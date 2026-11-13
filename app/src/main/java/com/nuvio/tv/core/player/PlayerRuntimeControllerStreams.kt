package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamTextSizeParser
import com.nuvio.tv.core.stream.hiddenStreamKeys
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.repository.streamRequestType
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.missingUpstreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Hard ceiling for next-episode stream search to prevent hanging forever. */
private const val NEXT_EPISODE_HARD_TIMEOUT_MS = 120_000L

/**
 * Schedules incremental badge matching for source streams in the background.
 * Only processes addon groups not yet badged, emits UI update every 5 streams.
 * Runs on the Main-dispatched [scope] (matching is main-safe, it hops to Default
 * itself) so [sourceBadgedAddonNames] is only ever touched on Main. A newer
 * emission replaces the previous job; addons are marked badged only once fully
 * processed, so a cancelled job leaves its unfinished addons to the next one.
 */
internal fun PlayerRuntimeController.scheduleSourceBadgeApplication() {
    val state = _uiState.value
    val newAddons = state.sourceAvailableAddons.filter { it !in sourceBadgedAddonNames }
    if (newAddons.isEmpty()) return

    sourceBadgeJob?.cancel()
    sourceBadgeJob = scope.launch {
        val allNewStreams = newAddons.flatMap { addonName ->
            _uiState.value.sourceAllStreams.filter { it.addonName == addonName }
        }
        if (allNewStreams.isEmpty()) {
            sourceBadgedAddonNames = sourceBadgedAddonNames + newAddons.toSet()
            return@launch
        }
        val chunks = allNewStreams.chunked(5)
        for ((index, chunk) in chunks.withIndex()) {
            val chunkGroup = com.nuvio.tv.domain.model.AddonStreams(addonName = "", addonLogo = null, streams = chunk)
            val badgedChunk = streamBadgePresentation.apply(listOf(chunkGroup))
                .firstOrNull()?.streams ?: chunk
            // Keyed by limitKey, not badgeMergeKey: two services' copies of one torrent file
            // share the merge key, and the whole badged stream replaces the row, so a merge-key
            // match would give copy B copy A's URL (Spec 51 R5-7, as the Stream screen).
            val badgedByKey = badgedChunk.associateBy { it.limitKey() }
            _uiState.update { current ->
                val updatedAll = current.sourceAllStreams.map { s ->
                    badgedByKey[s.limitKey()] ?: s
                }
                // Badges change no stream's size or limit key: the hidden set stands.
                current.copy(sourceAllStreams = updatedAll).withSourceList(current.sourceListView.hiddenVerdicts)
            }
            // Streams are grouped by addon, so only the addon continuing into
            // the next chunk is still partial.
            val partialAddon = chunks.getOrNull(index + 1)?.first()?.addonName
            val coveredAddons = chunk.map { it.addonName }.filter { it != partialAddon }.toSet()
            sourceBadgedAddonNames = sourceBadgedAddonNames + coveredAddons
        }
    }
}

/**
 * Schedules badge matching for episode streams in the background.
 */
internal fun PlayerRuntimeController.scheduleEpisodeBadgeApplication() {
    episodeBadgeJob?.cancel()
    episodeBadgeJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val streams = _uiState.value.episodeAllStreams
        if (streams.isEmpty()) return@launch
        val group = com.nuvio.tv.domain.model.AddonStreams(addonName = "", addonLogo = null, streams = streams)
        val badged = streamBadgePresentation.apply(listOf(group))
        val badgedStreams = badged.flatMap { it.streams }
        if (badgedStreams == streams) return@launch
        _uiState.update { current ->
            // Badges change no limit key: the hidden set stands.
            current.copy(episodeAllStreams = badgedStreams).withEpisodeList(current.episodeListView.hiddenVerdicts)
        }
    }
}

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleTimingDialog = false,
            showSpeedPanel = false,
            showChaptersPanel = false,
            showMoreDialog = false
        )
    }

    val desiredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && desiredSeason != null) {
        selectEpisodesSeason(desiredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleTimingDialog = false,
            showSpeedPanel = false,
            showChaptersPanel = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false
        )
    }
    loadSourceStreams(forceRefresh = false)
}

/** The in-player source-list request for one playing item (episode-scoped for series). */
internal data class SourceStreamsRequest(
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?
) {
    val key: String get() = "$type|$videoId|${season ?: -1}|${episode ?: -1}"
}

/** The request for the item playing now, or null when its identity is unknown. */
internal fun playingItemSourceRequest(
    contentType: String?,
    contentId: String?,
    currentVideoId: String?,
    currentSeason: Int?,
    currentEpisode: Int?
): SourceStreamsRequest? =
    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val type = contentType ?: return null
        val vid = currentVideoId ?: contentId ?: return null
        SourceStreamsRequest(type, vid, currentSeason, currentEpisode)
    } else {
        SourceStreamsRequest(contentType ?: "movie", contentId ?: return null, null, null)
    }

/**
 * True when the source list was loaded for [currentKey]. Episode switches (binge, next
 * episode, in-player episode pick) keep the previous episode's list in
 * [PlayerUiState.sourceAllStreams], so readers that act on it as the playing item's list
 * check this first.
 */
internal fun sourceListLoadedFor(loadedKey: String?, currentKey: String?): Boolean =
    currentKey != null && loadedKey == currentKey

internal fun PlayerRuntimeController.currentSourceStreamsRequest(): SourceStreamsRequest? =
    playingItemSourceRequest(contentType, contentId, currentVideoId, currentSeason, currentEpisode)

/** True when the loaded source list (or its error) belongs to the item playing now. */
internal fun PlayerRuntimeController.sourceListIsForPlayingItem(): Boolean =
    sourceListLoadedFor(sourceStreamsCacheRequestKey, currentSourceStreamsRequest()?.key)

