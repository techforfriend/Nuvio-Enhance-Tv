package com.nuvio.tv.livetv.parse

import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

@Serializable
data class EpgChannel(val id: String, val displayNames: List<String>, val icon: String?)

/**
 * One retained programme. `desc` is not kept (Spec 38 S1): nothing shows it, and on i.mjh.nz's
 * Pluto guide it dominated the cached snapshot (see Spec 38 S1 for the measured sizes). Caches
 * written before still decode (`localStoreJson` ignores unknown keys).
 */
@Serializable
data class EpgProgramme(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    /** The first `<category>`: the full-screen OSD eyebrow shows it. */
    val category: String? = null,
    /** XMLTV `<live/>`: broadcast live (D16 LIVE badge on the row). */
    val live: Boolean = false
)

/**
 * What one XMLTV file leaves after a parse: every channel, and per channel the programmes
 * overlapping [windowStartMs, windowEndMs) plus the first one starting at or after the window end,
 * sorted by start (Spec 34 D14 look-ahead retention: the last in-window programme still has a next;
 * not kept when the per-channel cap dropped part of the window).
 * [complete] is false when the file was cut short (malformed XML, size cap): what was read is kept.
 */
@Serializable
data class EpgSnapshot(
    val channels: Map<String, EpgChannel>,
    val programmes: Map<String, List<EpgProgramme>>,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val complete: Boolean
) {
    /** Current and next programme of [channelId] at [nowMs], re-derived from the retained window. */
    fun nowNext(channelId: String, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> {
        val list = programmes[channelId].orEmpty()
        val now = list.lastOrNull { it.startMs <= nowMs && nowMs < it.stopMs }
        val next = list.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs) && it !== now }
        return now to next
    }
}

/**
 * Streaming XMLTV parser (Spec 34 D10/D14). Input is untrusted: gzip is detected by magic bytes
 * (not URL or headers); [GuardedInputStream] caps the (inflated) size and any text run and rejects
 * entity declarations before the platform parser sees them, so no parser's DOCTYPE handling is
 * relied on; only programmes inside the look-ahead window are kept, so memory follows the window,
 * not the file. Subset read: `channel{id, display-name, icon}`,
 * `programme{start, stop, channel, title, category, live}`. Programmes without `stop` are dropped
 * (both D5 sources carry it).
 *
 * [newParser] is `android.util.Xml::newPullParser` in the app; JVM tests pass kxml2's parser.
 * [maxProgrammesPerChannel] bounds each channel's in-window list: the default fits the now/next
 * look-ahead; a day window (Spec 67 guide grid) passes a larger one.
 */
