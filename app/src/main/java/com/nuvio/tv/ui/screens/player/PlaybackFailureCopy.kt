package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.FailureFacts
import com.nuvio.tv.core.player.PlaybackFailure
import com.nuvio.tv.core.player.PlaybackStep
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy
import com.nuvio.tv.core.player.ServerResponseFinding
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.player.FailoverNotice
import com.nuvio.tv.core.player.TriedSource
import com.nuvio.tv.core.stream.FailureMark
import com.nuvio.tv.ui.components.formatStreamSizeLabel
import com.nuvio.tv.ui.components.sources.SourceFailureMarks
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One run of a Details line: plain, bold (the fact) or muted (its note), as the mockup draws them;
 * a line's runs are shown joined by single spaces (string resources cannot end in a space).
 */
data class DetailSpan(val text: String, val tone: Tone) {
    enum class Tone { PLAIN, BOLD, MUTED }
}

/**
 * The error box's words for one failure (Spec 75 S4). [eyebrow] is "Playback error", or with the
 * count of sources tried when the failover ran out (S5); [also] then counts the earlier ones by
 * reason.
 */
data class FailureCopy(
    val title: String,
    val body: String,
    val details: List<List<DetailSpan>>,
    val eyebrow: String,
    val also: String? = null
)

/** String lookup, so the copy is pinned by plain unit tests (the app passes [contextFailureStrings]). */
interface FailureStrings {
    fun get(id: Int, vararg args: Any): String

    /** The [count] form of the plurals resource [id], formatted with [args]. */
    fun plural(id: Int, count: Int, vararg args: Any): String
}

/**
 * The app's lookup. No arguments = the raw string ([Context.getString] with an empty vararg runs
 * String.format and throws on a template such as "%1$s GB").
 */
internal fun contextFailureStrings(context: android.content.Context) = object : FailureStrings {
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)
}

/**
 * Spec 75 S4: the approved copy (spec § Plan table, mockup drawn copy) for a [PlaybackFailure]:
 * title = the cause in plain words, body = what to do, Details = the server/player line, the
 * probe or measured-speed line when there is one, Host (+ via addon), File + size (D4).
 * Untrusted text (a page title, an engine message) only ever lands inside a plain string.
 */
internal object PlaybackFailureCopy {

    private const val MIB = 1024L * 1024L
    private const val MEDIA_TYPE_MAX = 60
    private const val MEDIA_TYPE_PUNCTUATION = "/+-._!#$&^"

