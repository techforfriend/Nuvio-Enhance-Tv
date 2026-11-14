package com.nuvio.tv.core.player

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val subtitleAutoSyncHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        // Sidecar + auto-sync both use this client; keep timeouts generous for flaky hosts.
        .connectTimeout(12_000, TimeUnit.MILLISECONDS)
        .readTimeout(15_000, TimeUnit.MILLISECONDS)
        .callTimeout(25_000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor(subtitleRedirectCredentialInterceptor())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/**
 * Network interceptor: OkHttp itself drops only Authorization on a cross-host redirect; a hop
 * that leaves the first request's site loses Cookie/Proxy-Authorization too (Spec 68 X1).
 */
internal fun subtitleRedirectCredentialInterceptor(
    registrableDomain: (String) -> String? = ::pslRegistrableDomain
): Interceptor = Interceptor { chain ->
    val request = chain.request()
    val firstUrl = chain.call().request().url.toString()
    if (shouldSendStreamCredentials(request.url.toString(), firstUrl, registrableDomain)) {
        chain.proceed(request)
    } else {
        chain.proceed(request.newBuilder().apply {
            SUBTITLE_CREDENTIAL_HEADERS.forEach { removeHeader(it) }
        }.build())
    }
}

private const val SUBTITLE_DOWNLOAD_MAX_ATTEMPTS = 3
private const val SUBTITLE_DOWNLOAD_RETRY_DELAY_MS = 350L

private const val AUTO_SYNC_REACTION_COMPENSATION_MS = 300L

internal fun PlayerRuntimeController.showSubtitleTimingDialog() {
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = true,
            showSubtitleOverlay = false,
            showSubtitleDelayOverlay = false,
            showMoreDialog = false,
            showSpeedPanel = false,
            showChaptersPanel = false,
            showAudioOverlay = false,
            showControls = false,
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null
        )
    }
    maybeLoadSubtitleAutoSyncCues(force = false)
}

internal fun PlayerRuntimeController.dismissSubtitleTimingDialog() {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    _uiState.update { it.copy(showSubtitleTimingDialog = false, subtitleAutoSyncStatus = null) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.captureSubtitleAutoSyncTime() {
    val capturePositionMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    _uiState.update {
        it.copy(
            subtitleAutoSyncCapturedVideoMs = capturePositionMs,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null
        )
    }
}

internal fun PlayerRuntimeController.applySubtitleAutoSyncCue(cueStartTimeMs: Long) {
    val capturePositionMs =
        _uiState.value.subtitleAutoSyncCapturedVideoMs ?: currentPlaybackPositionMs() ?: return
    val newDelayMs = (capturePositionMs - cueStartTimeMs - AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    _uiState.update {
        it.copy(
            subtitleDelayMs = newDelayMs,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = true,
            showControls = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(newDelayMs)
            ),
            subtitleAutoSyncError = null
        )
    }
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()
    refreshActiveSubtitleTrackAfterTimingChange()
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.reloadSubtitleAutoSyncCues() {
    maybeLoadSubtitleAutoSyncCues(force = true)
}

internal fun PlayerRuntimeController.resetSubtitleAutoSyncState(clearLoadedTrack: Boolean = true) {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLoadedTrackKey = if (clearLoadedTrack) null else it.subtitleAutoSyncLoadedTrackKey
        )
    }
}

private fun PlayerRuntimeController.maybeLoadSubtitleAutoSyncCues(force: Boolean) {
    val selectedSubtitle = _uiState.value.selectedAddonSubtitle
    if (selectedSubtitle == null) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncCues = emptyList(),
                subtitleAutoSyncCapturedVideoMs = null,
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_select_addon_track),
                subtitleAutoSyncLoadedTrackKey = null
            )
        }
        return
    }

    val selectedTrackKey = selectedSubtitle.autoSyncTrackKey()
    val state = _uiState.value
    if (!force &&
        state.subtitleAutoSyncLoadedTrackKey == selectedTrackKey &&
        state.subtitleAutoSyncCues.isNotEmpty()
    ) {
        return
    }

    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = scope.launch {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = true,
                subtitleAutoSyncError = null,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncCues = if (force) emptyList() else it.subtitleAutoSyncCues,
                subtitleAutoSyncCapturedVideoMs = if (force) null else it.subtitleAutoSyncCapturedVideoMs,
                subtitleAutoSyncLoadedTrackKey = selectedTrackKey
            )
        }

        try {
            val rawSubtitleBody = downloadSubtitleBody(
                selectedSubtitle.url,
                selectedSubtitle.lang,
                selectedSubtitle.headers
            )
            val parsedCues = PlayerSubtitleCueParser.parseFromText(
                rawText = rawSubtitleBody,
                sourceUrl = selectedSubtitle.url
            )
                .filter { cue -> cue.text.isNotBlank() }

            if (_uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedTrackKey) {
                return@launch
            }

            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncCues = parsedCues,
                    subtitleAutoSyncError = if (parsedCues.isEmpty()) {
                        context.getString(com.nuvio.tv.R.string.subtitle_timing_file_no_lines)
                    } else {
                        null
                    },
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedTrackKey) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncCues = emptyList(),
                    subtitleAutoSyncError = e.message ?: context.getString(com.nuvio.tv.R.string.subtitle_timing_load_lines_failed),
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        }
    }
}