class XmltvNowNextParser(
    private val newParser: () -> XmlPullParser,
    private val maxInputBytes: Long = MAX_INPUT_BYTES,
    private val maxProgrammesPerChannel: Int = MAX_PROGRAMMES_PER_CHANNEL
) {
    init {
        require(maxProgrammesPerChannel > 0) { "maxProgrammesPerChannel must be positive" }
    }

    private class Slot {
        val inWindow = ArrayList<EpgProgramme>()
        var afterWindow: EpgProgramme? = null
        /** The cap dropped an in-window programme: the window's tail is missing. */
        var capped = false
    }

    /** Parses [input] keeping the window [windowStartMs, windowStartMs + lookAheadMs) (the app passes a day window). */
    fun parse(
        input: InputStream,
        windowStartMs: Long,
        lookAheadMs: Long = DEFAULT_LOOK_AHEAD_MS,
        keepChannel: (String) -> Boolean = { true }
    ): EpgSnapshot {
        val windowEnd = windowStartMs + lookAheadMs
        val channels = LinkedHashMap<String, EpgChannel>()
        val slots = HashMap<String, Slot>()
        // kxml reaches END_DOCUMENT on a truncated file without an error: complete only once </tv> closes.
        var rootClosed = false
        var failed = false
        try {
            val parser = newParser()
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            parser.setInput(openGuarded(input, maxInputBytes), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.END_TAG && parser.depth == 1) rootClosed = true
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser)?.let { ch ->
                            if (channels.size < MAX_CHANNELS || ch.id in channels) channels[ch.id] = ch
                        }
                        "programme" -> readProgramme(parser, windowStartMs, windowEnd, keepChannel, slots)
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            failed = true
        } catch (e: IOException) {
            failed = true
        } catch (e: RuntimeException) {
            // Trust boundary: whatever a platform parser throws on hostile input keeps the partial snapshot.
            failed = true
        }
        val programmes = slots
            // A capped channel's first-after-window programme would read as the next of its last kept
            // one and hide where its data ends (day schedule), so it goes.
            .mapValues { (_, slot) ->
                slot.inWindow.sortedBy { it.startMs } + listOfNotNull(slot.afterWindow.takeUnless { slot.capped })
            }
            .filterValues { it.isNotEmpty() }
        return EpgSnapshot(channels, programmes, windowStartMs, windowEnd, complete = rootClosed && !failed)
    }

    private fun readChannel(parser: XmlPullParser): EpgChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>()
        var icon: String? = null
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "display-name" -> readText(parser).takeIf { it.isNotEmpty() }?.let { names += it }
                "icon" -> if (icon == null) icon = parser.getAttributeValue(null, "src")?.trim()?.ifEmpty { null }
            }
        }
        return if (id.isEmpty()) null else EpgChannel(id, names, icon)
    }

    /** Files a wanted programme under its channel's slot; anything else is skipped unread. */
    private fun readProgramme(
        parser: XmlPullParser,
        windowStart: Long,
        windowEnd: Long,
        keepChannel: (String) -> Boolean,
        slots: MutableMap<String, Slot>
    ) {
        // Cheap checks before time parsing; distinct channel keys are capped like channels.
        val channel = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val slot = when {
            channel.isEmpty() || !keepChannel(channel) -> null
            else -> slots[channel] ?: if (slots.size < MAX_CHANNELS) Slot().also { slots[channel] = it } else null
        }
        val start = slot?.let { parseXmltvTime(parser.getAttributeValue(null, "start")) }
        val stop = start?.let { parseXmltvTime(parser.getAttributeValue(null, "stop")) }
        if (slot == null || start == null || stop == null || stop <= start || stop <= windowStart) {
            skipElement(parser)
            return
        }
        // Where it goes: in-window list (earliest-starting MAX kept, files are not always sorted)
        // or the single first-after-window slot. -1 = append; null = not wanted.
        val list = slot.inWindow
        val replaceAt: Int? = when {
            start >= windowEnd -> if ((slot.afterWindow?.startMs ?: Long.MAX_VALUE) > start) -1 else null
            list.size < maxProgrammesPerChannel -> -1
            else -> list.indices.maxBy { list[it].startMs }.takeIf { list[it].startMs > start }.also { slot.capped = true }
        }
        if (replaceAt == null) {
            skipElement(parser)
            return
        }
        var title: String? = null
        var category: String? = null
        var live = false
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> if (title == null) title = readText(parser) else skipElement(parser)
                "category" -> if (category == null) category = readText(parser) else skipElement(parser)
                "live" -> live = true
                else -> skipElement(parser)
            }
        }
        val p = EpgProgramme(start, stop, title.orEmpty(), category?.ifEmpty { null }, live)
        when {
            start >= windowEnd -> slot.afterWindow = p
            replaceAt == -1 -> list += p
            else -> list[replaceAt] = p
        }
    }

    /**
     * Text of the current element up to its end tag with child elements skipped (mixed content
     * must not abort the file the way `nextText()` would), trimmed and cut to [MAX_TEXT_CHARS].
     */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.TEXT && sb.length < MAX_TEXT_CHARS * 2) sb.append(parser.text)
        }
        return sb.trim().take(MAX_TEXT_CHARS).toString()
    }

    private fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    companion object {
        const val DEFAULT_LOOK_AHEAD_MS = 6L * 60 * 60 * 1000
        /** D10 cap on the bytes handed to the XML parser (inflated for gzip; plain XML too). */
        const val MAX_INPUT_BYTES = 500L * 1024 * 1024
        /** Longest run of bytes without a `<` (one text node or attribute); real files stay far below. */
        const val MAX_TEXT_RUN_BYTES = 1024 * 1024
        const val MAX_CHANNELS = 50_000
        const val MAX_PROGRAMMES_PER_CHANNEL = 48
        private const val MAX_TEXT_CHARS = 500
        private val OFFSET = Regex("^([+-])(\\d{2}):?(\\d{2})")

        /**
         * Inflates [input] when it starts with the gzip magic (1f 8b), then wraps either form in a
         * [GuardedInputStream] capped at [maxBytes].
         */
        fun openGuarded(input: InputStream, maxBytes: Long = MAX_INPUT_BYTES): InputStream {
            val buffered = if (input.markSupported()) input else BufferedInputStream(input)
            buffered.mark(2)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val gzip = b0 == 0x1f && b1 == 0x8b
            val plain = if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered
            return GuardedInputStream(plain, maxBytes, MAX_TEXT_RUN_BYTES)
        }

        /**
         * XMLTV time: `yyyyMMddHHmmss` (seconds, then minutes, may be missing) and an optional
         * `+HHMM` / `-HHMM` offset (UTC without one). Null when unreadable.
         */
        fun parseXmltvTime(raw: String?): Long? {
            val s = raw?.trim() ?: return null
            val digits = s.takeWhile { it.isDigit() }
            if (digits.length < 10) return null
            val year = digits.substring(0, 4).toInt()
            val month = digits.substring(4, 6).toInt()
            val day = digits.substring(6, 8).toInt()
            val hour = digits.substring(8, 10).toInt()
            val minute = if (digits.length >= 12) digits.substring(10, 12).toInt() else 0
            val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
            val rest = s.substring(digits.length).trim()
            val offsetMinutes = OFFSET.find(rest)?.let { m ->
                val sign = if (m.groupValues[1] == "-") -1 else 1
                sign * (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt())
            } ?: 0
            return runCatching {
                java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                    .toEpochSecond(java.time.ZoneOffset.ofTotalSeconds(offsetMinutes * 60)) * 1000
            }.getOrNull()
        }
    }
}

