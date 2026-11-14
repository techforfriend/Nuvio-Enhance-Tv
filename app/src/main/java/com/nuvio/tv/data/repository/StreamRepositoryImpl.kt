package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstream.CloudStreamRepository
import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import com.nuvio.tv.core.di.aioStreamDataUserAgent
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeAddonApiCall
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.stream.MissingUpstreamsTemplates
import com.nuvio.tv.core.stream.missingUpstreamsText
import com.nuvio.tv.data.mapper.gatewayErrorUpstream
import com.nuvio.tv.data.mapper.isGatewayStatusRow
import com.nuvio.tv.data.mapper.toListedStreams
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.isAioStreams
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.nuvio.tv.core.util.encodeUrlArg
import java.security.MessageDigest
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

/**
 * Per-addon deadline for a single stream fetch.
 *
 * Without this each addon inherits only the shared client's connect(30s) +
 * read(60s) budget, so one unresponsive source can hold its slot for ~90s
 * while every other addon has long since answered. 25s is deliberately
 * generous: a cold scrape on a large title legitimately takes ten-plus
 * seconds, and the self-hosted AIOStreams gateway (Spec 39 S3) answers a
 * title's first visit in ~15-16s because it waits for its slowest web source
 * (14s per-source timeout); cutting that off would drop every result at once.
 * Results already stream out as each addon lands, so a slow addon that beats
 * the deadline still contributes. Must stay under the @Api client's 30s
 * callTimeout (NetworkModule), which would otherwise fire first. The inner
 * request in [StreamRepositoryImpl.getStreamsFromAddon] gets the same deadline
 * (Spec 48 P1, upstream ysosrs 9f27af2b0): with the 20s addon default there,
 * the cut fell at 20s as an error and this 25s never applied.
 */
private const val ADDON_STREAM_FETCH_TIMEOUT_MS = 25_000L

/** Bounds the upstream names one gateway answer can report (untrusted input; AIOStreams sends one per upstream). */
private const val MAX_MISSING_UPSTREAMS = 20

/** Upstream names the all-failed message shows before "and N more" (Spec 42 D7). */
private const val MESSAGE_UPSTREAM_NAMES = 3

/**
 * One addon's stream answer: its sources, plus the upstreams a gateway reported as failed
 * (AIOStreams error rows, Spec 42 P6; empty for every other addon).
 */
internal data class AddonStreamAnswer(
    val streams: List<Stream>,
    val missingUpstreams: List<String> = emptyList()
)

class StreamRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val profileManager: ProfileManager,
    private val healthStore: AddonHealthStore,
    private val cloudStreamStreamSource: CloudStreamStreamSource
) : StreamRepository {

    /**
     * Fire-and-forget a passive health sample for one addon stream fetch, so it never
     * delays the channel close. Maps the ADDON_MS outcome vocabulary onto
     * HealthOutcome; cancelled and any unknown outcome are dropped (an external
     * cap-cancel is not the addon's fault).
     */
    private fun recordAddonHealth(baseUrl: String, outcome: String, latencyMs: Long) {
        val mapped = when (outcome) {
            "ok", "ok_inline" -> HealthOutcome.SUCCESS
            "empty" -> HealthOutcome.EMPTY
            "timeout", "error" -> HealthOutcome.FAILURE
            else -> return
        }
        healthStore.recordAddonOutcome(baseUrl, mapped, latencyMs)
    }

    private val streamSearchSessions = StreamSearchSessionCache()

    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED,
        /** A gateway answered with error rows only: every upstream it asked failed ([StreamAttemptFailure.upstreams]). */
        UPSTREAMS_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String,
        val upstreams: List<String> = emptyList()
    )

    private data class StreamSourceConfigurationSnapshot(
        val profileId: Int,
        val addons: List<Addon>
    )

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
        title: String?
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        val sourceConfiguration = captureSourceConfiguration()
        val requestKey = StreamSearchRequestKey(
            profileId = sourceConfiguration.profileId,
            type = type.lowercase(),
            videoId = videoId,
            season = season,
            episode = episode,
            sourceConfiguration = buildSourceConfigurationKey(sourceConfiguration.addons)
        )

        emitAll(
            streamSearchSessions.observe(
                key = requestKey,
                forceRefresh = forceRefresh
            ) {
                fetchStreamsFromAllSources(
                    type = type,
                    videoId = videoId,
                    addons = sourceConfiguration.addons,
                    season = season,
                    episode = episode,
                    title = title
                )
            }
        )
    }

    private suspend fun captureSourceConfiguration(): StreamSourceConfigurationSnapshot {
        while (true) {
            val profileId = profileManager.activeProfileId.value
            // Not first(): at cold start that reads the empty StateFlow seed and the
            // search ends with "no supported addon".
            val addons = awaitInstalledAddons(addonRepository.getInstalledAddons())

            if (profileManager.activeProfileId.value != profileId) continue

            return StreamSourceConfigurationSnapshot(
                profileId = profileId,
                addons = addons
            )
        }
    }

    private fun fetchStreamsFromAllSources(
        type: String,
        videoId: String,
        addons: List<Addon>,
        season: Int? = null,
        episode: Int? = null,
        title: String? = null
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)
        // P0 instrument: whole-scrape wall time. Grep anchor: SCRAPE_TOTAL.
        val scrapeT0 = android.os.SystemClock.elapsedRealtime()

        try {
            // Pair addons that support streams for this id with the type each is asked for
            val streamAddons = addons.mapNotNull { addon ->
                addon.streamRequestType(type, videoId)?.let { requestType -> addon to requestType }
            }

            val attemptedAddonNames = streamAddons.map { (addon, _) -> addon.displayName }
            // CloudStream counts as one extra source when an IMDb id is present and
            // at least one provider is installed, enabled and verified — or when the
            // video id names a CS3 title directly (Home section play, "cs3:...").
            val isDirectCs3VideoId =
                videoId.trim().startsWith(CloudStreamStreamSource.CS3_VIDEO_ID_PREFIX)
            val cs3Active = (isDirectCs3VideoId ||
                cloudStreamStreamSource.imdbIdFromVideoId(videoId) != null) &&
                CloudStreamRepository.runnablePlugins().isNotEmpty()
            val cs3ProviderNames = if (cs3Active) {
                if (isDirectCs3VideoId) {
                    val directProviderId = videoId.trim()
                        .removePrefix(CloudStreamStreamSource.CS3_VIDEO_ID_PREFIX)
                        .substringBefore('|').trim()
                    CloudStreamRepository.runnablePlugins()
                        .filter { it.metadata.id.value == directProviderId }
                        .map { it.metadata.name }
                } else {
                    CloudStreamRepository.runnablePlugins().map { it.metadata.name }
                }
            } else {
                emptyList()
            }
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs; the last job's finally
                // closes the channel. With no jobs nothing would ever
                // close it, so close up front and let the drain loop fall through.
                val totalJobs = streamAddons.size + if (cs3Active) 1 else 0
                val completedJobs = java.util.concurrent.atomic.AtomicInteger(0)
                if (totalJobs == 0) resultChannel.close()

                // Launch addon jobs
                streamAddons.forEach { (addon, requestType) ->
                    launch {
                        // P0 instrument: per-addon wall time, from job launch to
                        // completion, timeout, error or cancellation. Grep anchor:
                        // ADDON_MS. Logged in the finally so every exit path,
                        // including the rethrown cancellation, produces a line.
                        val addonT0 = android.os.SystemClock.elapsedRealtime()
                        var addonOutcome = "cancelled"
                        var addonStreamCount = 0
                        try {
                          withTimeout(ADDON_STREAM_FETCH_TIMEOUT_MS) {
                            val streamsResult = getStreamsFromAddon(
                                addon.baseUrl, requestType, videoId, addon.displayName, addon.logo,
                                userAgent = if (addon.isAioStreams()) aioStreamDataUserAgent() else null
                            )
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    // Upstream names are a gateway's facts (Spec 42 D6/D7): another
                                    // addon's streamData.addon or error rows name nothing.
                                    val gateway = addon.isAioStreams()
                                    val answer = streamsResult.data
                                    val missingUpstreams = if (gateway) answer.missingUpstreams else emptyList()
                                    if (answer.streams.isNotEmpty()) {
                                        val namedStreams = answer.streams.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                                .withUpstreamIf(gateway)
                                        }
                                        addonOutcome = "ok"
                                        addonStreamCount = namedStreams.size
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams,
                                                missingUpstreams = missingUpstreams
                                            )
                                        )
                                    } else {
                                        // Stream endpoint returned empty - try inline
                                        // streams from meta response as fallback.
                                        val inlineStreams = fetchInlineStreamsFromMeta(
                                            addon, requestType, videoId
                                        )
                                        if (inlineStreams.isNotEmpty()) {
                                            addonOutcome = "ok_inline"
                                            addonStreamCount = inlineStreams.size
                                            resultChannel.send(
                                                AddonStreams(
                                                    addonName = addon.displayName,
                                                    addonLogo = addon.logo,
                                                    streams = inlineStreams.map { it.withUpstreamIf(gateway) },
                                                    missingUpstreams = missingUpstreams
                                                )
                                            )
                                        } else if (missingUpstreams.isNotEmpty()) {
                                            addonOutcome = "empty"
                                            attemptedFailures += StreamAttemptFailure(
                                                addonName = addon.displayName,
                                                kind = StreamFailureKind.UPSTREAMS_FAILED,
                                                detail = "",
                                                upstreams = missingUpstreams
                                            )
                                        } else {
                                            addonOutcome = "empty"
                                            attemptedFailures += buildMissingStreamFailure(addon)
                                        }
                                    }
                                }
                                is NetworkResult.Error -> {
                                    addonOutcome = "error"
                                    attemptedFailures += buildAddonFailure(addon, streamsResult)
                                }
                                NetworkResult.Loading -> Unit
                            }
                          }
                        } catch (e: TimeoutCancellationException) {
                            // MUST precede the broad catch below: TimeoutCancellationException
                            // is a CancellationException, and rethrowing it would cancel the
                            // enclosing coroutineScope and every sibling addon job with it.
                            //
                            // Distinguish this addon's OWN 25s deadline from an EXTERNAL
                            // cancellation. The P2 prefetch cap wraps the collect in
                            // withTimeoutOrNull, whose TimeoutCancellationException propagates
                            // in here when the cap fires (e.g. at 3s) - which previously
                            // mislabelled a 3s cap-cancel as "exceeded <deadline>ms / timeout" and
                            // made a genuinely dead addon indistinguishable from one that was
                            // simply cut short. On a real own-timeout only the inner withTimeout
                            // scope expired, so this coroutine is still active; on an external
                            // cancel the coroutine itself is cancelled.
                            if (!isActive) {
                                addonOutcome = "cancelled"
                                throw e
                            }
                            addonOutcome = "timeout"
                            Log.w(TAG, "Addon ${addon.name} exceeded ${ADDON_STREAM_FETCH_TIMEOUT_MS}ms - abandoning")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            addonOutcome = "error"
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            val addonMs = android.os.SystemClock.elapsedRealtime() - addonT0
                            Log.i(
                                TAG,
                                "ADDON_MS name=${addon.displayName} " +
                                    "ms=$addonMs " +
                                    "streams=$addonStreamCount outcome=$addonOutcome"
                            )
                            recordAddonHealth(addon.baseUrl, addonOutcome, addonMs)
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // CloudStream job: same shape as an addon job — one AddonStreams
                // group per provider, same deadline, same channel close accounting.
                if (cs3Active) {
                    launch {
                        val cs3T0 = android.os.SystemClock.elapsedRealtime()
                        var cs3Outcome = "cancelled"
                        var cs3StreamCount = 0
                        try {
                            withTimeout(ADDON_STREAM_FETCH_TIMEOUT_MS) {
                                val groups = cloudStreamStreamSource.fetchStreams(
                                    videoId, season, episode, title, type
                                )
                                if (groups.isNotEmpty()) {
                                    cs3Outcome = "ok"
                                    cs3StreamCount = groups.sumOf { it.streams.size }
                                    groups.forEach { resultChannel.send(it) }
                                } else {
                                    cs3Outcome = "empty"
                                    attemptedFailures += StreamAttemptFailure(
                                        addonName = "CloudStream",
                                        kind = StreamFailureKind.MISSING,
                                        detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_no_streams_for_id)
                                    )
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            if (!isActive) {
                                cs3Outcome = "cancelled"
                                throw e
                            }
                            cs3Outcome = "timeout"
                            Log.w(TAG, "CloudStream exceeded ${ADDON_STREAM_FETCH_TIMEOUT_MS}ms - abandoning")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = "CloudStream",
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            cs3Outcome = "error"
                            Log.e(TAG, "CloudStream failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = "CloudStream",
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            val cs3Ms = android.os.SystemClock.elapsedRealtime() - cs3T0
                            Log.i(
                                TAG,
                                "ADDON_MS name=CloudStream " +
                                    "ms=$cs3Ms " +
                                    "streams=$cs3StreamCount outcome=$cs3Outcome"
                            )
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Emit results as they arrive, greedily draining whatever has
                // already queued so groups that landed together share one emission.
                // tryReceive never blocks, so a lone arrival is emitted immediately.
                for (result in resultChannel) {
                    val batch = mutableListOf(result)
                    while (true) {
                        val more = resultChannel.tryReceive().getOrNull() ?: break
                        batch += more
                    }
                    batch.forEach { addonResult ->
                        mergeResult(accumulatedResults, addonResult)
                    }
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest batch of ${batch.size}: ${batch.joinToString { "${it.addonName}(${it.streams.size})" }}")
                }
            }

            Log.i(
                TAG,
                "SCRAPE_TOTAL ms=${android.os.SystemClock.elapsedRealtime() - scrapeT0} " +
                    "groups=${accumulatedResults.size} " +
                    "streams=${accumulatedResults.sumOf { it.streams.size }}"
            )

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    type = type,
                    id = videoId,
                    attemptedAddonNames = attemptedAddonNames + cs3ProviderNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_fetch_failed)))
        }
    }

    private fun buildSourceConfigurationKey(addons: List<Addon>): String = buildString {
        append("addons:")
        addons.forEach { addon ->
            append("|addon:").append(addon)
        }
        // CloudStream registry changes (install/enable/remove/refresh) bump the
        // revision, so a stale cached session never hides a new provider.
        append("|cs3:").append(CloudStreamRepository.uiState.value.registryRevision)
    }.sha256()

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun mergeResult(
        accumulatedResults: MutableList<AddonStreams>,
        result: AddonStreams
    ) {
        val existingIndex = accumulatedResults.indexOfFirst { it.addonName == result.addonName }
        if (existingIndex >= 0) {
            val existing = accumulatedResults[existingIndex]
            accumulatedResults[existingIndex] = existing.copy(
                streams = mergeStreams(existing.streams, result.streams),
                missingUpstreams = (existing.missingUpstreams + result.missingUpstreams).distinct()
            )
        } else {
            accumulatedResults.add(result)
        }
    }

    private fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        incoming.forEach { stream ->
            val key = stream.dedupKey()
            val prior = streamsByKey[key]
            streamsByKey[key] = if (prior == null) stream else mergeDuplicateStreams(prior, stream)
        }
        return streamsByKey.values.toList()
    }

    /**
     * Main review F8: last-write-wins dropped the richer duplicate — the same
     * torrent arriving twice (e.g. via the meta-inline fallback) could lose an
     * earlier copy's behaviorHints (filename, videoSize, bingeGroup). Incoming
     * stays authoritative; fields it lacks are backfilled from the prior copy.
     */
    private fun mergeDuplicateStreams(prior: Stream, incoming: Stream): Stream {
        val mergedHints = when {
            incoming.behaviorHints == null -> prior.behaviorHints
            prior.behaviorHints == null -> incoming.behaviorHints
            else -> incoming.behaviorHints.copy(
                notWebReady = incoming.behaviorHints.notWebReady ?: prior.behaviorHints.notWebReady,
                bingeGroup = incoming.behaviorHints.bingeGroup ?: prior.behaviorHints.bingeGroup,
                countryWhitelist = incoming.behaviorHints.countryWhitelist ?: prior.behaviorHints.countryWhitelist,
                proxyHeaders = incoming.behaviorHints.proxyHeaders ?: prior.behaviorHints.proxyHeaders,
                videoHash = incoming.behaviorHints.videoHash ?: prior.behaviorHints.videoHash,
                videoSize = incoming.behaviorHints.videoSize ?: prior.behaviorHints.videoSize,
                filename = incoming.behaviorHints.filename ?: prior.behaviorHints.filename
            )
        }
        return incoming.copy(
            title = incoming.title ?: prior.title,
            description = incoming.description ?: prior.description,
            fileIdx = incoming.fileIdx ?: prior.fileIdx,
            behaviorHints = mergedHints,
            sources = incoming.sources ?: prior.sources,
            quality = incoming.quality ?: prior.quality,
            qualityValue = if (incoming.qualityValue >= 0) incoming.qualityValue else prior.qualityValue,
            clientResolve = incoming.clientResolve ?: prior.clientResolve,
            badges = incoming.badges.ifEmpty { prior.badges },
            streamData = incoming.streamData ?: prior.streamData
        )
    }

    /** [Stream.streamData]'s upstream name kept only for a gateway's stream (Spec 42 D6). */
    private fun Stream.withUpstreamIf(gateway: Boolean): Stream =
        if (gateway || streamData?.upstream == null) this else copy(streamData = streamData.copy(upstream = null))

    private fun Stream.dedupKey(): String =
        infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}" }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: "${addonName}:${name}:${title}"

    /**
     * Streams from one addon; internal for the fan-out above and its tests. [userAgent]
     * overrides the app UA for this request (AIOStreams streamData, X1); null keeps it.
     */
    internal suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String,
        addonName: String? = null,
        addonLogo: String? = null,
        userAgent: String? = null
    ): NetworkResult<AddonStreamAnswer> {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
        val encodedType = encodeUrlArg(type)
        val encodedVideoId = encodeUrlArg(videoId)
        val streamUrl = "$basePath/stream/$encodedType/$encodedVideoId.json$baseQuery"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId host=${streamUrl.safeHost()}")

        // Name/logo come from the caller (it already holds the installed Addon and
        // overwrites these fields on the result anyway). The previous per-call
        // addonRepository.fetchAddon() here bypassed the manifest cache and fired a
        // redundant network round trip per addon, serialised before every stream
        // request, only to be discarded (main review F1).
        val resolvedAddonName = addonName
            ?: context.getString(com.nuvio.tv.R.string.stream_addon_unknown)

        return when (val result = safeAddonApiCall(context, ADDON_STREAM_FETCH_TIMEOUT_MS) {
            api.getStreams(streamUrl, userAgent)
        }) {
            is NetworkResult.Success -> {
                // Gateway status rows never become sources: a response holding only those is
                // empty, so the addon's chip ends as a failure (Spec 42 P1). Error rows still
                // name the upstream that failed (P6).
                val rows = result.data.streams.orEmpty()
                val missingUpstreams = rows.mapNotNull { it.gatewayErrorUpstream() }
                    .distinct()
                    .take(MAX_MISSING_UPSTREAMS)
                val streams = rows
                    .filterNot { it.isGatewayStatusRow() }
                    .toListedStreams(resolvedAddonName, addonLogo)
                Log.d(
                    TAG,
                    "Streams success addon=$resolvedAddonName count=${streams.size} " +
                        "missingUpstreams=${missingUpstreams.size} host=${streamUrl.safeHost()}"
                )
                NetworkResult.Success(AddonStreamAnswer(streams, missingUpstreams))
            }
            is NetworkResult.Error -> {
                // ua attributes a body-cap failure to the larger streamData responses (QU5-3;
                // stream lists cap at UntrustedBodyCap.STREAMS). Build-time constant, not a secret.
                Log.w(
                    TAG,
                    "Streams failed addon=$resolvedAddonName code=${result.code} message=${result.message} " +
                        "ua=${userAgent ?: "app"} host=${streamUrl.safeHost()}"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Fetch meta for the given content and extract inline streams from the
     * matching video entry. Returns an empty list, without a request, when the
     * addon declares no meta resource for this type and id (Spec 84 A8), and when
     * the video has no inline streams.
     */
    private suspend fun fetchInlineStreamsFromMeta(
        addon: Addon,
        type: String,
        videoId: String
    ): List<Stream> {
        // For inline streams the meta is fetched using the content-level ID
        // (everything before the video-specific suffix). For "other" type
        // the videoId IS the content ID; for series it is contentId:S:E.
        val metaId = addonMetaIdOf(videoId)
        if (!addon.servesMeta(type, metaId)) return emptyList()
        val (basePath, baseQuery) = splitAddonBaseUrl(addon.baseUrl)
        val encodedType = encodeUrlArg(type)
        val encodedMetaId = encodeUrlArg(metaId)
        val metaUrl = "$basePath/meta/$encodedType/$encodedMetaId.json$baseQuery"
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId host=${metaUrl.safeHost()}")
        return try {
            when (val result = safeAddonApiCall(context) { api.getMeta(metaUrl) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    val streams = matchingVideo?.streams
                        ?.toListedStreams(addon.displayName, addon.logo)
                        .orEmpty()
                    Log.d(TAG, "Inline streams from meta: addon=${addon.displayName} videoId=$videoId found=${streams.size}")
                    streams
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${e.message}")
            emptyList()
        }
    }

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_no_streams_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        // A gateway whose every upstream failed: name those upstreams (Spec 42 P6, mockup stage 5).
        // Other addons' failures are left out of this message; their chips show them.
        missingUpstreamsText(
            names = failures.filter { it.kind == StreamFailureKind.UPSTREAMS_FAILED }.flatMap { it.upstreams },
            maxNames = MESSAGE_UPSTREAM_NAMES,
            templates = MissingUpstreamsTemplates(
                one = context.getString(R.string.stream_upstreams_missing_one),
                list = context.getString(R.string.stream_upstreams_missing_list),
                more = context.getString(R.string.stream_upstreams_missing_more)
            )
        )?.let { return context.getString(R.string.stream_error_upstreams_failed, it) }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == StreamFailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return context.getString(R.string.error_stream_tried_none, triedAddons, id, type)
        }

        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            context.getString(R.string.error_stream_tried_generic, triedAddons, id, type)
        } else {
            context.getString(R.string.error_stream_tried_issues, triedAddons, id, type, issueSummary)
        }
    }
}