/**
 * Downloads a remote subtitle body for sidecar rendering / auto-sync.
 *
 * Stream headers ride along scoped by domain (see [buildSubtitleRequestHeaders]): the same
 * domain keeps debrid/CDN auth, foreign hosts get Referer/Origin only, and explicit
 * per-subtitle headers (addon-provided) win last. Forwarding debrid auth wholesale to
 * OpenSubtitles-style hosts is a common cause of intermittent HTTP 4xx / empty bodies.
 */
internal suspend fun PlayerRuntimeController.downloadSubtitleBody(
    url: String,
    languageHint: String? = null,
    headers: Map<String, String>? = null
): String =
    withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(SUBTITLE_DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext executeSubtitleDownload(url, languageHint, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < SUBTITLE_DOWNLOAD_MAX_ATTEMPTS - 1) {
                    delay(SUBTITLE_DOWNLOAD_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Subtitle download failed")
    }

/** Hop-by-hop / transport headers that must never be replayed on a subtitle request. */
internal val SUBTITLE_HOP_BY_HOP_HEADERS = setOf("range", "host", "connection", "transfer-encoding")

/** Stream credential headers, sent to a subtitle host only when [shouldSendStreamCredentials]. */
internal val SUBTITLE_CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization")

/**
 * Registrable domain (public suffix + one label) of [host] from OkHttp's bundled public suffix
 * list; null for IP literals and bare public suffixes. On Android the list is read from OkHttp's
 * asset on first use (call off the main thread); a load failure throws IllegalStateException.
 */
internal fun pslRegistrableDomain(host: String): String? =
    HttpUrl.Builder().scheme("https").host(host).build().topPrivateDomain()

/**
 * True when both hosts are non-blank and are the same host or share one registrable domain by
 * the public suffix list (cdn.example.com vs example.com: same; a.co.uk vs b.co.uk: foreign).
 * Hosts must be canonical ([HttpUrl.host]: lowercase ASCII/punycode), so the identical-host check
 * is exact. IP literals match only exactly. Fails closed: an invalid host or a PSL load failure
 * counts as foreign.
 */
internal fun sharesRegistrableDomain(
    host1: String?,
    host2: String?,
    registrableDomain: (String) -> String? = ::pslRegistrableDomain
): Boolean {
    if (host1.isNullOrBlank() || host2.isNullOrBlank()) return false
    if (host1 == host2) return true
    val domain1 = registrableDomainOrNull(host1, registrableDomain) ?: return false
    val domain2 = registrableDomainOrNull(host2, registrableDomain) ?: return false
    return domain1 == domain2
}

/**
 * The one credential decision for subtitle fetches (Spec 68 X1): the stream's Authorization/
 * Cookie/Proxy-Authorization may go to [subtitleUrl] only when both are http(s) URLs on one
 * registrable domain and the subtitle does not downgrade an https stream to http. Both URLs go
 * through OkHttp's parser, the one the request itself uses, so a host that only looks equal
 * (Unicode case folding) cannot pass.
 */
internal fun shouldSendStreamCredentials(
    subtitleUrl: String?,
    streamUrl: String?,
    registrableDomain: (String) -> String? = ::pslRegistrableDomain
): Boolean {
    val subtitle = subtitleUrl?.toHttpUrlOrNull() ?: return false
    val stream = streamUrl?.toHttpUrlOrNull() ?: return false
    if (stream.isHttps && !subtitle.isHttps) return false
    return sharesRegistrableDomain(subtitle.host, stream.host, registrableDomain)
}

/**
 * The URL mpv may fetch itself after the OkHttp download failed, or null. mpv sends the stream's
 * global http-header-fields, so this needs [shouldSendStreamCredentials], and it returns OkHttp's
 * canonical form of the URL it judged: mpv/ffmpeg split a raw authority differently
 * (`https://host\@evil/` is host to OkHttp, evil to ffmpeg).
 */
internal fun mpvSubtitleFallbackUrl(
    subtitleUrl: String,
    streamUrl: String?,
    registrableDomain: (String) -> String? = ::pslRegistrableDomain
): String? {
    if (!shouldSendStreamCredentials(subtitleUrl, streamUrl, registrableDomain)) return null
    return subtitleUrl.toHttpUrlOrNull()?.toString()
}

private fun registrableDomainOrNull(host: String, registrableDomain: (String) -> String?): String? =
    try {
        registrableDomain(host)
    } catch (e: IllegalArgumentException) {
        null // not a valid host
    } catch (e: IllegalStateException) {
        null // public suffix list failed to load
    }

/**
 * Headers to send with a subtitle download, in application order:
 * 1. stream headers, hop-by-hop always dropped; unless [sendCredentials]
 *    ([shouldSendStreamCredentials]), Authorization/Cookie/Proxy-Authorization are dropped too
 *    (foreign auth rejection) while Referer/Origin/metadata survive (CDN 403 fix);
 * 2. explicit per-subtitle headers on top (addon-specified, override stream values);
 * 3. [defaultUserAgent]/[defaultAccept] only when no case-insensitive match is present.
 */
internal fun buildSubtitleRequestHeaders(
    streamHeaders: Map<String, String>,
    sendCredentials: Boolean,
    explicitHeaders: Map<String, String>?,
    defaultUserAgent: String,
    defaultAccept: String
): Map<String, String> {
    val excluded = if (sendCredentials) {
        SUBTITLE_HOP_BY_HOP_HEADERS
    } else {
        SUBTITLE_HOP_BY_HOP_HEADERS + SUBTITLE_CREDENTIAL_HEADERS
    }
    val headers = LinkedHashMap<String, String>()
    streamHeaders
        .filterKeys { it.lowercase() !in excluded }
        .forEach { (key, value) -> headers[key] = value }
    explicitHeaders
        ?.filterKeys { it.lowercase() !in SUBTITLE_HOP_BY_HOP_HEADERS }
        ?.forEach { (key, value) ->
            // Case-insensitive override: drop any case-variant stream key so the map-level
            // contract holds without relying on OkHttp's header() replacement semantics.
            headers.keys.removeAll { it.equals(key, ignoreCase = true) }
            headers[key] = value
        }
    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
        headers["User-Agent"] = defaultUserAgent
    }
    if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
        headers["Accept"] = defaultAccept
    }
    return headers
}

