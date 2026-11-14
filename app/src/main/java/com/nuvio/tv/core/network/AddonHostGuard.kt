package com.nuvio.tv.core.network

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.network.tls.AddonCertificateSkipException
import com.nuvio.tv.core.network.tls.addonServerOf
import com.nuvio.tv.core.network.tls.isAddonCertificateFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Spec 70 R3 (D7): per-host protection for addon calls, in memory for the process. A 429 (or a 503 with
 * Retry-After) pauses the host; [FAILURES_TO_OPEN] failures within [FAILURE_WINDOW_MS] open a breaker for
 * [OPEN_COOLDOWN_MS], then one probe decides. Separate from `AddonHealthModel`'s breaker, which only drives
 * the health chips. Thread-safe: OkHttp calls it from its dispatcher threads.
 *
 * Spec 68 X7 P6: also keeps, per `host:port`, whether the last real outcome was a certificate
 * failure ([certificateFailures]); one process-wide instance (NetworkModule) so Trust can reset it.
 */
internal class AddonHostGuard(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val wallNowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Admission { ALLOW, PROBE, SKIP_RATE_LIMITED, SKIP_BREAKER_OPEN, SKIP_CERTIFICATE }

    private class HostState {
        var pauseUntilMs = 0L
        val failureAtMs = ArrayDeque<Long>()

        /** 0 = closed. */
        var openUntilMs = 0L
        var probeInFlight = false
    }

    private val hosts = HashMap<String, HostState>()

    private val certificateFailed = MutableStateFlow<Set<String>>(emptySet())

    /**
     * P6: the `host:port` keys whose last real outcome was a certificate failure. Any answer or other
     * network failure clears a key, as do Trust and a probe that now verifies; skips, cache hits and
     * cancelled calls leave it.
     */
    val certificateFailures: StateFlow<Set<String>> = certificateFailed.asStateFlow()

    @Synchronized
    fun admit(host: String): Admission {
        val state = hosts.getOrPut(host, ::HostState)
        val now = nowMs()
        return when {
            now < state.pauseUntilMs -> Admission.SKIP_RATE_LIMITED
            state.openUntilMs == 0L -> Admission.ALLOW
            now < state.openUntilMs || state.probeInFlight ->
                if (host in certificateFailed.value) Admission.SKIP_CERTIFICATE else Admission.SKIP_BREAKER_OPEN
            else -> {
                state.probeInFlight = true
                Admission.PROBE
            }
        }
    }

    /** The host answered with [code]. */
    @Synchronized
    fun onAnswer(host: String, code: Int, retryAfter: String?, probe: Boolean) {
        val state = hosts.getOrPut(host, ::HostState)
        if (probe) state.probeInFlight = false
        setCertificateFailure(host, false)
        val now = nowMs()
        if (shouldApplyBackendCooldown(code, retryAfter)) {
            val pauseMs = retryAfterDelayMillis(retryAfter, wallNowMs(), fallbackDelayMs = PAUSE_FALLBACK_MS)
            state.pauseUntilMs = maxOf(state.pauseUntilMs, now + pauseMs)
            Log.w(TAG, "ADDON_HOST paused host=$host code=$code pauseMs=$pauseMs")
            // The host answered: alive for the breaker.
            onSuccess(host, state)
        } else if (code >= 500) {
            onFailure(host, state, now, probe)
        } else {
            onSuccess(host, state)
        }
    }

    /** The call failed on the network, or ran out its own callTimeout. */
    @Synchronized
    fun onNetworkFailure(host: String, probe: Boolean) {
        val state = hosts.getOrPut(host, ::HostState)
        if (probe) state.probeInFlight = false
        setCertificateFailure(host, false)
        onFailure(host, state, nowMs(), probe)
    }

    /** The handshake failed on the certificate (P6): a failure for the breaker, and the host is flagged. */
    @Synchronized
    fun onCertificateFailure(host: String, probe: Boolean) {
        val state = hosts.getOrPut(host, ::HostState)
        if (probe) state.probeInFlight = false
        setCertificateFailure(host, true)
        onFailure(host, state, nowMs(), probe)
    }

    /**
     * The user trusted this host's certificate: closes its breaker and clears its flag so the
     * re-fetch goes out now. A 429 pause stays: the server asked for it.
     */
    @Synchronized
    fun onTrusted(host: String) {
        hosts[host]?.let { state ->
            state.failureAtMs.clear()
            state.openUntilMs = 0L
            state.probeInFlight = false
        }
        setCertificateFailure(host, false)
    }

    /** A certificate probe found the host verifies now (P7). */
    @Synchronized
    fun clearCertificateFailure(host: String) = setCertificateFailure(host, false)

    private fun setCertificateFailure(host: String, failed: Boolean) {
        val current = certificateFailed.value
        if ((host in current) != failed) certificateFailed.value = if (failed) current + host else current - host
    }

    /** No outcome (its caller cancelled it, or the cache answered): only frees a probe slot. */
    @Synchronized
    fun onNoOutcome(host: String, probe: Boolean) {
        if (probe) hosts[host]?.probeInFlight = false
    }

    private fun onSuccess(host: String, state: HostState) {
        state.failureAtMs.clear()
        if (state.openUntilMs != 0L) {
            state.openUntilMs = 0L
            Log.i(TAG, "ADDON_HOST breaker closed host=$host")
        }
    }

    private fun onFailure(host: String, state: HostState, now: Long, probe: Boolean) {
        if (probe) {
            // A call admitted before the breaker opened may have closed it meanwhile: that answer wins.
            if (state.openUntilMs == 0L) return
            state.openUntilMs = now + OPEN_COOLDOWN_MS
            Log.w(TAG, "ADDON_HOST probe failed, breaker re-opened host=$host")
            return
        }
        state.failureAtMs.addLast(now)
        while (state.failureAtMs.isNotEmpty() && now - state.failureAtMs.first() > FAILURE_WINDOW_MS) {
            state.failureAtMs.removeFirst()
        }
        if (state.openUntilMs == 0L && state.failureAtMs.size >= FAILURES_TO_OPEN) {
            state.openUntilMs = now + OPEN_COOLDOWN_MS
            state.failureAtMs.clear()
            Log.w(TAG, "ADDON_HOST breaker opened host=$host cooldownMs=$OPEN_COOLDOWN_MS")
        }
    }

    companion object {
        private const val TAG = "NuvioAddonHost"
        const val FAILURES_TO_OPEN = 3
        const val FAILURE_WINDOW_MS = 60_000L
        const val OPEN_COOLDOWN_MS = 30_000L

        /** A 429 without Retry-After. */
        const val PAUSE_FALLBACK_MS = 10_000L
    }
}