/**
 * Throws [IOException] once more than [max] bytes were read (gzip bomb, endless body), when
 * [maxRun] bytes pass without a `<` (the pull parser materialises a text node whole before any
 * truncation), or at an `<!ENTITY` declaration (XMLTV never needs one; this blocks entity
 * expansion whatever the platform parser does with an internal DTD subset).
 */
internal class GuardedInputStream(
    input: InputStream,
    private val max: Long,
    private val maxRun: Int
) : FilterInputStream(input) {
    private var count = 0L
    private var run = 0
    private var entityMatched = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            if (++count > max) throw IOException("EPG larger than $max bytes")
            inspect(b.toByte())
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > max) throw IOException("EPG larger than $max bytes")
            for (i in off until off + n) inspect(b[i])
        }
        return n
    }

    private fun inspect(b: Byte) {
        if (b == LT) {
            run = 0
            entityMatched = 1
            return
        }
        if (++run > maxRun) throw IOException("EPG text run over $maxRun bytes")
        entityMatched = if (entityMatched in 1 until ENTITY.size && b == ENTITY[entityMatched]) entityMatched + 1 else 0
        if (entityMatched == ENTITY.size) throw IOException("EPG declares an entity")
    }

    private companion object {
        const val LT = '<'.code.toByte()
        val ENTITY = "<!ENTITY".toByteArray(Charsets.US_ASCII)
    }
}
