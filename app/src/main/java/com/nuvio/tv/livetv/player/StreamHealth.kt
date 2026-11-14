package com.nuvio.tv.livetv.player

import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.serialization.Serializable

/** Why one attempt at a stream failed, as the ladder classifies it (Spec 34 D8). */
@Serializable
enum class FailureKind {
    /** DNS, connect, TLS, timeouts, 5xx/429: may heal soon. */
    NETWORK,

    /** Startup/mid-play watchdog, behind-live-window loop, unexpected end. */
    STALL,

    /** 401/403/451. */
    BLOCKED,

    /** 400/404/410. */
    DEAD,

    /** The container or playlist cannot be read. */
    PARSE,

    /** A licensed stream failed: refused, a key system this TV lacks, or a licence it rejected (Spec 74 D5). */
    DRM
}

/** A stream's derived health (D8). Only [ALIVE] and the network-class states heal on their own. */
enum class StreamState { ALIVE, FLAKY, UNREACHABLE, DISPUTED, BLOCKED, DEAD }

/** What is remembered per stream: the last good play and the current failure run. */
@Serializable
data class StreamHealthRecord(
    val lastPlayedMs: Long? = null,
    val lastFailedMs: Long? = null,
    val lastFailure: FailureKind? = null,
    /** Consecutive failures since the last good play. */
    val failures: Int = 0
) {
    val lastAtMs: Long get() = maxOf(lastPlayedMs ?: 0L, lastFailedMs ?: 0L)
}

/**
 * Check-on-play stream health (Spec 34 D8): pure rules, no I/O. States, cooldowns and the order
 * streams are tried in. A stream "played recently" when it played within [RECENT_PLAY_MS] before
 * its failure: the failure then reads as flaky (network) or disputed (an HTTP block/dead code,
 * e.g. a token or geo flap) and cools down on the short network clock rather than for a day.
 * Times are wall-clock (they are persisted); a failure stamped in the future (the clock moved
 * back) never cools, so clock skew cannot lock a channel out.
 */
object StreamHealth {

    const val RECENT_PLAY_MS = 24L * 60 * 60 * 1000
    const val BASE_COOLDOWN_MS = 5L * 60 * 1000
    const val MAX_COOLDOWN_MS = 24L * 60 * 60 * 1000

    /** The stream URL as the source wrote it, trimmed (headers do not change which server answers). */
    fun key(stream: LiveStream): String = stream.url.trim()

    /** A good play ends the failure run. */
    fun played(nowMs: Long): StreamHealthRecord = StreamHealthRecord(lastPlayedMs = nowMs)

    fun failed(record: StreamHealthRecord?, kind: FailureKind, nowMs: Long): StreamHealthRecord {
        val current = record ?: StreamHealthRecord()
        return current.copy(lastFailedMs = nowMs, lastFailure = kind, failures = current.failures + 1)
    }

    fun state(record: StreamHealthRecord): StreamState {
        val failure = record.lastFailure ?: return StreamState.ALIVE
        val failedMs = record.lastFailedMs ?: return StreamState.ALIVE
        val playedMs = record.lastPlayedMs
        if (playedMs != null && playedMs >= failedMs) return StreamState.ALIVE
        val playedRecently = playedMs != null && failedMs - playedMs < RECENT_PLAY_MS
        return when (failure) {
            FailureKind.NETWORK, FailureKind.STALL -> if (playedRecently) StreamState.FLAKY else StreamState.UNREACHABLE
            FailureKind.BLOCKED -> if (playedRecently) StreamState.DISPUTED else StreamState.BLOCKED
            FailureKind.DEAD -> if (playedRecently) StreamState.DISPUTED else StreamState.DEAD
            // A licence refused after a recent play is likely an expired token (a refresh brings a new one).
            FailureKind.DRM -> if (playedRecently) StreamState.DISPUTED else StreamState.DEAD
            FailureKind.PARSE -> StreamState.DEAD
        }
    }

    /** How long after its last failure a stream is skipped: 5 min x 2^(n-1) capped at 24 h, or 24 h. */
    fun cooldownMs(record: StreamHealthRecord): Long = when (state(record)) {
        StreamState.ALIVE -> 0L
        StreamState.BLOCKED, StreamState.DEAD -> MAX_COOLDOWN_MS
        StreamState.FLAKY, StreamState.UNREACHABLE, StreamState.DISPUTED -> {
            val doublings = (record.failures - 1).coerceIn(0, 20)
            (BASE_COOLDOWN_MS shl doublings).coerceAtMost(MAX_COOLDOWN_MS)
        }
    }

    fun isCooling(record: StreamHealthRecord?, nowMs: Long): Boolean {
        if (record == null) return false
        val failedMs = record.lastFailedMs ?: return false
        val sinceFailure = nowMs - failedMs
        return sinceFailure >= 0 && sinceFailure < cooldownMs(record)
    }

    /**
     * The order to try [streams] in: the last stream that played (when it is not cooling) first,
     * then the rest in source order, cooling streams last (still tried: every stream cooling must
     * not make a channel untunable when the user asks for it).
     */
    fun order(streams: List<LiveStream>, records: Map<String, StreamHealthRecord>, nowMs: Long): List<LiveStream> {
        val (ready, cooling) = streams.partition { !isCooling(records[key(it)], nowMs) }
        val lastGood = ready
            .filter { records[key(it)]?.let { record -> state(record) == StreamState.ALIVE } == true }
            .maxByOrNull { records[key(it)]?.lastPlayedMs ?: Long.MIN_VALUE }
        return listOfNotNull(lastGood) + ready.filter { it !== lastGood } + cooling
    }

    /** Every stream cooling: the channel shows as unavailable and zapping skips it (D16). */
    fun allCooling(streams: List<LiveStream>, records: Map<String, StreamHealthRecord>, nowMs: Long): Boolean =
        streams.isNotEmpty() && streams.all { isCooling(records[key(it)], nowMs) }
}