/**
 * The guard's per-server key, `host:port` with OkHttp's `HttpUrl` host and port: self-hosted addons often
 * share one LAN address on different ports. One home for the interceptor, the probe and Trust (Spec 68).
 */
internal fun addonGuardKey(host: String, port: Int): String = "$host:$port"

/** [addonGuardKey] of an addon URL, the key its calls are flagged under (the Addons row reads it); null when unparseable. */
internal fun addonGuardKeyOf(addonUrl: String): String? =
    addonServerOf(addonUrl)?.let { addonGuardKey(it.host, it.port) }

/**
 * Spec 70 R3: the first application interceptor on the addon (`@Api`) client. A skipped request never reaches
 * the network: it is re-issued only-if-cached (a fresh cached answer still serves), else it ends in a synthetic
 * 429 / 503, or for a host whose last failure was a certificate (Spec 68 P6) in [AddonCertificateSkipException]. Logs name the host only (addon paths carry keys).
 */
internal class AddonHostGuardInterceptor(
    private val guard: AddonHostGuard,
    /** False while the device has no network: failures then are the TV's, not the host's (red team 0da94ac6). */
    private val deviceOnline: () -> Boolean = { true },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = addonGuardKey(request.url.host, request.url.port)
        val admission = guard.admit(host)
        if (admission == AddonHostGuard.Admission.SKIP_RATE_LIMITED ||
            admission == AddonHostGuard.Admission.SKIP_BREAKER_OPEN ||
            admission == AddonHostGuard.Admission.SKIP_CERTIFICATE
        ) {
            return cachedOrSkipped(chain, admission)
        }
        val probe = admission == AddonHostGuard.Admission.PROBE
        val startedAtNs = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            if (t.isAddonCertificateFailure()) {
                // A certificate came back: the host was reached, whatever the online check says.
                guard.onCertificateFailure(host, probe)
            } else if (t is IOException &&
                (!chain.call().isCanceled() || ranOutCallTimeout(chain, startedAtNs)) &&
                deviceOnlineOrAssumed()
            ) {
                guard.onNetworkFailure(host, probe)
            } else {
                guard.onNoOutcome(host, probe)
            }
            throw t
        }
        if (response.networkResponse == null) {
            guard.onNoOutcome(host, probe) // served from cache: says nothing about the host
        } else {
            guard.onAnswer(host, response.code, response.header("Retry-After"), probe)
        }
        return response
    }

    /**
     * Never throws: a throw here would skip both guard calls (a probe slot leaks and wedges the host) and
     * replace the caller's IOException (red team b5b191eb). A failed read counts as online, so the failure
     * still counts, as before the offline check existed.
     */
    private fun deviceOnlineOrAssumed(): Boolean = runCatching { deviceOnline() }.getOrDefault(true)

    /**
     * OkHttp's callTimeout cancels the call when it fires, so a cancelled call that lasted the whole timeout
     * timed out; one cancelled sooner was dropped by its caller (navigation, the repositories' own timers).
     */
    private fun ranOutCallTimeout(chain: Interceptor.Chain, startedAtNs: Long): Boolean {
        val timeoutNs = chain.call().timeout().timeoutNanos()
        // The timeout started a little before this interceptor ran; the slack covers that, and keeps the
        // threshold (29 s) above the callers' own timers (streams 25 s, meta 28 s, under a 30 s callTimeout).
        return timeoutNs > 0L && System.nanoTime() - startedAtNs >= timeoutNs - CALL_TIMEOUT_SLACK_NS
    }

    private fun cachedOrSkipped(chain: Interceptor.Chain, admission: AddonHostGuard.Admission): Response {
        val request = chain.request()
        val cached = chain.proceed(
            request.newBuilder().cacheControl(CacheControl.Builder().onlyIfCached().build()).build()
        )
        // OkHttp answers an unsatisfiable only-if-cached request with a 504 that has no cache response.
        if (cached.cacheResponse != null) return cached
        cached.close()
        // Spec 68 P6: the caller sees the certificate error the host last gave, not a generic 503.
        if (admission == AddonHostGuard.Admission.SKIP_CERTIFICATE) throw AddonCertificateSkipException()
        val rateLimited = admission == AddonHostGuard.Admission.SKIP_RATE_LIMITED
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (rateLimited) 429 else 503)
            .message(if (rateLimited) SKIPPED_RATE_LIMITED else SKIPPED_BREAKER_OPEN)
            .body("".toResponseBody(null))
            .build()
    }

    companion object {
        private const val CALL_TIMEOUT_SLACK_NS = 1_000_000_000L
        const val SKIPPED_RATE_LIMITED = "Addon server asked to slow down; skipped for now"
        const val SKIPPED_BREAKER_OPEN = "Addon server is failing; skipped for now"
    }
}