/** [state]'s [PlayerUiState.sourceAllStreams] when it belongs to the item playing now, else empty. */
internal fun PlayerRuntimeController.sourceStreamsForPlayingItem(state: PlayerUiState = _uiState.value): List<Stream> =
    if (sourceListIsForPlayingItem()) state.sourceAllStreams else emptyList()

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    val request = currentSourceStreamsRequest() ?: return
    val (type, vid, seasonArg, episodeArg) = request
    val requestKey = request.key
    val state = _uiState.value
    val hasCachedPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null

    // Fully completed cache hit — nothing to do
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload && sourceStreamsFetchCompleted) {
        return
    }
    // Already loading the same request — don't restart
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    val isResume = !forceRefresh && !targetChanged && hasCachedPayload && !sourceStreamsFetchCompleted
    val resetList = forceRefresh || targetChanged
    sourceStreamsScope?.cancel()
    sourceStreamsJob = null
    val newScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    sourceStreamsScope = newScope
    sourceChipErrorDismissJob?.cancel()
    // The key switch and the stale-list reset happen before the launch, so a reader
    // (the dead-source failover's list wait) never sees the old item's list under the
    // new key, whatever the dispatch timing.
    sourceStreamsCacheRequestKey = requestKey
    sourceStreamsFetchCompleted = false
    if (resetList) {
        sourceBadgeJob?.cancel()
        sourceBadgedAddonNames = emptySet()
    }
    updateAndDeriveStreamLists {
        val loading = it.copy(isLoadingSourceStreams = true, sourceStreamsError = null)
        if (!resetList) {
            loading
        } else {
            loading.copy(
                sourceAllStreams = emptyList(),
                sourceMissingUpstreams = emptyList(),
                sourceListSelection = StreamListSelection.All,
                sourceAvailableAddons = emptyList(),
                sourceChips = emptyList()
            )
        }
    }
    sourceStreamsJob = newScope.launch {
        // Spec 51: the limit is held before any result derives (and before failover can walk them).
        awaitLimitInputs()
        val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
        val installedAddonOrder = installedAddons.map { it.displayName }

        // On resume, skip chip reset — keep existing chip statuses
        if (!isResume) {
            updateSourceChipsForFetchStart(type, vid, installedAddons)
        }

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg,
            forceRefresh = forceRefresh,
            title = state.contentName?.takeIf { it.isNotBlank() }
                ?: state.title.takeIf { it.isNotBlank() }
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    // Derives both lists: the episode list's exemption is read from this one.
                    updateAndDeriveStreamLists {
                        // On resume, merge fresh results with any previously cached streams
                        val mergedAllStreams = if (isResume && it.sourceAllStreams.isNotEmpty()) {
                            mergeSourceStreams(it.sourceAllStreams, allStreams)
                        } else {
                            allStreams
                        }
                        // Preserve badges already computed by prior badge jobs (by limitKey, as
                        // the badge job: a twin copy on another service keeps its own).
                        val existingBadged = it.sourceAllStreams
                            .filter { s -> s.badges.isNotEmpty() }
                            .associateBy { s -> s.limitKey() }
                        val badgePreserved = if (existingBadged.isEmpty()) {
                            mergedAllStreams
                        } else {
                            mergedAllStreams.map { s ->
                                val existing = existingBadged[s.limitKey()]
                                if (existing != null && s.badges.isEmpty()) s.copy(badges = existing.badges) else s
                            }
                        }
                        val mergedAvailableAddons = if (isResume && it.sourceAvailableAddons.isNotEmpty()) {
                            (it.sourceAvailableAddons + availableAddons).distinct()
                        } else {
                            availableAddons
                        }
                        // Chips only gain names here, so the pick needs no keptIn.
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = badgePreserved,
                            sourceMissingUpstreams = addonStreams.missingUpstreams(),
                            sourceAvailableAddons = mergedAvailableAddons,
                            sourceChips = mergeSourceChipStatuses(
                                existing = it.sourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            sourceStreamsError = null
                        )
                    }
                    scheduleSourceBadgeApplication()
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingSourceStreams = true) }
                }
            }
        }
        sourceStreamsFetchCompleted = true
        markRemainingSourceChipsAsError()
    }
}

/**
 * Merge fresh stream results with previously cached streams.
 * Newer entries for the same stream (matched by addon + url/infoHash) replace older ones.
 */
private fun mergeSourceStreams(cached: List<Stream>, fresh: List<Stream>): List<Stream> {
    val merged = LinkedHashMap<String, Stream>()
    cached.forEach { stream -> merged[stream.mergeKey()] = stream }
    fresh.forEach { stream -> merged[stream.mergeKey()] = stream }
    return merged.values.toList()
}

private fun Stream.mergeKey(): String =
    infoHash?.lowercase()?.let { hash -> "$addonName|$hash:${fileIdx ?: ""}" }
        ?: "$addonName|${getStreamUrl() ?: externalUrl ?: ytId ?: "${name}:${title}"}"

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    sourceChipErrorDismissJob?.cancel()
    _uiState.update {
        it.copy(
            showSourcesPanel = false,
            isLoadingSourceStreams = false
        )
    }
    scheduleHideControls()
}

private fun PlayerRuntimeController.updateSourceChipsForFetchStart(
    type: String,
    videoId: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type, videoId) }
        .map { it.displayName }

    val ordered = addonNames.distinct()
    _uiState.update {
        it.withSourceChips(ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) })
    }
}

private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }

    val successSet = succeededNames.toSet()
    val updated = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()

    val known = updated.map { it.name }.toSet()
    succeededNames.forEach { name ->
        if (name !in known) updated += SourceChipItem(name, SourceChipStatus.SUCCESS)
    }
    return updated
}