    /**
     * [failure]'s words; [tried] = the sources the failover already left in this chain, oldest
     * first (Spec 75 S5: non-empty means every source was tried and [failure] is the last).
     */
    fun of(failure: PlaybackFailure, s: FailureStrings, tried: List<TriedSource> = emptyList()): FailureCopy {
        val f = failure.facts
        val service = resolveStreamProvider(f.streamName, f.streamDescription, f.addon, host = null)
            ?: s.get(R.string.failure_service_fallback)
        val pickAnother = s.get(R.string.failure_body_pick_another)
        val (title, body) = when (failure.cause) {
            FailureCause.LINK_EXPIRED -> s.get(R.string.failure_title_link_expired) to s.get(
                if (tried.isEmpty()) R.string.failure_body_link_expired else R.string.failure_body_link_expired_tried,
                service
            )
            FailureCause.BLOCKED ->
                s.get(R.string.failure_title_blocked) to s.get(R.string.failure_body_blocked, service)
            FailureCause.REMOVED ->
                s.get(R.string.failure_title_removed) to s.get(R.string.failure_body_removed, service)
            FailureCause.RATE_LIMITED ->
                s.get(R.string.failure_title_rate_limited) to s.get(R.string.failure_body_rate_limited, service)
            FailureCause.SERVER_DOWN ->
                s.get(R.string.failure_title_server_down) to s.get(R.string.failure_body_server_down, service)
            FailureCause.OTHER_HTTP -> s.get(R.string.failure_title_other_http) to
                ((f.httpCode ?: f.probe?.status)?.let { s.get(R.string.failure_body_other_http, it.toString()) } ?: pickAnother)
            FailureCause.NO_CONNECTION ->
                s.get(R.string.failure_title_no_connection) to s.get(R.string.failure_body_no_connection)
            FailureCause.WEB_PAGE -> s.get(R.string.failure_title_web_page) to
                (f.probe?.title?.let { s.get(R.string.failure_body_web_page, it) } ?: pickAnother)
            FailureCause.TINY_FILE -> tinyFileTitle(f, s) to pickAnother
            FailureCause.TEXT -> (
                f.probe?.contentType?.let(::mediaType)?.takeIf { it.isNotEmpty() }
                    ?.let { s.get(R.string.failure_title_text_typed, it) }
                    ?: s.get(R.string.failure_title_text)
                ) to pickAnother
            FailureCause.LOGIN -> (
                f.probe?.loginHostPath?.let { s.get(R.string.failure_title_login_at, it) }
                    ?: s.get(R.string.failure_title_login)
                ) to pickAnother
            FailureCause.UNREADABLE ->
                s.get(R.string.failure_title_unreadable) to s.get(R.string.failure_body_unreadable)
            FailureCause.TOO_SLOW -> {
                val measured = f.measuredMbps
                val needed = f.neededMbps
                s.get(R.string.failure_title_too_slow) to if (measured != null && needed != null) {
                    s.get(R.string.failure_body_too_slow, mbpsDown(measured), mbpsUp(needed))
                } else {
                    pickAnother
                }
            }
            FailureCause.NO_DATA -> s.get(R.string.failure_title_no_data) to s.get(R.string.failure_body_no_data)
            FailureCause.PLACEHOLDER ->
                s.get(R.string.failure_title_placeholder) to s.get(R.string.failure_body_placeholder)
            FailureCause.VIDEO_UNSUPPORTED ->
                s.get(R.string.failure_title_video_unsupported) to s.get(R.string.failure_body_video_unsupported)
            FailureCause.DECODER_STUCK ->
                s.get(R.string.failure_title_decoder_stuck) to s.get(R.string.failure_body_decoder_stuck)
            FailureCause.CORRUPT -> s.get(R.string.failure_title_corrupt) to s.get(R.string.failure_body_corrupt)
            FailureCause.FROZEN -> s.get(R.string.failure_title_frozen) to s.get(R.string.failure_body_frozen)
            FailureCause.AUDIO_FAILED ->
                s.get(R.string.failure_title_audio_failed) to s.get(R.string.failure_body_audio_failed)
            FailureCause.TORRENT -> s.get(R.string.failure_title_torrent) to (
                f.engineMessage?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
                    ?.let { s.get(R.string.failure_body_torrent, it) }
                    ?: s.get(R.string.failure_body_torrent_plain)
                )
            FailureCause.ENGINE -> s.get(R.string.failure_title_engine) to s.get(R.string.failure_body_engine)
            FailureCause.NO_URL -> s.get(R.string.failure_title_no_url) to s.get(R.string.failure_body_no_url)
            FailureCause.WRONG_FILE, FailureCause.UNKNOWN ->
                s.get(R.string.failure_title_unknown) to s.get(R.string.failure_body_unknown)
        }
        if (tried.isEmpty()) return FailureCopy(title, body, details(failure, s), eyebrow = s.get(R.string.failure_eyebrow))
        return FailureCopy(
            title = title,
            body = body,
            details = details(failure, s) + tried.map { triedLine(it, s) },
            eyebrow = s.get(R.string.failure_eyebrow_tried, tried.size + 1),
            also = s.plural(R.plurals.failure_tried_before, tried.size, countsByReason(tried, s), tried.size)
        )
    }

    /** Spec 75 S5 (stage 8): "Link expired, trying next source (2/3)". */
    fun failoverLine(notice: FailoverNotice, s: FailureStrings): String =
        s.get(R.string.failure_failover_trying, SourceFailureMarks.reason(notice.mark, s), notice.attempt, notice.max)

    /** The failover toast: "Link expired, playing the next source". */
    fun failoverToast(mark: FailureMark, s: FailureStrings): String =
        s.get(R.string.failure_failover_toast, SourceFailureMarks.reason(mark, s))

    /**
     * "2 expired links, 1 web page, 1 too slow": one count per reason, the most frequent first
     * (a tie keeps the order the sources failed in).
     */
    fun countsByReason(tried: List<TriedSource>, s: FailureStrings): String =
        tried.groupBy { countRes(it.mark.cause) }.entries
            .sortedByDescending { it.value.size }
            .joinToString(", ") { (res, sources) -> s.plural(res, sources.size, sources.size) }

    /** One earlier source in Details: its mark in bold, then addon and file, as the other lines. */
    private fun triedLine(source: TriedSource, s: FailureStrings): List<DetailSpan> = listOfNotNull(
        bold(SourceFailureMarks.reason(source.mark, s)),
        source.addon?.takeIf { it.isNotBlank() }?.let { muted("\u00b7 $it") },
        source.fileName?.takeIf { it.isNotBlank() }?.let { muted("\u00b7 $it") }
    )

