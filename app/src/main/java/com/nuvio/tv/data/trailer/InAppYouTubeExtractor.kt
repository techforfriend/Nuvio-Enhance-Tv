package com.nuvio.tv.data.trailer

import android.net.Uri
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.executeCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.core.util.booleanField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val TAG = "InAppYouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 30_000L
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

internal data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: JsonObject,
    val priority: Int
)

private data class WatchConfig(
    val apiKey: String?,
    val visitorData: String?
)

internal data class StreamCandidate(
    val client: String,
    val priority: Int,
    val url: String,
    val score: Double,
    val hasN: Boolean,
    val itag: String,
    val height: Int,
    val fps: Int,
    val ext: String,
    // Only meaningful for audio candidates: false means this format is an
    // alternate-language dub track, not the video's original/default audio.
    // Always true for video/progressive candidates, so it never affects them.
    val isDefaultAudioTrack: Boolean = true
)

private data class ManifestBestVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long
)

private data class ManifestCandidate(
    val client: String,
    val priority: Int,
    val manifestUrl: String,
    val selectedVariantUrl: String,
    val height: Int,
    val bandwidth: Long
)

private val DEFAULT_HEADERS = mapOf(
    "accept-language" to "en-US,en;q=0.9",
    "user-agent" to DEFAULT_USER_AGENT
)

internal val CLIENTS = listOf(
    YouTubeClient(
        key = "visionos",
        id = "101",
        version = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        context = buildJsonObject {
            put("clientName", "VISIONOS")
            put("clientVersion", "1.02")
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice17,1")
            put("osName", "visionOS")
            put("osVersion", "26.5.23O471")
            put("hl", "en")
            put("gl", "US")
        },
        priority = 0
    ),
    YouTubeClient(
        key = "android",
        id = "3",
        version = "20.10.35",
        userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
        context = buildJsonObject {
            put("clientName", "ANDROID")
            put("clientVersion", "20.10.35")
            put("osName", "Android")
            put("osVersion", "14")
            put("platform", "MOBILE")
            put("androidSdkVersion", 34)
            put("hl", "en")
            put("gl", "US")
        },
        priority = 1
    ),
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "20.10.1",
        userAgent = "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
        context = buildJsonObject {
            put("clientName", "IOS")
            put("clientVersion", "20.10.1")
            put("deviceModel", "iPhone16,2")
            put("osName", "iPhone")
            put("osVersion", "17.4.0.21E219")
            put("platform", "MOBILE")
            put("hl", "en")
            put("gl", "US")
        },
        priority = 2
    )
)