private fun PlayerRuntimeController.markRemainingSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.sourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAnyError = true
        state.copy(
            sourceChips = state.sourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) {
                    chip.copy(status = SourceChipStatus.ERROR)
                } else {
                    chip
                }
            }
        )
    }
    if (!markedAnyError) return

    sourceChipErrorDismissJob?.cancel()
    sourceChipErrorDismissJob = scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.withSourceChips(state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR })
        }
    }
}

private fun PlayerRuntimeController.updateEpisodeSourceChipsForFetchStart(
    type: String,
    videoId: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type, videoId) }
        .map { it.displayName }

    val ordered = addonNames.distinct()
    _uiState.update {
        it.withEpisodeChips(ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) })
    }
}

private fun PlayerRuntimeController.markRemainingEpisodeSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.episodeSourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAnyError = true
        state.copy(
            episodeSourceChips = state.episodeSourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) {
                    chip.copy(status = SourceChipStatus.ERROR)
                } else {
                    chip
                }
            }
        )
    }
    if (!markedAnyError) return

    scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.withEpisodeChips(state.episodeSourceChips.filterNot { it.status == SourceChipStatus.ERROR })
        }
    }
}

private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String, videoId: String): Boolean =
    streamRequestType(type, videoId) != null

/**
 * Filename for a stream picked in-player (source/episode switch, failover): its own
 * hint, else the URL's last path segment when it looks like a file. Never the launch
 * stream's navigationArgs.filename: currentFilename keys the AFR cache, mime
 * inference and subtitle hashing, so inheriting it misidentifies the new file.
 */
internal fun switchedStreamFilename(hintFilename: String?, url: String?): String? =
    hintFilename ?: url?.substringBefore('?')?.substringAfterLast('/', "")
        ?.takeIf { it.isNotBlank() && it.contains('.') }

/**
 * False only for a stream that needs TorrServer (Stream.isTorrent: no playable HTTP url) while
 * P2P is off: no consent to start it. Gates automatic picks (dead-source failover, next-episode
 * auto-play) and, before any teardown, manual in-player torrent switches.
 */
internal fun isP2pPermitted(stream: Stream, p2pEnabled: Boolean): Boolean =
    p2pEnabled || !stream.isTorrent()

/**
 * Whether the dead-source failover may switch to [stream] on its own (Register A2 P13, ysosrs
 * `a5e29d3cd`): an external link would open a browser mid-playback, and a row with no http(s)
 * link cannot play in the player (ytId only stops on the invalid-URL error under the failover
 * overlay; a stremio: link fails in ExoPlayer and burns a failover slot). Torrent rows follow
 * the P2P consent and need an info hash (switchToTorrentSourceStream returns silently without).
 */
internal fun isAutoFailoverCandidate(stream: Stream, p2pEnabled: Boolean): Boolean {
    if (stream.isExternal()) return false
    if (stream.isTorrent()) return isP2pPermitted(stream, p2pEnabled) && stream.getEffectiveInfoHash() != null
    val url = stream.getStreamUrl()?.trim() ?: return false
    return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}

/**
 * A manual in-player torrent pick with P2P off: TorrentService.startStream would throw only
 * after the switch had torn down working playback. Keeps playing and shows a short notice
 * (the failover Toast pattern); the panel keeps its stream list.
 */
private fun PlayerRuntimeController.rejectedForP2pOff(stream: Stream): Boolean {
    if (isP2pPermitted(stream, p2pEnabled)) return false
    scope.launch {
        android.widget.Toast.makeText(
            context,
            context.getString(com.nuvio.tv.R.string.torrent_error_p2p_disabled),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    return true
}

/**
 * Seeds a source or engine switch's resume from the live position, captured before the old
 * player is released. A saved-progress read here would race the async save that
 * flushPlaybackSnapshotForSwitchOrExit just started and could resume from a stale
 * row. Returns false when there is no live position (fall back to the read), and after a
 * placeholder reject (Spec 21 P3: the playhead is the placeholder's, not the title's).
 */
internal fun PlayerRuntimeController.seedSourceSwitchResumeFromLivePosition(): Boolean {
    if (placeholderRejectedForStream) return false
    val position = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: return false
    pendingResumeProgress = null
    _uiState.update { it.copy(pendingSeekPosition = position) }
    return true
}

/**
 * Per-stream state every in-player switch (source or episode, HTTP or torrent)
 * resets before the new stream initialises. Must run before initializePlayer /
 * the torrent launch. [resetFailoverBudget] is forwarded to resetErrorRetryState.
 */
private fun PlayerRuntimeController.resetPerStreamSwitchState(resetFailoverBudget: Boolean) {
    // The one 416 reset: initializePlayer must not clear it, or the retry-from-start would re-arm
    // on the rebuild it triggers (Spec 21 P8).
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState(resetDeadSourceFailovers = resetFailoverBudget)
    trackSelection.newStream()
    lastSavedPosition = 0L
    // AFR review F2/R2: the previous stream's TRACK detection would make the
    // new stream's preflight skip-guard a guaranteed no-op, so mixed-frame-rate
    // sources/episodes (25 fps HDTV next to 23.976 WEB-DL) never re-matched.
    _uiState.update {
        it.copy(
            detectedFrameRate = 0f,
            detectedFrameRateSource = null
        )
    }
}

private fun PlayerRuntimeController.applySelectedStreamState(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
    currentStreamUrl = playbackRequest.url
    currentHeaders = playbackRequest.headers
    currentFilename = switchedStreamFilename(stream.behaviorHints?.filename, playbackRequest.url)
    currentStreamResponseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = playbackRequest.url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    parsingErrorProbeAttempted = false
    applyStreamMetadata(stream)
}

/**
 * Apply stream metadata that is common to both HTTP and torrent paths.
 * Ensures binge-group, addon info, and video hints are always set regardless
 * of stream type — critical for next-episode binge matching.
 */
private fun PlayerRuntimeController.applyStreamMetadata(stream: Stream) {
    // Stream identity changes here: a subtitle fetch still running for the old
    // stream must not read the new hash/url or write its results. The loading
    // flag is cleared by resetAddonSubtitleStateForNewStream on the reinit.
    addonSubtitleFetchJob?.cancel()
    addonSubtitleFetchJob = null
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = StreamTextSizeParser.effectiveSizeBytes(stream)
    currentStreamSubtitles = stream.subtitles
    currentStreamDescription = stream.description
    persistContentBingeGroup(stream.behaviorHints?.bingeGroup)
}

/**
 * Remembers [bingeGroup] for this content so later episode plays (CW, Details, next episode)
 * reuse the same source group. Not for movies (Spec 42 P2): a movie has no next episode, and a
 * remembered group is the probable way Details > Play picked a same-group row the runtime guard
 * had rejected (F-2, unconfirmed). A movie writes null, which clears an entry left from before
 * on its next play.
 */
internal fun PlayerRuntimeController.persistContentBingeGroup(bingeGroup: String?) {
    val cid = contentId ?: return
    val value = bingeGroup.takeIf { contentType != "movie" }
    scope.launch(kotlinx.coroutines.NonCancellable) {
        bingeGroupCacheDataStore.replace(cid, value)
    }
}

private fun PlayerRuntimeController.persistSelectedStreamForReuse(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = headers,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year
        )
    }
}