    private fun countRes(cause: FailureCause): Int = when (cause) {
        FailureCause.LINK_EXPIRED -> R.plurals.failure_count_link_expired
        FailureCause.BLOCKED -> R.plurals.failure_count_blocked
        FailureCause.REMOVED -> R.plurals.failure_count_removed
        FailureCause.RATE_LIMITED -> R.plurals.failure_count_rate_limited
        FailureCause.SERVER_DOWN -> R.plurals.failure_count_server_down
        FailureCause.OTHER_HTTP -> R.plurals.failure_count_other_http
        FailureCause.NO_CONNECTION -> R.plurals.failure_count_no_connection
        FailureCause.WEB_PAGE -> R.plurals.failure_count_web_page
        FailureCause.TINY_FILE -> R.plurals.failure_count_tiny_file
        FailureCause.TEXT -> R.plurals.failure_count_text
        FailureCause.LOGIN -> R.plurals.failure_count_login
        FailureCause.UNREADABLE -> R.plurals.failure_count_unreadable
        FailureCause.TOO_SLOW -> R.plurals.failure_count_too_slow
        FailureCause.NO_DATA -> R.plurals.failure_count_no_data
        FailureCause.PLACEHOLDER -> R.plurals.failure_count_placeholder
        FailureCause.VIDEO_UNSUPPORTED -> R.plurals.failure_count_video_unsupported
        FailureCause.DECODER_STUCK -> R.plurals.failure_count_decoder_stuck
        FailureCause.CORRUPT -> R.plurals.failure_count_corrupt
        FailureCause.FROZEN -> R.plurals.failure_count_frozen
        FailureCause.AUDIO_FAILED -> R.plurals.failure_count_audio_failed
        FailureCause.TORRENT -> R.plurals.failure_count_torrent
        FailureCause.ENGINE -> R.plurals.failure_count_engine
        FailureCause.NO_URL -> R.plurals.failure_count_no_url
        FailureCause.WRONG_FILE, FailureCause.UNKNOWN -> R.plurals.failure_count_unknown
    }

    /** D4: the server/player line always, then the probe, measured speed, host and file when known. */
    fun details(failure: PlaybackFailure, s: FailureStrings): List<List<DetailSpan>> {
        val f = failure.facts
        return listOfNotNull(
            playerLine(failure, s),
            f.probe?.let { probeLine(it, s) },
            measuredLine(f, s),
            f.host?.let { host ->
                listOfNotNull(
                    plain(s.get(R.string.failure_detail_host)),
                    bold(host),
                    f.addon?.takeIf { it.isNotBlank() }?.let { muted(s.get(R.string.failure_detail_via, it)) }
                )
            },
            fileLine(f, s)
        )
    }

    private fun playerLine(failure: PlaybackFailure, s: FailureStrings): List<DetailSpan> {
        val f = failure.facts
        val code = f.httpCode
        if (code != null) {
            return listOfNotNull(
                plain(s.get(R.string.failure_detail_server_answered)),
                bold(listOfNotNull("HTTP $code", f.httpStatus).joinToString(" ")),
                f.playerError?.let { muted("\u00b7 $it") }
            )
        }
        val player = plain(s.get(R.string.failure_detail_player))
        f.playerError?.let { return listOf(player, bold(it)) }
        val fire = f.watchdogFire
        val waited = f.waitedMs
        if (fire != null && waited != null) {
            val what = when (fire) {
                PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
                PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack -> R.string.failure_detail_watchdog_no_data
                PlayerStartupWatchdogPolicy.FireReason.DataStillArriving -> R.string.failure_detail_watchdog_still_loading
                PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported -> R.string.failure_detail_watchdog_video_refused
                PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive -> R.string.failure_detail_watchdog_no_picture
            }
            return listOf(player, bold(s.get(what, duration(waited, s))), muted(s.get(R.string.failure_detail_watchdog_note)))
        }
        f.frozenForMs?.let { return listOf(player, bold(s.get(R.string.failure_detail_frozen_for, duration(it, s)))) }
        return listOf(player, bold(f.engineMessage?.takeIf { it.isNotBlank() } ?: failure.message))
    }

    private fun probeLine(probe: ServerResponseFinding, s: FailureStrings): List<DetailSpan> {
        val answer = listOfNotNull(
            probe.status.toString(),
            probe.contentType?.let(::mediaType)?.takeIf { it.isNotEmpty() }
        ).joinToString(" ") + (probe.sizeBytes?.let { ", " + size(it, s) } ?: "")
        val note = when {
            probe.title != null -> s.get(R.string.failure_detail_probe_title, probe.title)
            probe.loginHostPath != null -> s.get(R.string.failure_detail_probe_login, probe.loginHostPath)
            else -> null
        }
        return listOfNotNull(plain(s.get(R.string.failure_detail_probe)), bold(answer), note?.let { muted("\u00b7 $it") })
    }

