package com.nuvio.tv.livetv.player

import android.content.Context
import android.os.SystemClock
import com.nuvio.tv.core.network.NetworkTransportReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device reaches the internet, asked when a live stream fails the way an outage
 * makes it fail (Spec 90 L1, D4/D5): a [isNetworkClass] failure is only held against the stream
 * when the answer is yes. No network link (`NetworkTransportReader.hasInternet`, Spec 70) is a no
 * without a request; else one HTTPS `generate_204` probe, Google first, Cloudflare when Google does
 * not answer 204. A yes is reused for [ONLINE_REUSE_MS]; a no is never reused (the player then
 * waits and asks again on [retryDelayMs]), except by callers that queued behind the probe that
 * answered it: one probe answers everyone waiting. Android's VALIDATED flag is not read: it lags an
 * outage behind a live LAN link (Spec 90 plan red team M1-M3).
 */
@Singleton
class LiveNetwork internal constructor(
    private val hasLink: () -> Boolean,
    private val probe: suspend () -> Boolean,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        hasLink = { NetworkTransportReader.hasInternet(context) },
        probe = ::probeHosts,
        nowMs = SystemClock::elapsedRealtime
    )

    private val mutex = Mutex()

    /** When the last probe answered yes; null after a no or a lost link. */
    private var onlineAtMs: Long? = null

    /** Probes answered so far, and the last answer: a caller that queued behind one takes it. */
    private var answers = 0L
    private var lastAnswer = false

    suspend fun online(): Boolean {
        if (!hasLink()) {
            onlineAtMs = null
            return false
        }
        val seen = answers
        return mutex.withLock {
            if (answers != seen) return@withLock lastAnswer
            val reusable = onlineAtMs?.let { nowMs() - it in 0 until ONLINE_REUSE_MS } == true
            // A link lost while the probe ran makes its yes a no, for this caller and the queued ones.
            reusable || (probe() && hasLink()).also {
                onlineAtMs = if (it) nowMs() else null
                answers++
                lastAnswer = it
            }
        }
    }

    /** What the player does with an answer about an outage-shaped failure (Spec 90 L1). */
    internal enum class Outcome {
        /** Online: the failure is the stream's; record it / end the channel. */
        PROCEED,

        /** Offline, the session is still the one asking, and no stream reached READY since: wait the outage out. */
        WAIT,

        /** Offline, but the user moved on or a stream reached READY since the question: neither record nor wait. */
        NONE
    }

    companion object {
        /** D5: a yes holds this long, so a flapping stream cannot probe more than once a minute. */
        const val ONLINE_REUSE_MS = 60_000L

        /** D5: the waits between probes while offline: 5, 10, 20, 40 s, then every 60 s. */
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)

        internal fun outcome(online: Boolean, sameSession: Boolean, playing: Boolean): Outcome = when {
            online -> Outcome.PROCEED
            sameSession && !playing -> Outcome.WAIT
            else -> Outcome.NONE
        }

        fun retryDelayMs(attempt: Int): Long = RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]

        /**
         * A failure an outage produces: no connection or answer (NETWORK without an HTTP status: a
         * status means a server answered) or a stall. Anything else is the stream's own.
         */
        fun isNetworkClass(failure: LiveFailure): Boolean =
            (failure.kind == FailureKind.NETWORK && failure.httpCode == null) || failure.kind == FailureKind.STALL

        private const val PROBE_TIMEOUT_MS = 3_000L
        private val PROBE_URLS = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )

        // Its own plain client: none of the playback client's interceptors (its trust-all TLS retry
        // would let an intercepting portal pass) and not its connection pool.
        private val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

        private suspend fun probeHosts(): Boolean = withContext(Dispatchers.IO) {
            PROBE_URLS.any { url ->
                try {
                    probeClient.newCall(Request.Builder().url(url).build()).execute().use { it.code == 204 }
                } catch (_: IOException) {
                    false
                }
            }
        }
    }
}