private fun PlayerRuntimeController.persistTorrentStreamForReuse(stream: Stream) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val infoHash = stream.getEffectiveInfoHash() ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = "",
            streamName = streamName,
            headers = emptyMap(),
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            infoHash = infoHash,
            fileIdx = stream.getEffectiveFileIdx(),
            sources = stream.sources,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year
        )
    }
}

private fun PlayerRuntimeController.openExternalStreamInBrowser(
    stream: Stream,
    fromEpisodePanel: Boolean
): Boolean {
    if (!stream.isExternal()) return false

    val externalUrl = stream.getStreamUrl()
    if (externalUrl.isNullOrBlank()) {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            } else {
                it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            }
        }
        return true
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching {
        context.startActivity(browserIntent)
    }.onSuccess {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(
                    showEpisodesPanel = false,
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false,
                    episodeStreamsError = null
                )
            } else {
                it.copy(
                    showSourcesPanel = false,
                    isLoadingSourceStreams = false,
                    sourceStreamsError = null
                )
            }
        }
    }.onFailure { error ->
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            } else {
                it.copy(sourceStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            }
        }
    }

    return true
}

/**
 * [resetFailoverBudget]: true for a user-picked source (fresh dead-source
 * failover budget); the automatic failover (advanceToNextLiveSource) passes
 * false so its MAX_DEAD_SOURCE_FAILOVERS count survives the switch.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(
    stream: Stream,
    resetFailoverBudget: Boolean = true
) {
    if (rejectedForP2pOff(stream)) return
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = false)) {
        return
    }

    if (stream.isTorrent()) {
        switchToTorrentSourceStream(stream, resetFailoverBudget)
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    val resumeSeededFromLivePosition = seedSourceSwitchResumeFromLivePosition()
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders
    persistSelectedStreamForReuse(stream = stream, url = playbackUrl, headers = playbackHeaders)

    // Reset stream-state error flags for the new stream.
    resetPerStreamSwitchState(resetFailoverBudget)

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = playbackUrl,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = false
        )
    }
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)

    initializePlayer(playbackUrl, playbackHeaders)

    if (PlayerStartPosition.readsSavedResumeOnSwitch(resumeSeededFromLivePosition, startFromBeginningPending)) {
        loadSavedProgressFor(currentSeason, currentEpisode)
    }
}

/**
 * Cancels the episode-stream fetch. A fetch cut off mid-load would leave a partial list cached
 * under its key (chips stuck loading on the next open of that episode), so the key is forgotten
 * and that episode refetches. Checked here, not in the job's completion, which would race a newer
 * fetch for the same key.
 */
private fun PlayerRuntimeController.cancelEpisodeStreamsFetch() {
    if (episodeStreamsJob?.isActive == true) episodeStreamsCacheRequestKey = null
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    cancelEpisodeStreamsFetch()
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return

    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(
            episodesSelectedSeason = season,
            episodes = episodesForSeason
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.switchToTorrentSourceStream(
    stream: Stream,
    resetFailoverBudget: Boolean
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    stopTorrentStream()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    flushPlaybackSnapshotForSwitchOrExit()
    val resumeSeededFromLivePosition = seedSourceSwitchResumeFromLivePosition()
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)
    resetPerStreamSwitchState(resetFailoverBudget)
    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = true
        )
    }
    applyStreamMetadata(stream)
    currentFilename = switchedStreamFilename(stream.behaviorHints?.filename, url = null)
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)
    launchTorrentSourceStream(
        stream,
        infoHash,
        loadSavedProgress = PlayerStartPosition.readsSavedResumeOnSwitch(
            resumeSeededFromLivePosition,
            startFromBeginningPending
        )
    )
    persistTorrentStreamForReuse(stream)
}

