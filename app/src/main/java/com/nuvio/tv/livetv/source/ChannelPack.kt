package com.nuvio.tv.livetv.source

import com.nuvio.tv.core.di.UNTRUSTED_JSON_MAX_DEPTH
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.M3uParser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedReader
import java.security.MessageDigest
import java.util.Locale

/** A pack stream's monitor state (Spec 60 D22: the states the pack keeps). */
@Serializable
enum class PackState {
    @SerialName("alive") ALIVE,
    @SerialName("suspect") SUSPECT,
    @SerialName("blocked") BLOCKED
}

/** One stream of the channel pack: [headers] = the row's Referer / User-Agent / Origin, null when none. */
@Serializable
data class PackStream(val url: String, val state: PackState, val headers: Map<String, String>? = null)

/**
 * One channel of the pack (Spec 73 S2b): the public list's rows merged by [key]; [streams] in list
 * order. Every kept stream is here, the ones Live TV does not list yet included (D1), for the scan (A2).
 */
@Serializable
data class PackChannel(
    val key: String,
    val name: String,
    val tvgId: String? = null,
    val country: String? = null,
    val groups: List<String> = emptyList(),
    val streams: List<PackStream>
)

/** The pack's figures before any download (`monitor/pack.json`, Spec 73 D9): what D2's facts line shows. */
data class PackSummary(val channels: Int, val countries: Int, val bytes: Long)

/**
 * The channel pack (Spec 73 S2b, Spec 60 D3/D22): the public list repo's `pool/streams.tsv` joined by
 * URL to the monitor's `monitor/state.tsv`. Kept: rows in state alive, suspect or blocked (D22),
 * except class drm (73/D7), rows with no state row (73/D8), YouTube (60/D7) and anything not
 * http(s). Pure: [LiveSourceManager] downloads, this reads. Both files are untrusted input: columns
 * are found by name (a missing one refuses the file), rows of the wrong width are skipped, and only
 * three header names survive.
 */
object ChannelPack {
    const val SOURCE_ID = "pack"
    const val NAME = "Channel pack"
    const val BASE_URL = "https://raw.githubusercontent.com/Arshad-Kamal/Nuvio-KE-channels/main/"
    const val STATE_URL = BASE_URL + "monitor/state.tsv"
    const val STREAMS_URL = BASE_URL + "pool/streams.tsv"
    const val SUMMARY_URL = BASE_URL + "monitor/pack.json"

    internal const val FORMAT_CHANGED = "Channel pack format changed"

    private const val SUMMARY_VERSION = 1
    private const val SCAN_KEY_BYTES = 8
    private const val HEX = "0123456789abcdef"
    private val HEADER_NAMES = mapOf("referer" to "Referer", "user-agent" to "User-Agent", "origin" to "Origin")
    private val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be")

    /**
     * The URLs of `state.tsv` the pack keeps, with their state: state alive, suspect or blocked,
     * class not drm, http(s) and not YouTube. Throws [SourceFetchException] when a needed column is
     * missing.
     */
    fun readState(reader: BufferedReader): Map<String, PackState> {
        val columns = Columns(reader.readLine(), "url", "state", "class")
        val kept = HashMap<String, PackState>()
        reader.forEachLine { line ->
            val cells = columns.cells(line) ?: return@forEachLine
            if (cells[columns["class"]] == "drm") return@forEachLine
            val state = when (cells[columns["state"]]) {
                "alive" -> PackState.ALIVE
                "suspect" -> PackState.SUSPECT
                "blocked" -> PackState.BLOCKED
                else -> return@forEachLine
            }
            val url = cells[columns["url"]]
            if (playableUrl(url)) kept[url] = state
        }
        return kept
    }

    /**
     * The pack's channels from `streams.tsv` rows whose URL [state] keeps ([readState]), merged by
     * [channelKey], in list order; a URL listed twice in one channel counts once. Linear in rows
     * (untrusted volume: one channel of many rows must not cost per row). Throws
     * [SourceFetchException] when a needed column is missing.
     */
    fun readStreams(reader: BufferedReader, state: Map<String, PackState>): List<PackChannel> {
        val columns = Columns(reader.readLine(), "name", "url", "country", "category", "headers", "channel")
        val byKey = LinkedHashMap<String, Building>()
        reader.forEachLine { line ->
            val cells = columns.cells(line) ?: return@forEachLine
            val url = cells[columns["url"]]
            val urlState = state[url] ?: return@forEachLine
            val name = cell(cells[columns["name"]]) ?: return@forEachLine
            val country = country(cell(cells[columns["country"]]))
            val channel = cell(cells[columns["channel"]])
            val key = channelKey(channel, name, country) ?: return@forEachLine
            val building = byKey.getOrPut(key) {
                Building(
                    PackChannel(
                        key = key,
                        name = name,
                        tvgId = channel?.takeIf(::iptvOrgId),
                        country = country,
                        groups = listOfNotNull(cell(cells[columns["category"]])),
                        streams = emptyList()
                    )
                )
            }
            if (building.urls.add(url)) {
                building.streams += PackStream(url, urlState, headers(cell(cells[columns["headers"]])))
            }
        }
        return byKey.values.map { it.first.copy(streams = it.streams) }
    }