private fun PlayerRuntimeController.executeSubtitleDownload(
    url: String,
    languageHint: String? = null,
    customHeaders: Map<String, String>? = null
): String {
    // Stream UA rides along when present (even cross-domain); the browser UA is only a
    // fallback when the stream carries none.
    val streamUa = currentHeaders.entries.firstOrNull {
        it.key.equals("User-Agent", ignoreCase = true)
    }?.value
    val defaultUa = streamUa ?: (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

    val requestHeaders = buildSubtitleRequestHeaders(
        streamHeaders = currentHeaders,
        sendCredentials = shouldSendStreamCredentials(url, currentStreamUrl),
        explicitHeaders = customHeaders,
        defaultUserAgent = defaultUa,
        defaultAccept = "text/plain, text/vtt, application/x-subrip, */*"
    )
    val requestBuilder = requestHeaders.entries.fold(Request.Builder().url(url)) { builder, (key, value) ->
        builder.header(key, value)
    }

    subtitleAutoSyncHttpClient.newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_failed_http, response.code))
        }
        val bodyBytes = response.body.bytes()
        if (bodyBytes.isEmpty()) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        }
        val body = SubtitleCharsetDetector.decode(bodyBytes, languageHint = languageHint)
        if (body.isBlank()) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        }
        return body
    }
}

private fun Subtitle.autoSyncTrackKey(): String = "$id|$url"

internal fun formatAutoSyncTimestamp(positionMs: Long): String =
    com.nuvio.tv.ui.util.formatPlaybackClock(positionMs, padMinutesUnderHour = true)

internal fun formatAutoSyncDelay(delayMs: Int): String {
    val sign = if (delayMs >= 0) "+" else "-"
    val absMs = kotlin.math.abs(delayMs)
    val seconds = absMs / 1000
    val millis = absMs % 1000
    return "$sign${seconds}.${millis.toString().padStart(3, '0')}s"
}