private fun PlayerRuntimeController.switchToTorrentEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video?,
    isAutoPlay: Boolean
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay
    )
    stopTorrentStream()
    beginEpisodeSwitch()
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)
    applyStreamMetadata(stream)
    currentFilename = switchedStreamFilename(stream.behaviorHints?.filename, url = null)
    applyEpisodeSwitchState(stream, forcedTargetVideo, playbackUrl = "", isTorrent = true)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
    persistTorrentStreamForReuse(stream)
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type = contentType
    val id = contentId
    if (type.isNullOrBlank() || id.isNullOrBlank()) return
    if (type !in listOf("series", "tv")) return
    if (_uiState.value.episodesAll.isNotEmpty() || _uiState.value.isLoadingEpisodes) return

    scope.launch {
        _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }

        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                val allEpisodes = result.data.videos
                    .sortedWith(
                        compareBy<Video> { it.season ?: Int.MAX_VALUE }
                            .thenBy { it.episode ?: Int.MAX_VALUE }
                            .thenBy { it.title }
                    )

                applyMetaDetails(result.data)

                val seasons = allEpisodes
                    .mapNotNull { it.season }
                    .distinct()
                    .sorted()

                val preferredSeason = when {
                    currentSeason != null && seasons.contains(currentSeason) -> currentSeason
                    initialSeason != null && seasons.contains(initialSeason) -> initialSeason
                    else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
                }

                val selectedSeason = preferredSeason ?: 1
                val episodesForSeason = allEpisodes
                    .filter { (it.season ?: -1) == selectedSeason }
                    .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

                _uiState.update {
                    it.copy(
                        isLoadingEpisodes = false,
                        episodesAll = allEpisodes,
                        episodesAvailableSeasons = seasons,
                        episodesSelectedSeason = selectedSeason,
                        episodes = episodesForSeason,
                        episodesError = null
                    )
                }
            }

            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = result.message) }
            }

            NetworkResult.Loading -> {
            }
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video, forceRefresh: Boolean) {
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_missing_content_type)) }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasCachedPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasCachedPayload) {
        // Re-derived: this Video may carry another runtime than the one the list was derived with.
        updateAndDeriveStreamLists {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title,
                episodeStreamsRuntimeMinutes = video.runtime
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    val newScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    episodeStreamsScope = newScope
    episodeStreamsJob = newScope.launch {
        episodeStreamsCacheRequestKey = requestKey
        val resetList = forceRefresh || targetChanged
        // The list's target and its runtime (the limit's, Spec 51 M3) change in one update.
        updateAndDeriveStreamLists {
            val loading = it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title,
                episodeStreamsRuntimeMinutes = video.runtime
            )
            if (!resetList) {
                loading
            } else {
                loading.copy(
                    episodeAllStreams = emptyList(),
                    episodeMissingUpstreams = emptyList(),
                    episodeListSelection = StreamListSelection.All,
                    episodeAvailableAddons = emptyList(),
                    episodeSourceChips = emptyList()
                )
            }
        }

        // Spec 51: the limit is held before any result derives.
        awaitLimitInputs()
        val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
        val installedAddonOrder = installedAddons.map { it.displayName }

        // Initialize episode source chips with LOADING status
        updateEpisodeSourceChipsForFetchStart(type, video.id, installedAddons)

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            forceRefresh = forceRefresh,
            title = video.title.takeIf { it.isNotBlank() }
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    updateAndDeriveStreamLists {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeMissingUpstreams = addonStreams.missingUpstreams(),
                            episodeAvailableAddons = availableAddons,
                            episodeSourceChips = mergeSourceChipStatuses(
                                existing = it.episodeSourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            episodeStreamsError = null
                        )
                    }
                    scheduleEpisodeBadgeApplication()
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
                }
            }
        }
        markRemainingEpisodeSourceChipsAsError()
    }
}

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetVideoId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetVideoId },
        state.episodesAll.firstOrNull { it.id == targetVideoId },
        state.episodes.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        },
        state.episodesAll.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video? = null,
    isAutoPlay: Boolean = false
) {
    if (rejectedForP2pOff(stream)) return
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = true)) {
        return
    }

    if (stream.isTorrent()) {
        switchToTorrentEpisodeStream(stream, forcedTargetVideo, isAutoPlay)
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay,
    )

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()
    beginEpisodeSwitch()

    // Pause current playback immediately so the old stream doesn't continue
    // playing audio/video in the background while the new episode is being prepared.
    _exoPlayer?.stop()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    // nt13: the URL and headers are final here, the outgoing stream is already
    // stopped, and the player is still ~1.3-3.1 s away from opening the
    // datasource (measured across five transitions, 27 Jul 2026). Start chunk 0
    // into that gap. Fresh presses deliberately do not call this, so every
    // capture carries its own control arm.
    //
    // nt14: the settings push must happen first. initializePlayer does it too,
    // but that runs after this point, so without it the pre-start keys its
    // session on the previous stream's geometry.
    //
    // nt16: from the cached snapshot, synchronously. nt14 read the settings Flow
    // here, and that Flow is cold -- every transition paid a full datastore read.
    // Measured 27 Jul: chunk 0 started 982 and 935 ms after the URL was final,
    // against 79 ms when this hook was synchronous. That was most of the head
    // start the hook exists to create, spent acquiring settings that the previous
    // initializePlayer had already resolved.
    //
    // A null snapshot means no playback has initialised yet, which cannot happen
    // on a transition. Stale settings (changed mid-session, before the next
    // initializePlayer) key the session on the wrong geometry, the player
    // declines to adopt it, and the path falls back to opening as it always did.
    lastAppliedPlayerSettings?.let { cachedSettings ->
        applyMediaSourceFactorySettings(cachedSettings)
        runCatching {
            mediaSourceFactory.prestartChunk0(
                url = url,
                headers = newHeaders,
                filename = stream.behaviorHints?.filename
            )
        }
    }

    // The player is stopped, not released: initializePlayer reuses it (nt12), and the
    // chunk-0 pre-start above only pays off on that path.
    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders
    applyEpisodeSwitchState(stream, forcedTargetVideo, playbackUrl = playbackUrl, isTorrent = false)
    persistSelectedStreamForReuse(stream = stream, url = playbackUrl, headers = playbackHeaders)

    Log.i(
        PlayerRuntimeController.TAG,
        "LINK_SELECTED: source=in_player_source host=${playbackUrl.safeHost()} " +
            "streamName=${stream.name?.compactTraceValue()} addon=${stream.addonName} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} torrent=false"
    )
    preparePlaybackBeforeStart(
        url = playbackUrl,
        headers = playbackHeaders,
        loadSavedProgress = true
    )
}