    private fun measuredLine(f: FailureFacts, s: FailureStrings): List<DetailSpan>? {
        val measured = f.measuredMbps ?: return null
        val spans = mutableListOf(
            plain(s.get(R.string.failure_detail_measured)),
            // Rounded down as in the body, so the two never disagree (17.96 is "17" and "17.9").
            bold(s.get(R.string.failure_detail_mbps, oneDecimal(floor(measured * 10.0) / 10.0)))
        )
        f.measuredOverMs?.let { spans += muted(s.get(R.string.failure_detail_over, duration(it, s))) }
        f.neededMbps?.let { needed ->
            spans += plain(s.get(R.string.failure_detail_needs))
            spans += bold(s.get(R.string.failure_detail_about_mbps, mbpsUp(needed)))
            val sizeBytes = f.fileSizeBytes
            val minutes = f.runtimeMinutes
            if (sizeBytes != null && minutes != null) {
                spans += muted(s.get(R.string.failure_detail_size_over_runtime, size(sizeBytes, s), minutes))
            }
        }
        return spans
    }

    private fun fileLine(f: FailureFacts, s: FailureStrings): List<DetailSpan>? {
        val sizeText = f.fileSizeBytes?.let { size(it, s) }
        val name = f.fileName?.takeIf { it.isNotBlank() }
        val label = plain(s.get(R.string.failure_detail_file))
        return when {
            sizeText != null -> listOfNotNull(label, bold(sizeText), name?.let { muted("\u00b7 $it") })
            name != null -> listOf(label, bold(name))
            else -> null
        }
    }

    private fun tinyFileTitle(f: FailureFacts, s: FailureStrings): String {
        val got = f.probe?.sizeBytes ?: return s.get(R.string.failure_title_tiny_file_plain)
        // The listing's size: what the player opened is the tiny reply itself.
        val expected = f.listedSizeBytes?.takeIf { it > got }
        return if (expected != null) {
            s.get(R.string.failure_title_tiny_file, size(got, s), size(expected, s))
        } else {
            s.get(R.string.failure_title_tiny_file_unsized, size(got, s))
        }
    }

    /** The step's label, in the order the line draws it. */
    fun stepLabel(step: PlaybackStep): Int = when (step) {
        PlaybackStep.SOURCES -> R.string.failure_step_sources
        PlaybackStep.LINK -> R.string.failure_step_link
        PlaybackStep.CONNECT -> R.string.failure_step_connect
        PlaybackStep.READ -> R.string.failure_step_read
        PlaybackStep.PICTURE -> R.string.failure_step_picture
        PlaybackStep.SOUND -> R.string.failure_step_sound
    }

    /** A shown duration: "1.4 s" under a minute, else whole minutes ("40 min"). */
    fun duration(ms: Long, s: FailureStrings): String =
        if (ms < 60_000L) {
            s.get(R.string.failure_time_seconds, oneDecimal(ms / 1000.0))
        } else {
            s.get(R.string.failure_time_minutes, (ms / 60_000L).toInt())
        }

    /** "2 KB" under 1 MiB (rounded up, never "0"), else the Sources rows' "6.1 GB" / "512 MB". */
    fun size(bytes: Long, s: FailureStrings): String =
        if (bytes < MIB) {
            s.get(R.string.unit_size_kb, ceil(bytes / 1024.0).toLong().coerceAtLeast(1L).toString())
        } else {
            formatStreamSizeLabel(bytes, s.get(R.string.unit_size_gb), s.get(R.string.unit_size_mb))
        }

    /**
     * The server's media type without parameters ("text/html; charset=utf-8" -> "text/html"),
     * reduced to the characters a media type may hold and capped: it is untrusted header text.
     */
    private fun mediaType(contentType: String): String =
        contentType.substringBefore(';').trim()
            .filter { it.isLetterOrDigit() && it.code < 128 || it in MEDIA_TYPE_PUNCTUATION }
            .take(MEDIA_TYPE_MAX)

    private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

    /**
     * Mbit/s for the too-slow sentence: what arrived rounded down and what the file needs rounded
     * up, so a speed just under the need never reads as equal ("69 ... about 70"); one decimal
     * under 10 so a trickle never reads "0".
     */
    fun mbpsDown(value: Double): String =
        if (value < 10.0) oneDecimal(floor(value * 10.0) / 10.0) else floor(value).toLong().toString()

    fun mbpsUp(value: Double): String =
        if (value < 10.0) oneDecimal(ceil(value * 10.0) / 10.0) else ceil(value).toLong().toString()

    private fun plain(text: String) = DetailSpan(text, DetailSpan.Tone.PLAIN)
    private fun bold(text: String) = DetailSpan(text, DetailSpan.Tone.BOLD)
    private fun muted(text: String) = DetailSpan(text, DetailSpan.Tone.MUTED)
}