@Singleton
class InAppYouTubeExtractor @Inject constructor() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.dns.AppDns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // W2.1 parity — bounds the whole call incl. the DNS phase.
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // --- Cached watch config (api key + visitor data) ---
    private data class CachedConfig(
        val apiKey: String,
        val visitorData: String?,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private val cachedConfig = AtomicReference<CachedConfig?>(null)
    private val configMutex = Mutex()

    companion object {
        /** How long cached visitor_data stays valid before a proactive refresh. */
        private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

        /** YouTube's public web player key, used when the watch page fails or yields none. */
        private const val FALLBACK_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    }

    /**
     * Returns cached watch config, fetching from watch page only if:
     *  - No cached config exists yet (first call)
     *  - Cache is older than CONFIG_TTL_MS
     *  - [forceRefresh] is true (e.g. after LOGIN_REQUIRED)
     * When the page fetch fails: the stale config if any, else an uncached fallback-key config.
     */
    private suspend fun ensureWatchConfig(forceRefresh: Boolean = false): CachedConfig {
        // Fast path: return valid cache without locking
        if (!forceRefresh) {
            val current = cachedConfig.get()
            if (current != null && !isConfigStale(current)) {
                return current
            }
        }

        // Slow path: fetch new config under mutex (only one fetch at a time)
        return configMutex.withLock {
            // Double-check after acquiring lock
            if (!forceRefresh) {
                val current = cachedConfig.get()
                if (current != null && !isConfigStale(current)) {
                    return@withLock current
                }
            }

            Log.d(TAG, "Fetching watch page for visitor_data (forceRefresh=$forceRefresh)")
            val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en"
            val watchResponse = performRequest(
                url = watchUrl,
                method = "GET",
                headers = DEFAULT_HEADERS
            )
            if (!watchResponse.ok) {
                // If we have a stale config, prefer it over failing
                val stale = cachedConfig.get()
                if (stale != null) {
                    Log.w(TAG, "Watch page failed (${watchResponse.status}), using stale config")
                    return@withLock stale
                }
                // The watch page can be replaced by a consent or bot-check page (a 429 from
                // google.com/sorry) while the player API still answers, so continue with the
                // fallback key (NM `221ef283c`). Not cached: the next extraction tries the
                // watch page again.
                Log.w(TAG, "Watch page failed (${watchResponse.status}), using the fallback key")
                return@withLock CachedConfig(apiKey = FALLBACK_API_KEY, visitorData = null)
            }

            val parsed = getWatchConfig(watchResponse.body)
            val apiKey = parsed.apiKey ?: FALLBACK_API_KEY
            val newConfig = CachedConfig(
                apiKey = apiKey,
                visitorData = parsed.visitorData
            )
            cachedConfig.set(newConfig)
            Log.d(TAG, "Watch config cached (visitor=${!parsed.visitorData.isNullOrBlank()})")
            newConfig
        }
    }

    private fun isConfigStale(config: CachedConfig): Boolean {
        return System.currentTimeMillis() - config.fetchedAt > CONFIG_TTL_MS
    }

    /** Invalidate cached config so next extraction re-fetches watch page. */
    fun invalidateConfig() {
        cachedConfig.set(null)
        Log.d(TAG, "Watch config invalidated")
    }

    /** [videoId] is an 11-char YouTube id; parse URLs with [youtubeVideoId] first. */
    suspend fun extractPlaybackSource(videoId: String): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        Log.d(TAG, "Starting Kotlin extraction for videoId=$videoId")
        // withTimeoutOrNull, not withTimeout: a timed-out attempt is a failed attempt (null),
        // whereas TimeoutCancellationException would escape the CancellationException rethrow
        // to callers that were never cancelled.
        var source: TrailerPlaybackSource? = null
        try {
            source = withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                extractPlaybackSourceInternal(videoId, forceRefreshConfig = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (error: Exception) {
            Log.w(TAG, "Kotlin extractor failed for videoId=$videoId: ${error.message}")
        }

        // Retry with fresh config if first attempt returned nothing
        if (source == null) {
            Log.d(TAG, "First attempt failed, retrying with fresh watch config...")
            try {
                source = withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                    extractPlaybackSourceInternal(videoId, forceRefreshConfig = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.w(TAG, "Kotlin extractor retry failed for videoId=$videoId: ${error.message}")
            }
        }

        if (source == null) {
            Log.w(TAG, "Kotlin extraction returned no playable source for videoId=$videoId")
        } else {
            Log.d(
                TAG,
                "Kotlin extraction success for videoId=$videoId " +
                    "(video=${summarizeUrl(source.videoUrl)}, audioPresent=${!source.audioUrl.isNullOrBlank()})"
            )
        }

        source
    }

    private suspend fun extractPlaybackSourceInternal(
        videoId: String,
        forceRefreshConfig: Boolean
    ): TrailerPlaybackSource? {

        // Use cached config instead of fetching watch page every time
        val config = ensureWatchConfig(forceRefresh = forceRefreshConfig)
        Log.d(TAG, "Using config: apiKey=${config.apiKey.take(10)}... visitor=${!config.visitorData.isNullOrBlank()}")

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        var loginRequiredCount = 0

        for (client in CLIENTS) {
            kotlinx.coroutines.yield()
            try {
                val playerResponse = fetchPlayerResponse(
                    apiKey = config.apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = config.visitorData
                )

                // Check for LOGIN_REQUIRED which means visitor_data is stale
                val playabilityStatus = playerResponse.mapValue("playabilityStatus")
                val status = playabilityStatus?.stringValue("status")
                if (status == "LOGIN_REQUIRED") {
                    loginRequiredCount++
                    Log.w(TAG, "Client ${client.key}: LOGIN_REQUIRED (visitor may be stale)")
                    continue
                }
                if (status != null && status != "OK") {
                    continue
                }

                val streamingData = playerResponse.mapValue("streamingData") ?: continue
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }

                for (format in streamingData.listMapValue("formats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (format.numberValue("height")
                        ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                        ?: 0.0).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate")
                        ?: format.numberValue("averageBitrate")
                        ?: 0.0

                    progressive += StreamCandidate(
                        client = client.key,
                        priority = client.priority,
                        url = url,
                        score = videoScore(height, fps, bitrate),
                        hasN = hasNParam(url),
                        itag = format.stringValue("itag").orEmpty(),
                        height = height,
                        fps = fps,
                        ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    )
                }

                for (format in streamingData.listMapValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    val hasVideo = mimeType.contains("video/")
                    val hasAudio = mimeType.contains("audio/") || mimeType.startsWith("audio/")

                    if (hasVideo) {
                        val height = (format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                            ?: 0.0).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0

                        adaptiveVideo += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = videoScore(height, fps, bitrate),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = height,
                            fps = fps,
                            ext = if (mimeType.contains("webm")) "webm" else "mp4"
                        )
                    } else if (hasAudio) {
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0
                        val asr = format.numberValue("audioSampleRate") ?: 0.0
                        // Multi-language uploads (common for major-studio trailers)
                        // expose each dub as a separate adaptiveFormats entry with an
                        // audioTrack.audioIsDefault flag. Formats with no audioTrack
                        // are the only audio for that video, so treat them as default.
                        val isDefaultAudioTrack = format.mapValue("audioTrack")
                            ?.booleanValue("audioIsDefault") ?: true

                        adaptiveAudio += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = audioScore(bitrate, asr),
                            hasN = hasNParam(url),
                            itag = format.stringValue("itag").orEmpty(),
                            height = 0,
                            fps = 0,
                            ext = if (mimeType.contains("webm")) "webm" else "m4a",
                            isDefaultAudioTrack = isDefaultAudioTrack
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Client ${client.key} failed: ${error.message}")
                }
            }

        }

        // If all clients returned LOGIN_REQUIRED, invalidate config for next attempt
        if (loginRequiredCount == CLIENTS.size) {
            Log.w(TAG, "All ${CLIENTS.size} clients returned LOGIN_REQUIRED, invalidating config")
            invalidateConfig()
            return null
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return null
        }

        var bestManifest: ManifestCandidate? = null
        for ((clientKey, priority, manifestUrl) in manifestUrls) {
            try {
                val variant = parseHlsManifest(manifestUrl) ?: continue
                val candidate = ManifestCandidate(
                    client = clientKey,
                    priority = priority,
                    manifestUrl = manifestUrl,
                    selectedVariantUrl = variant.url,
                    height = variant.height,
                    bandwidth = variant.bandwidth
                )
                if (
                    bestManifest == null ||
                    candidate.height > bestManifest.height ||
                    (candidate.height == bestManifest.height && candidate.bandwidth > bestManifest.bandwidth)
                ) {
                    bestManifest = candidate
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Manifest parse failed: ${error.message}")
                }
            }
        }

        val bestProgressive = sortCandidates(progressive).firstOrNull()

        // Probe every client's top candidates in parallel and take the best reachable
        // pair. Token-free clients (visionos) win here even when gated clients score
        // higher, because gated URLs fail the reachability probe.
        kotlinx.coroutines.yield()
        val chosenVideo = probeBestPerClient(adaptiveVideo)
        val chosenAudio = if (chosenVideo != null) {
            probeBestPerClient(adaptiveAudio, preferClient = chosenVideo.client)
        } else null

        if (chosenVideo != null) {
            Log.d(TAG, "Trailer adaptive: ${chosenVideo.client}/${chosenVideo.height}p " +
                "audio=${chosenAudio?.client ?: "none"}")
            return TrailerPlaybackSource(videoUrl = chosenVideo.url, audioUrl = chosenAudio?.url)
        }

        // Adaptive failed (403) — fall back to HLS manifest (1080p, always works for COPPA/kids content)
        if (bestManifest != null) {
            return TrailerPlaybackSource(videoUrl = bestManifest.manifestUrl, audioUrl = null)
        }

        // No HLS available — try progressive (combined video+audio, usually low quality)
        val resolvedProgressive = bestProgressive?.url?.let { resolveReachableUrl(it) }
        if (resolvedProgressive != null) {
            return TrailerPlaybackSource(videoUrl = resolvedProgressive, audioUrl = null)
        }

        return null
    }

    private fun getWatchConfig(html: String): WatchConfig {
        val apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1)
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        return WatchConfig(apiKey = apiKey, visitorData = visitorData)
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?
    ): JsonObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"

        val headers = buildMap {
            putAll(DEFAULT_HEADERS)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
        }

        val payload = buildPlayerPayload(videoId, client)

        val response = performRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = payload.toString()
        )
        if (!response.ok) {
            throw IllegalStateException("player API ${client.key} failed (${response.status})")
        }

        // Opaque foreign JSON: element-tree navigation only (no strict models —
        // YouTube churns these fields; lenient access is load-bearing here).
        return runCatching {
            localStoreJson.parseToJsonElement(response.body).jsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
    }

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = performRequest(
            url = manifestUrl,
            method = "GET",
            headers = DEFAULT_HEADERS
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var bestVariant: ManifestBestVariant? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(i + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val resolution = attrs["RESOLUTION"].orEmpty()
            val (width, height) = parseResolution(resolution)
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L

            val candidate = ManifestBestVariant(
                url = absolutizeUrl(manifestUrl, nextLine),
                width = width,
                height = height,
                bandwidth = bandwidth
            )

            if (
                bestVariant == null ||
                candidate.height > bestVariant.height ||
                (candidate.height == bestVariant.height && candidate.bandwidth > bestVariant.bandwidth) ||
                (
                    candidate.height == bestVariant.height &&
                        candidate.bandwidth == bestVariant.bandwidth &&
                        candidate.width > bestVariant.width
                    )
            ) {
                bestVariant = candidate
            }
        }

        return bestVariant
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') {
                    inKey = false
                } else {
                    key.append(ch)
                }
                continue
            }

            if (ch == '"') {
                inQuote = !inQuote
                continue
            }

            if (ch == ',' && !inQuote) {
                val k = key.toString().trim()
                if (k.isNotEmpty()) {
                    out[k] = value.toString().trim()
                }
                key.clear()
                value.clear()
                inKey = true
                continue
            }

            value.append(ch)
        }

        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) {
            out[lastKey] = value.toString().trim()
        }

        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        if (parts.size != 2) return 0 to 0
        val width = parts[0].toIntOrNull() ?: 0
        val height = parts[1].toIntOrNull() ?: 0
        return width to height
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val match = QUALITY_LABEL_REGEX.find(label) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNParam(url: String): Boolean {
        return runCatching {
            !Uri.parse(url).getQueryParameter("n").isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun videoScore(height: Int, fps: Int, bitrate: Double): Double {
        return height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    }

    private fun audioScore(bitrate: Double, audioSampleRate: Double): Double {
        return bitrate * 1_000_000.0 + audioSampleRate
    }

    internal fun sortCandidates(items: List<StreamCandidate>): List<StreamCandidate> {
        return items.sortedWith(
            compareBy<StreamCandidate> { if (it.isDefaultAudioTrack) 0 else 1 }
                .thenByDescending { it.score }
                .thenBy { if (it.hasN) 1 else 0 }
                .thenBy { containerPreference(it.ext) }
                .thenBy { it.priority }
        )
    }

    private fun containerPreference(ext: String): Int {
        return when (ext.lowercase()) {
            "mp4", "m4a" -> 0
            "webm" -> 1
            else -> 2
        }
    }

    private suspend fun probeBestPerClient(
        candidates: List<StreamCandidate>,
        preferClient: String? = null,
        perClientDepth: Int = 2
    ): StreamCandidate? {
        val toProbe = candidates.groupBy { it.client }
            .flatMap { (_, list) -> sortCandidates(list).take(perClientDepth) }
        if (toProbe.isEmpty()) return null
        val results = java.util.Collections.synchronizedList(mutableListOf<StreamCandidate>())
        val probeScope = CoroutineScope(Dispatchers.IO)
        try {
            val jobs = toProbe.map { cand ->
                probeScope.launch {
                    val resolved = resolveReachableUrl(cand.url)
                    if (resolved != null) results.add(cand.copy(url = resolved))
                }
            }
            withTimeoutOrNull(2_500L) { jobs.forEach { it.join() } }
        } finally {
            probeScope.cancel()
        }
        val reachable = results.toList()
        if (reachable.isEmpty()) return null
        if (preferClient != null) {
            sortCandidates(reachable.filter { it.client == preferClient }).firstOrNull()?.let { return it }
        }
        return sortCandidates(reachable).firstOrNull()
    }

    /**
     * Probes CDN nodes for the given googlevideo URL and returns the first reachable one.
     * Returns null if no CDN node responds successfully (all return 403/timeout).
     */
    private suspend fun resolveReachableUrl(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val mnParam = uri.getQueryParameter("mn") ?: return url
        val servers = mnParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) return url

        val candidates = mutableListOf(url)
        for (server in servers) {
            val mviIndex = servers.indexOf(server)
            val altHost = uri.host?.replaceFirst(
                Regex("^rr\\d+---"),
                "rr${mviIndex + 1}---"
            )?.replaceFirst(
                Regex("sn-[a-z0-9]+-[a-z0-9]+"),
                server
            ) ?: continue
            if (altHost == uri.host) continue
            candidates += url.replace(uri.host!!, altHost)
        }

        if (candidates.size == 1) {
            // Single candidate — verify it's reachable
            return if (isUrlReachable(candidates[0])) candidates[0] else null
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                val reachable = isUrlReachable(candidate)
                if (reachable) result.complete(candidate)
            }
        }
        return try {
            withTimeoutOrNull(2_000L) { result.await() }
        } finally {
            probeScope.cancel()
        }
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.dns.AppDns)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private suspend fun isUrlReachable(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .headers(buildHeaders(DEFAULT_HEADERS))
                .build()
            // Cancellable: the probe scopes cancel the losers, which now aborts their sockets.
            probeClient.newCall(request).executeCancellable { response ->
                response.code == 200 || response.code == 206
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String {
        return runCatching {
            URL(URL(baseUrl), maybeRelative).toString()
        }.getOrElse { maybeRelative }
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            val host = parsed.host ?: "unknown-host"
            val path = parsed.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    /** Cancellable, so the extractor's timeout budget and caller cancellation abort the call. */
    private suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): RequestResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            else -> requestBuilder.get()
        }

        return httpClient.newCall(requestBuilder.build()).executeCancellable { response ->
            RequestResponse(
                ok = response.isSuccessful,
                status = response.code,
                body = response.body.string()
            )
        }
    }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", DEFAULT_USER_AGENT)
        }
        return headers.build()
    }
}