    /** A channel being read: its first row's fields, then its streams and their URLs. */
    private class Building(val first: PackChannel) {
        val streams = ArrayList<PackStream>()
        val urls = HashSet<String>()
    }

    /**
     * What Live TV lists of the pack (D1, S3a): each channel with its [visibleStreams], channels
     * with none left out until a scan from this TV proves one.
     */
    fun listing(pack: List<PackChannel>, results: Map<String, PackScanResult> = emptyMap()): List<Channel> =
        pack.mapNotNull { ch ->
            val visible = visibleStreams(ch, results)
            if (visible.isEmpty()) return@mapNotNull null
            Channel(
                key = ch.key,
                sourceId = SOURCE_ID,
                name = ch.name,
                tvgId = ch.tvgId,
                country = ch.country,
                groups = ch.groups,
                streams = visible.map { LiveStream(it.url, it.headers.orEmpty()) }
            )
        }

    /**
     * The one rule for what Live TV lists of [channel] (D1, S3a): a stream this TV scanned counts
     * by its result (ok listed, failed hidden until the next scan), any other by the monitor's
     * state (alive listed). List order.
     */
    internal fun visibleStreams(channel: PackChannel, results: Map<String, PackScanResult>): List<PackStream> =
        channel.streams.filter { results[scanKey(it.url)]?.ok ?: (it.state == PackState.ALIVE) }

    /** A stream's key in the scan store: the first 16 hex digits of its URL's SHA-256. */
    fun scanKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val key = CharArray(SCAN_KEY_BYTES * 2)
        for (i in 0..<SCAN_KEY_BYTES) {
            val byte = digest[i].toInt() and 0xff
            key[2 * i] = HEX[byte ushr 4]
            key[2 * i + 1] = HEX[byte and 0x0f]
        }
        return String(key)
    }

    /** `monitor/pack.json` (D9: version 1, channels, countries, bytes); null when it is anything else. */
    fun readSummary(text: String): PackSummary? {
        val obj = M3uParser.jsonObjectOrNull(text.trim(), UNTRUSTED_JSON_MAX_DEPTH) ?: return null
        fun number(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }
        if (number("version")?.intOrNull != SUMMARY_VERSION) return null
        val channels = number("channels")?.intOrNull?.takeIf { it > 0 } ?: return null
        val countries = number("countries")?.intOrNull?.takeIf { it >= 0 } ?: return null
        val bytes = number("bytes")?.longOrNull?.takeIf { it > 0 } ?: return null
        return PackSummary(channels, countries, bytes)
    }

    /**
     * Stable key of a pack channel: the list's channel cell (an iptv-org id with its feed, or a FAST
     * provider id; feeds stay apart, as the approved figures count them), else name + country.
     * Null when neither gives one (a name of symbols only).
     */
    internal fun channelKey(channel: String?, name: String, country: String?): String? {
        if (channel != null) return "$SOURCE_ID#ch:${channel.lowercase(Locale.ROOT)}"
        val normalized = ChannelIdentity.normalizeName(name).ifEmpty { return null }
        return "$SOURCE_ID#nc:$normalized|${country.orEmpty()}"
    }

    /** An iptv-org channel id (`Name.cc` with an optional `@feed`), not a provider id (`plex:...`). */
    private fun iptvOrgId(channel: String): Boolean =
        ':' !in channel && ChannelIdentity.countryOfTvgId(channel) != null

    /** The app's country form; the list writes the United Kingdom both as GB and UK, the app as `uk`. */
    internal fun country(raw: String?): String? =
        ChannelIdentity.normalizeCountry(raw)?.let { if (it == "gb") "uk" else it }

    private fun playableUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return YOUTUBE_HOSTS.none { host == it || host.endsWith(".$it") }
    }

    /** The row's JSON headers cell: Referer, User-Agent and Origin with string values; anything else dropped. */
    private fun headers(cell: String?): Map<String, String>? {
        val obj = cell?.let { M3uParser.jsonObjectOrNull(it, UNTRUSTED_JSON_MAX_DEPTH) } ?: return null
        val kept = obj.entries.mapNotNull { (name, value) ->
            val canonical = HEADER_NAMES[name.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (text.isNullOrEmpty()) null else canonical to text
        }
        return kept.toMap().ifEmpty { null }
    }

    /** The list writes an empty cell as `-` or nothing. */
    private fun cell(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it != "-" }

    /** A TSV header's column positions by name; a missing name refuses the file. */
    private class Columns(header: String?, vararg needed: String) {
        private val names = header?.removePrefix("\uFEFF")?.split('\t').orEmpty()
        private val index = needed.associateWith { name ->
            names.indexOf(name).takeIf { it >= 0 } ?: throw SourceFetchException(FORMAT_CHANGED)
        }

        operator fun get(name: String): Int = index.getValue(name)

        /** A row's cells; null for a row of the wrong width (skipped, never guessed at). */
        fun cells(line: String): List<String>? =
            line.split('\t', limit = names.size + 1).takeIf { it.size == names.size }
    }
}
