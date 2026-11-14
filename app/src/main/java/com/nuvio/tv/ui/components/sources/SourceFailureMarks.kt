package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.R
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import com.nuvio.tv.core.stream.RecentFailure
import com.nuvio.tv.core.stream.RecentFailures
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.FailureStrings
import com.nuvio.tv.ui.screens.player.PlaybackFailureCopy

/**
 * Spec 75 S6 (D1, mockup stages 9-10): the red line under a source that failed in the last day:
 * [reason] in two to four words (the D4 table's row mark) and its [age] ("2 min ago", "just now").
 */
data class SourceFailureMark(val reason: String, val age: String)

internal object SourceFailureMarks {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /**
     * [stream]'s mark: the store's entry aged to [recent]'s read time, else, for a source that failed
     * in this player session ([deadUrls]) that the store has no entry for, "Failed · just now".
     */
    fun forRow(stream: Stream, recent: RecentFailures, deadUrls: Set<String>, s: FailureStrings): SourceFailureMark? {
        recent.markFor(stream)?.let { return of(it, recent.readAtEpochMs, s) }
        val url = stream.getStreamUrl() ?: return null
        return if (url in deadUrls) justNow(s) else null
    }

    /** A stored failure [recent] as of [nowEpochMs]. */
    fun of(recent: RecentFailure, nowEpochMs: Long, s: FailureStrings): SourceFailureMark =
        SourceFailureMark(reason(recent.mark, s), age((nowEpochMs - recent.atEpochMs).coerceAtLeast(0L), s))

    /** A source dead in this player session that the store has no entry for: "Failed · just now". */
    fun justNow(s: FailureStrings): SourceFailureMark =
        SourceFailureMark(reason(null, s), s.get(R.string.failure_ago_just_now))

    /** The D4 row mark; "Too slow: 18 of 70 Mbit/s" when both speeds are known. */
    fun reason(mark: FailureMark?, s: FailureStrings): String {
        val measured = mark?.measuredMbps
        val needed = mark?.neededMbps
        if (mark?.cause == FailureCause.TOO_SLOW && measured != null && needed != null) {
            return s.get(R.string.failure_mark_too_slow_speeds, PlaybackFailureCopy.mbpsDown(measured), PlaybackFailureCopy.mbpsUp(needed))
        }
        return s.get(labelRes(mark?.cause))
    }

    /** The row mark of [cause]; an entry from before causes were kept (null) reads "Failed". */
    fun labelRes(cause: FailureCause?): Int = when (cause) {
        FailureCause.LINK_EXPIRED -> R.string.failure_mark_link_expired
        FailureCause.BLOCKED -> R.string.failure_mark_blocked
        FailureCause.REMOVED -> R.string.failure_mark_removed
        FailureCause.RATE_LIMITED -> R.string.failure_mark_rate_limited
        FailureCause.SERVER_DOWN -> R.string.failure_mark_server_down
        FailureCause.OTHER_HTTP -> R.string.failure_mark_other_http
        FailureCause.NO_CONNECTION -> R.string.failure_mark_no_connection
        FailureCause.WEB_PAGE -> R.string.failure_mark_web_page
        FailureCause.TINY_FILE -> R.string.failure_mark_tiny_file
        FailureCause.TEXT -> R.string.failure_mark_text
        FailureCause.LOGIN -> R.string.failure_mark_login
        FailureCause.UNREADABLE -> R.string.failure_mark_unreadable
        FailureCause.TOO_SLOW -> R.string.failure_mark_too_slow
        FailureCause.NO_DATA -> R.string.failure_mark_no_data
        FailureCause.PLACEHOLDER -> R.string.failure_mark_placeholder
        FailureCause.VIDEO_UNSUPPORTED -> R.string.failure_mark_video_unsupported
        FailureCause.DECODER_STUCK -> R.string.failure_mark_decoder_stuck
        FailureCause.CORRUPT -> R.string.failure_mark_corrupt
        FailureCause.FROZEN -> R.string.failure_mark_frozen
        FailureCause.AUDIO_FAILED -> R.string.failure_mark_audio_failed
        FailureCause.TORRENT -> R.string.failure_mark_torrent
        FailureCause.ENGINE -> R.string.failure_mark_engine
        FailureCause.NO_URL -> R.string.failure_mark_no_url
        FailureCause.WRONG_FILE, FailureCause.UNKNOWN, null -> R.string.failure_mark_unknown
    }

    /** "just now" under a minute, "N min ago" under an hour, else "N h ago" (the window is a day). */
    fun age(elapsedMs: Long, s: FailureStrings): String = when {
        elapsedMs < MINUTE_MS -> s.get(R.string.failure_ago_just_now)
        elapsedMs < HOUR_MS -> s.get(R.string.failure_ago_minutes, (elapsedMs / MINUTE_MS).toInt())
        else -> s.get(R.string.failure_ago_hours, (elapsedMs / HOUR_MS).toInt())
    }
}