/**
 * First step of every in-player episode switch (HTTP and torrent): stop the old
 * episode's jobs and save its progress while its player still exists.
 */
private fun PlayerRuntimeController.beginEpisodeSwitch() {
    cancelEpisodeStreamsFetch()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    flushPlaybackSnapshotForSwitchOrExit()
}

/**
 * The episode state both switch paths share (Spec 21 P4, 11/L1-67), after each has torn
 * down its old player and applied the new stream: per-stream resets, the new episode's
 * identity and UI state, the subtitle reset (on both paths; before P4 it ran for torrents
 * only), next-episode, parental and skip data. [playbackUrl] is "" for a torrent until
 * TorrServer resolves it.
 */
private fun PlayerRuntimeController.applyEpisodeSwitchState(
    stream: Stream,
    forcedTargetVideo: Video?,
    playbackUrl: String,
    isTorrent: Boolean
) {
    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    trackSelection.newContent()
    // Spec 57 D4: "Start from beginning" was for the episode it was chosen on.
    startFromBeginningPending = false
    // A position carried from the old episode must not become this one's start
    // position (it outranks the saved resume at Exo init, Spec 37 / A5-23).
    _uiState.update { it.copy(pendingSeekPosition = null) }
    // Reset stream-state error flags for the new stream.
    resetPerStreamSwitchState(resetFailoverBudget = true)

    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = playbackUrl,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = isTorrent,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)
    updateEpisodeDescription()
    resetSubtitlesForEpisodeSwitch()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleTimingDialog = false,
            showSpeedPanel = false,
            showChaptersPanel = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    // Episodes already loaded: show that season's list too, so Back lands on the season the
    // eyebrow and chips name. Not loaded yet: a no-op; the load sets season and list together.
    video.season?.let { selectEpisodesSeason(it) }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal fun PlayerRuntimeController.playNextEpisode(userInitiated: Boolean = false) {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return

    // Instrument (26 Jul capture gap): everything between the press and
    // stream selection was unmeasured, so the addon scrape and the bounded
    // streamAutoPlayTimeoutSeconds wait were both invisible and the transition
    // budget was inferred rather than read. These two lines close that.
    // Logged under TTFF_STAGE so the existing capture tag filter is unchanged.
    val nextEpisodePressElapsedMs = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i(
        "TTFF_STAGE",
        "NEXT_EPISODE_PRESS userInitiated=$userInitiated " +
            "season=${nextVideo.season} episode=${nextVideo.episode}"
    )

    val state = _uiState.value
    val nextInfo = state.nextEpisode ?: return
    if (!nextInfo.hasAired) {
        return
    }
    val activeAutoPlay = state.postPlayMode as? PostPlayMode.AutoPlay
    if (activeAutoPlay != null &&
        (activeAutoPlay.searching || activeAutoPlay.countdownSec != null)
    ) {
        return
    }

    // Follow the episode. Video.runtime is the per-episode value the next-episode
    // resolver already holds, so no lookup and no added latency on the transition.
    // Placed after the early returns so an aborted transition cannot mutate it.
    // Where the addon supplies no per-episode runtime the previous value is kept:
    // same series, so a closer approximation than dropping to null, and every
    // downstream use treats an over-estimate as fail-safe.
    nextVideo.runtime?.let { expectedRuntimeMinutes = it }

    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = nextInfo,
                searching = true,
            ),
        )
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val pick = pickEpisodeStream(nextVideo, type, nextEpisodePressElapsedMs, "NEXT_EPISODE_STREAMS_SETTLED")
            if (pick is EpisodeStreamPick.Picker) {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = pick.forceRefresh)
                return@launch
            }
            val streamToPlay = (pick as EpisodeStreamPick.Picked).stream
            val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
            // The countdown exists so the decision can be cancelled.
            //
            // S5/B3: countdown removed on a deliberate press (26 Jul 2026).
            // nt2 shipped it shortened to 1 s so the chosen source name
            // stayed visible; the capture confirmed the card renders the
            // name correctly, and Paul's call is that the second of latency
            // is not worth the name. The loop body was pure dead time: the
            // delay(1000) overlapped no work whatsoever (measured 26 Jul: a
            // 1,064 ms gap before preparing_metadata).
            //
            // Auto-play is deliberately untouched at three seconds: an
            // unattended transition still needs a cancellable window.
            //
            // Button-suppression note: countdownSec != null is what greys
            // out SkipNext while the card is up. searching = true covers the
            // search above, and with the countdown gone selection runs
            // straight into switchToEpisodeStream, so the unguarded window
            // is ~0 ms.
            if (!userInitiated) {
                for (remaining in 3 downTo 1) {
                    _uiState.update { current ->
                        val episodeForMode = current.nextEpisode ?: nextInfo
                        current.copy(
                            postPlayMode = PostPlayMode.AutoPlay(
                                nextEpisode = episodeForMode,
                                searching = false,
                                sourceName = sourceName,
                                countdownSec = remaining,
                            ),
                        )
                    }
                    delay(1000)
                }
            }
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                    playbackEnded = false,
                )
            }
            switchToEpisodeStream(
                stream = streamToPlay,
                forcedTargetVideo = nextVideo,
                isAutoPlay = !userInitiated
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}

/**
 * Spec 45 Previous: play the episode before this one from its saved position, through the same
 * scrape + auto-select as a Next press ([pickEpisodeStream]; never prefetched, so it always scrapes).
 * While it searches, [PlayerUiState.previousEpisodeSearch] drives the post-play card ("Previous
 * episode · Finding a source"), never [postPlayMode], whose auto-play and focus rules are Next's; a
 * resting Next card is cleared and the threshold rule may bring it back once the search ends.
 * Ignored while a Next search, countdown or still-watching prompt holds the card. The
 * job shares [nextEpisodeAutoPlayJob], so a Next press, a source switch or an exit cancels it too.
 */