private data class RequestResponse(
    val ok: Boolean,
    val status: Int,
    val body: String
)


/** InnerTube player request body for [client]; pinned shape - a regression disables trailers. */
internal fun buildPlayerPayload(videoId: String, client: YouTubeClient): JsonObject = buildJsonObject {
    put("videoId", videoId)
    put("contentCheckOk", true)
    put("racyCheckOk", true)
    put("context", buildJsonObject { put("client", client.context) })
    put("playbackContext", buildJsonObject {
        put("contentPlaybackContext", buildJsonObject {
            put("html5Preference", "HTML5_PREF_WANTS")
        })
    })
}

internal fun JsonObject.mapValue(key: String): JsonObject? {
    return this[key] as? JsonObject
}

internal fun JsonObject.listMapValue(key: String): List<JsonObject> {
    val raw = this[key] as? JsonArray ?: return emptyList()
    return raw.mapNotNull { it as? JsonObject }
}

/**
 * Any primitive reads as its content (numbers included: itag 137 -> "137") -
 * Gson's `value.toString()` parity. Deliberately NOT JsonElementFields
 * .stringField (quoted-only): YouTube emits unquoted numeric fields we read
 * as strings; merging the two families would break the scraper.
 */
internal fun JsonObject.stringValue(key: String): String? {
    return (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

internal fun JsonObject.booleanValue(key: String): Boolean? = this.booleanField(key)

internal fun JsonObject.numberValue(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()
}