internal fun PlayerRuntimeController.playPreviousEpisode() {
    val target = previousEpisodeVideo ?: return
    val type = contentType ?: return
    val state = _uiState.value
    val info = state.previousEpisode?.takeIf { it.hasAired && it.videoId == target.id } ?: return
    if (state.previousEpisodeSearch != null) return
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return

    val pressElapsedMs = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i("TTFF_STAGE", "PREVIOUS_EPISODE_PRESS season=${target.season} episode=${target.episode}")
    _uiState.update { it.copy(previousEpisodeSearch = info, postPlayMode = null) }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val pick = pickEpisodeStream(target, type, pressElapsedMs, "PREVIOUS_EPISODE_STREAMS_SETTLED")
            when (pick) {
                is EpisodeStreamPick.Picked -> {
                    // As Next: the episode's own runtime, set only once it is really switching.
                    target.runtime?.let { expectedRuntimeMinutes = it }
                    _uiState.update { it.copy(previousEpisodeSearch = null, playbackEnded = false) }
                    switchToEpisodeStream(stream = pick.stream, forcedTargetVideo = target, isAutoPlay = false)
                }
                is EpisodeStreamPick.Picker -> {
                    // As Next: no Next card over the sources panel.
                    _uiState.update { it.copy(previousEpisodeSearch = null, postPlayDismissedForCurrentEpisode = true) }
                    showEpisodeStreamPicker(video = target, forceRefresh = pick.forceRefresh)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(previousEpisodeSearch = null, postPlayDismissedForCurrentEpisode = true) }
            showEpisodeStreamPicker(video = target, forceRefresh = false)
        } finally {
            // Cancelled (Next press, source switch, exit): drop the card. Only this press sets it,
            // and a second press is ignored while it is set, so the value here is always ours.
            if (_uiState.value.previousEpisodeSearch === info) {
                _uiState.update { it.copy(previousEpisodeSearch = null) }
            }
        }
    }
}

/** What [pickEpisodeStream] settled on: a stream to switch to, or the episode's sources panel. */
internal sealed interface EpisodeStreamPick {
    data class Picked(val stream: Stream) : EpisodeStreamPick
    data class Picker(val forceRefresh: Boolean) : EpisodeStreamPick
}

/**
 * The scrape + auto-select behind an episode press (Next, and Spec 45 Previous): the stream the
 * auto-play rules pick for [target] within the timeout, else the sources panel (manual mode, nothing
 * matched, forceRefresh after an addon error). Reads [target] through the prefetch cache, so Next's
 * binge lookahead still lands; [pressElapsedMs] stamps the TTFF_STAGE [settleTag] line.
 */
private suspend fun PlayerRuntimeController.pickEpisodeStream(
    target: Video,
    type: String,
    pressElapsedMs: Long,
    settleTag: String
): EpisodeStreamPick = coroutineScope {
    val playerSettings = playerSettingsDataStore.playerSettings.first()
    val shouldAutoSelectInManualMode =
        playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                playerSettings.streamAutoPlayNextEpisodeEnabled ||
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                )
    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            (!playerSettings.streamAutoPlayNextEpisodeEnabled ||
                !playerSettings.streamAutoPlayNextEpisodeFallbackEnabled) &&
            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
    if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
        return@coroutineScope EpisodeStreamPick.Picker(forceRefresh = true)
    }

    val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
    val installedAddonOrder = installedAddons.map { it.displayName }
    // Spec 51 P5: the press never picks a stream the limit hides: the set over the candidates it
    // ranks, with the target's own runtime and the binge exemption the lookahead uses (one helper).
    val limit = awaitLimitInputs()
    val exemptBingeGroup = limitExemptBingeGroup(limit)
    fun hiddenKeysOf(candidates: List<Stream>): Set<String> = hiddenStreamKeys(
        streams = candidates,
        limit = limit,
        transport = transportNow,
        runtimeMinutes = target.runtime,
        episode = true,
        exemptBingeGroup = exemptBingeGroup
    )
    val effectiveMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        playerSettings.streamAutoPlayMode
    }
    val effectiveSource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        playerSettings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        playerSettings.streamAutoPlaySelectedAddons
    }
    val effectiveRegex = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        playerSettings.streamAutoPlayRegex
    }
    var selectedStream: Stream? = null
    var lastSuccessData: List<AddonStreams>? = null
    var autoSelectTriggered = false
    var timeoutElapsed = false
    var lastError: NetworkResult.Error? = null
    // nt7 (task 3): time spent ranking inside the settle window.
    var selectionRankMs = 0L
    var selectionRankCalls = 0
    // Completed as soon as a stream is selected or the addon search
    // finishes, so the waiting code below resumes without polling.
    val searchSettled = CompletableDeferred<Unit>()

    // Read once: the whole selection runs under one P2P consent state.
    val allowTorrents = p2pEnabled
    fun candidateStreams(data: List<AddonStreams>): List<Stream> =
        StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder)
            .flatMap { it.streams }
            .filter { isP2pPermitted(it, allowTorrents) }

    fun trySelectStreamInner(data: List<AddonStreams>): Stream? {
        val allStreams = candidateStreams(data)
        // preferBingeGroupInSelection was passed explicitly here as the
        // SETTING, while AutoPlaySelection derives it from
        // preferredBingeGroup != null. Those disagree in exactly one
        // case -- setting on, no binge group known -- and that
        // disagreement is inert: selectAutoPlayStream gates the binge
        // branch on targetBingeGroup.isNotEmpty(), false either way.
        // Asserted in StreamAutoPlaySelectorTest.
        return AutoPlaySelection.select(
            streams = allStreams,
            inputs = AutoPlaySelection.Inputs(
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonOrder.toSet(),
                selectedAddons = effectiveSelectedAddons,
                preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                    currentStreamBingeGroup
                } else {
                    null
                },
                hiddenKeys = hiddenKeysOf(allStreams)
            ),
            bingeGroupOnly = bingeGroupOnlyManualMode
        )
    }

    fun tryBingeGroupOnlyInner(data: List<AddonStreams>): Stream? {
        if (currentStreamBingeGroup == null || !playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return null
        val allStreams = candidateStreams(data)
        // The guard above returns early when currentStreamBingeGroup is
        // null, so the derived preferBingeGroupInSelection is true here
        // exactly as the explicit argument was.
        return AutoPlaySelection.select(
            streams = allStreams,
            inputs = AutoPlaySelection.Inputs(
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonOrder.toSet(),
                selectedAddons = effectiveSelectedAddons,
                preferredBingeGroup = currentStreamBingeGroup,
                hiddenKeys = hiddenKeysOf(allStreams)
            ),
            bingeGroupOnly = true
        )
    }

    // nt7 (task 3): the 27 Jul capture prices ranking at ~500 ms
    // on this device (PREFETCH rank_only ms=531/496), which would
    // account for nearly all of nt6's one 647 ms settle against
    // 43/48 ms siblings -- but only a measurement on the settle
    // line itself can adjudicate re-rank vs slow-await. These
    // wrappers keep the original names so all call sites are
    // untouched; both local selectors funnel through them.
    fun trySelectStream(data: List<AddonStreams>): Stream? {
        val rankT0 = android.os.SystemClock.elapsedRealtime()
        val result = trySelectStreamInner(data)
        selectionRankMs += android.os.SystemClock.elapsedRealtime() - rankT0
        selectionRankCalls++
        return result
    }

    fun tryBingeGroupOnly(data: List<AddonStreams>): Stream? {
        val rankT0 = android.os.SystemClock.elapsedRealtime()
        val result = tryBingeGroupOnlyInner(data)
        selectionRankMs += android.os.SystemClock.elapsedRealtime() - rankT0
        selectionRankCalls++
        return result
    }

    fun recordSelection(candidate: Stream) {
        autoSelectTriggered = true
        selectedStream = candidate
        searchSettled.complete(Unit)
    }

    val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds

    val innerJob = launch {
        // S5 binge lookahead (part 2): read THROUGH the prefetch cache.
        //
        // This call site went straight to the repository, so the
        // lookahead above would have filled a cache the binge path never
        // consulted. streamsFor() substitutes the flow rather than
        // bypassing this consumer: a hit emits Loading then one Success
        // then completes, which is indistinguishable from a very fast
        // scrape, so the timeout/auto-select machinery below is
        // untouched. A miss, an expired entry or a join timeout falls
        // through to the live flow, i.e. exactly today's behaviour.
        //
        // The win is not only the scrape: with a hit, searchSettled
        // completes almost immediately and the bounded
        // streamAutoPlayTimeoutSeconds wait (3 s on Paul's device) is
        // skipped rather than served.
        com.nuvio.tv.core.stream.StreamPrefetchCache.streamsFor(
            repository = streamRepository,
            type = type,
            videoId = target.id,
            season = target.season,
            episode = target.episode,
            title = target.title.takeIf { it.isNotBlank() }
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    lastSuccessData = result.data
                    if (!autoSelectTriggered) {
                        val candidate = when {
                            timeoutElapsed -> trySelectStream(result.data)
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode ->
                                tryBingeGroupOnly(result.data)
                            else -> null
                        }
                        if (candidate != null) recordSelection(candidate)
                    }
                }
                is NetworkResult.Error -> lastError = result
                NetworkResult.Loading -> Unit
            }
        }
        // Every addon has responded: take whatever matched, then settle so
        // the waiting code below resumes even if nothing was selected.
        if (!autoSelectTriggered) {
            lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
        }
        searchSettled.complete(Unit)
    }

    val timeoutMs = timeoutSeconds * 1_000L
    if (PlayerSettings.isBoundedTimeout(timeoutSeconds)) {
        // Wait for the timeout, resuming as soon as a stream is settled.
        withTimeoutOrNull(timeoutMs) { searchSettled.await() }
        timeoutElapsed = true
        if (!autoSelectTriggered) {
            val data = lastSuccessData
            if (data != null) {
                // Streams arrived: full select once. If nothing matches,
                // respect the timeout and stop (the caller shows the picker).
                trySelectStream(data)?.let { recordSelection(it) }
            } else {
                // No addon responded yet: keep waiting for the first
                // usable result up to the hard timeout, matching the
                // instant and unlimited branches below. searchSettled
                // completes when the scrape settles, so this resolves
                // at scrape-end (typically seconds); the cap is only a
                // hung-addon backstop, not a fixed wait. Without it a
                // slow in-flight next-episode prefetch that is about
                // to land was abandoned for the manual picker.
                withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { trySelectStream(it)?.let { s -> recordSelection(s) } }
                }
            }
        }
        innerJob.cancel()
    } else if (timeoutSeconds == 0) {
        timeoutElapsed = true
        withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
        if (!autoSelectTriggered) {
            lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
        }
        innerJob.cancel()
    } else {
        withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
        if (!autoSelectTriggered) {
            lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
        }
        innerJob.cancel()
    }

    // Everything above is scrape + auto-select, including the bounded
    // timeout wait. Subtracting this from the press stamp prices the
    // block S5 part 2 targets.
    android.util.Log.i(
        "TTFF_STAGE",
        "$settleTag " +
            "ms=${android.os.SystemClock.elapsedRealtime() - pressElapsedMs} " +
            "selected=${selectedStream != null} timeoutElapsed=$timeoutElapsed " +
            "selectMs=$selectionRankMs selectCalls=$selectionRankCalls"
    )
    selectedStream?.let { EpisodeStreamPick.Picked(it) }
        ?: EpisodeStreamPick.Picker(forceRefresh = lastError != null)
}
