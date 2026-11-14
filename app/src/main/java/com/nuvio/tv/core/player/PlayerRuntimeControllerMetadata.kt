package com.nuvio.tv.core.player

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PlaceholderStreamPolicy.isShortPlaceholderDuration
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.core.util.parseRuntimeMinutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bounded wait on [PlayerRuntimeController.metaFetchJob] for readers that need meta fields. */
internal const val META_FETCH_JOIN_TIMEOUT_MS = 3_000L

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return

    metaFetchJob = scope.launch {
        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                applyMetaDetails(result.data)
            }
            is NetworkResult.Error -> {
            }
            NetworkResult.Loading -> {
            }
        }
    }
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    metaGenres = meta.genres
    metaCountry = meta.country
    metaImdbId = meta.imdbId
    // Fill in content language from meta if not provided via navigation args.
    if (contentLanguage == null) {
        contentLanguage = meta.resolveContentLanguage()
    }
    val description = resolveDescription(meta)

    metaRuntime = meta.runtime
    val runtime = playingRuntimeMinutes(metaVideos, currentVideoId, currentSeason, currentEpisode, metaRuntime)

    _uiState.update { state ->
        state.copy(
            description = description ?: state.description,
            castMembers = if (meta.castMembers.isNotEmpty()) meta.castMembers else state.castMembers,
            genres = meta.genres.filter { it.isNotBlank() },
            imdbRating = meta.imdbRating,
            tmdbRating = meta.tmdbRating,
            // The route's runtime is for this same item: keep it when the meta has none.
            runtimeMinutes = runtime ?: state.runtimeMinutes
        )
    }
    recomputeNextEpisode(resetVisibility = false)
}

/**
 * The runtime the player's meta line shows (26/B5): the playing item's own, found by [videoId]
 * else by [season]/[episode]; without an episode, the movie's [metaRuntime] text. Null when
 * unknown or 0 (an episode never borrows the series runtime).
 */
internal fun playingRuntimeMinutes(
    videos: List<Video>,
    videoId: String?,
    season: Int?,
    episode: Int?,
    metaRuntime: String?
): Int? {
    val video = playingVideo(videos, videoId, season, episode)
    video?.runtime?.takeIf { it > 0 }?.let { return it }
    return if (season == null && episode == null) parseRuntimeMinutes(metaRuntime)?.takeIf { it > 0 } else null
}

/**
 * The runtime the Spec 35 mismatch check judges against: [playingRuntimeMinutes], else (an
 * episode without its own) the series runtime text. Only this check borrows the series runtime;
 * the meta line never does.
 */
internal fun runtimeMismatchCheckMinutes(
    videos: List<Video>,
    videoId: String?,
    season: Int?,
    episode: Int?,
    metaRuntime: String?
): Int? = playingVideo(videos, videoId, season, episode)?.runtime?.takeIf { it > 0 }
    ?: parseRuntimeMinutes(metaRuntime)?.takeIf { it > 0 }

/** The playing item in the meta's list: by [videoId] first ("other" items), else by season/episode. */
private fun playingVideo(videos: List<Video>, videoId: String?, season: Int?, episode: Int?): Video? =
    videoId?.let { id -> videos.firstOrNull { it.id == id } }
        ?: if (season != null && episode != null) {
            videos.firstOrNull { it.season == season && it.episode == episode }
        } else {
            null
        }

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    if (type in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.season == currentSeason && video.episode == currentEpisode
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }

    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = playingVideo(metaVideos, currentVideoId, currentSeason, currentEpisode)?.overview
    // The new item's own runtime: the last one's would be wrong, unknown shows none.
    val runtime = playingRuntimeMinutes(metaVideos, currentVideoId, currentSeason, currentEpisode, metaRuntime)
    _uiState.update {
        it.copy(
            description = overview?.takeIf { text -> text.isNotBlank() } ?: it.description,
            runtimeMinutes = runtime
        )
    }

    // Push episode metadata to the MediaSession so Google Home shows the new episode.
    updateMediaSessionMetadata()
}

/** Also recomputes the previous episode (Spec 45): every call site that moves the next one moves it. */
internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    recomputePreviousEpisode()
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv", "other")) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    if (normalizedType == "other") {
        val currentId = currentVideoId
        val idx = if (currentId != null) metaVideos.indexOfFirst { it.id == currentId } else -1
        val resolvedNext = if (idx >= 0 && idx < metaVideos.size - 1) metaVideos[idx + 1] else null
        nextEpisodeVideo = resolvedNext
        if (resolvedNext == null) {
            clearNextEpisodeAndCancelPostPlay()
            return
        }
        val nextInfo = NextEpisodeInfo(
            videoId = resolvedNext.id,
            season = resolvedNext.season ?: 1,
            episode = resolvedNext.episode ?: (idx + 2),
            title = resolvedNext.title,
            thumbnail = resolvedNext.thumbnail,
            overview = resolvedNext.overview,
            released = resolvedNext.released,
            runtimeMinutes = resolvedNext.runtime?.takeIf { it > 0 },
            hasAired = true,
            unairedMessage = null,
            isOtherType = true
        )
        applyRecomputedNextEpisode(nextInfo, resetVisibility)
        return
    }

    val season = currentSeason
    val episode = currentEpisode
    if (season == null || episode == null) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val resolvedNext = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = metaVideos,
        currentSeason = season,
        currentEpisode = episode
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val aired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    // T31 (P42, NM 322840264): an episode the addon marks unavailable is not playable either
    // (no card, prompt or auto-play), the rule external players already follow.
    // NextEpisodeInfo.hasAired is the field every next-episode gate reads (see its KDoc).
    val playable = ExternalAutoNextPolicy.isPlayableNextEpisode(resolvedNext.available, aired)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: return,
        episode = resolvedNext.episode ?: return,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        runtimeMinutes = resolvedNext.runtime?.takeIf { it > 0 },
        hasAired = playable,
        unairedMessage = when {
            playable -> null
            !aired -> context.getString(com.nuvio.tv.R.string.next_episode_not_aired_yet)
            else -> context.getString(com.nuvio.tv.R.string.episodes_unavailable)
        }
    )
    applyRecomputedNextEpisode(nextInfo, resetVisibility)
}

/**
 * The episode before the playing one (Spec 45): "other" items by list order, series by
 * [PlayerNextEpisodeRules.resolvePreviousEpisode]; playable under the same rule as Next (aired and not
 * marked unavailable). Null for movies, the first episode and specials.
 */
private fun PlayerRuntimeController.recomputePreviousEpisode() {
    val normalizedType = contentType?.lowercase()
    val otherIndex = currentVideoId?.let { id -> metaVideos.indexOfFirst { it.id == id } } ?: -1
    val resolved = when (normalizedType) {
        "other" -> if (otherIndex > 0) metaVideos[otherIndex - 1] else null
        "series", "tv" -> {
            val season = currentSeason
            val episode = currentEpisode
            if (season == null || episode == null) null
            else PlayerNextEpisodeRules.resolvePreviousEpisode(metaVideos, season, episode)
        }
        else -> null
    }
    previousEpisodeVideo = resolved
    val info = resolved?.let { video ->
        val isOther = normalizedType == "other"
        NextEpisodeInfo(
            videoId = video.id,
            season = video.season ?: if (isOther) 1 else return@let null,
            // An "other" item without a number: its 1-based list position (Next uses idx + 2).
            episode = video.episode ?: if (isOther) otherIndex else return@let null,
            title = video.title,
            thumbnail = video.thumbnail,
            overview = video.overview,
            released = video.released,
            runtimeMinutes = video.runtime?.takeIf { it > 0 },
            hasAired = isOther || ExternalAutoNextPolicy.isPlayableNextEpisode(
                video.available,
                PlayerNextEpisodeRules.hasEpisodeAired(video.released)
            ),
            unairedMessage = null,
            isOtherType = isOther
        )
    }
    if (info == null) previousEpisodeVideo = null
    _uiState.update { it.copy(previousEpisode = info) }
}

private fun PlayerRuntimeController.clearNextEpisodeAndCancelPostPlay() {
    val mode = _uiState.value.postPlayMode
    if (mode != null) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update {
        it.copy(
            nextEpisode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
}

private fun PlayerRuntimeController.applyRecomputedNextEpisode(
    nextInfo: NextEpisodeInfo,
    resetVisibility: Boolean,
) {
    val previousState = _uiState.value
    val previousNextEpisode = previousState.nextEpisode
    val previousMode = previousState.postPlayMode
    if (previousMode is PostPlayMode.StillWatching &&
        previousNextEpisode != null &&
        previousNextEpisode.videoId != nextInfo.videoId
    ) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        val updatedMode = if (shouldResetVisibility) {
            null
        } else {
            state.postPlayMode?.copyWithNextEpisode(nextInfo)
        }
        state.copy(
            nextEpisode = nextInfo,
            postPlayMode = updatedMode,
            postPlayDismissedForCurrentEpisode =
                if (shouldResetVisibility && !state.postPlayDismissedForCurrentEpisode) false
                else state.postPlayDismissedForCurrentEpisode,
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayOverlayState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

/**
 * S5 binge lookahead: start the next episode's scrape before it is pressed.
 *
 * Called from [evaluatePostPlayOverlayVisibility], which the progress loop
 * already invokes on every tick for both engines with the position and
 * duration this needs. Sharing that call site is deliberate -- a second
 * per-tick hook would be a second thing to keep in step.
 *
 * Why the trigger is near the END of the episode and not at its start:
 * StreamPrefetchCache entries carry addon-resolved links and expire after
 * five minutes. A prefetch fired when playback began would
 * be forty minutes stale by the time the next-episode button is pressed.
 *
 * Six minutes exceeds that five-minute TTL by design (Paul's call, 26 Jul).
 * The first fire expires with about a minute of episode left, and because
 * [StreamPrefetchCache.prefetch] no-ops on a fresh or identical in-flight key,
 * the very next tick after expiry re-fires it for free -- no re-arm state to
 * hold. The second entry lands with roughly fifty seconds to spare and stays
 * fresh for about four minutes past the episode end, which also covers
 * dwelling on the post-play card. Cost is two scrape cycles per episode.
 *
 * S5 part 3: a ranker IS now supplied, so the lookahead also ranks the
 * winner and prewarms its URL. The winner's URL is what REVEALS THE CDN HOST:
 * the 26 Jul nt3 capture caught the next episode on nexus-170 while every
 * pooled connection was to nexus-196, so the probe paid a full cold connect
 * including fresh DNS (1,480 ms to headers). OkHttp pools per host: warming
 * the wrong node is worth nothing.
 *
 * Runway note: connections idle out of the pool after three minutes, so the
 * six-minute fire warms a node that will have gone cold by the press. The TTL
 * re-fire about a minute before the end is the one that lands, and it
 * re-resolves against the resolver's own 15-minute link cache, so the second
 * pass is cheap.
 */
internal fun PlayerRuntimeController.maybePrefetchNextEpisodeForBinge(
    positionMs: Long,
    durationMs: Long
) {
    if (!hasRenderedFirstFrame) return
    val type = contentType ?: return
    val nextVideo = nextEpisodeVideo ?: return
    if (_uiState.value.nextEpisode?.hasAired != true) return

    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    if (effectiveDuration <= 0L) return
    val remainingMs = effectiveDuration - positionMs
    if (remainingMs <= 0L || remainingMs > BINGE_LOOKAHEAD_TRIGGER_MS) return

    com.nuvio.tv.core.stream.StreamPrefetchCache.prefetch(
        repository = streamRepository,
        type = type,
        videoId = nextVideo.id,
        season = nextVideo.season,
        episode = nextVideo.episode,
        title = nextVideo.title,
        source = "binge_lookahead",
        background = true,
        rank = { groups ->
            // Mirror what the press SETTLES on, which is not its first
            // attempt. PlayerRuntimeControllerStreams tries
            // tryBingeGroupOnly opportunistically while the scrape is still
            // arriving, but when nothing matches it falls through on timeout
            // to trySelectStream -- a full select carrying the playing
            // source (Spec 65 P7 continuation, else currentStreamBingeGroup)
            // as a PREFERENCE, not a requirement.
            //
            // nt10 replicated only the early attempt and stopped there. The
            // 27 Jul capture caught the consequence immediately: no stream in
            // the next episode shared the current binge group, the lookahead
            // returned winner=none, and the transition paid a full 1,743 ms
            // resolve and a 2,093 ms cold probe -- worse than the
            // quality-ranked guess it replaced. The press meanwhile settled
            // in 343 ms with selectCalls=2, which is the fall-through in the
            // log. The fall-through is the common path (with the continuation
            // its first step can hit, but that select is the same one), so
            // the lookahead predicts it: one full select with the same inputs.
            // A lookahead entry carries the continuation, so a Stream-screen
            // open of that episode never reuses it (inputs-changed, live rank).
            val preferBinge = playerSettingsDataStore.playerSettings.first()
                .streamAutoPlayPreferBingeGroupForNextEpisode
            val lookaheadBingeGroup = currentStreamBingeGroup?.takeIf { preferBinge }
            // Spec 65 P7: the press keeps the playing source through ContinuationTarget; predict it
            // the same way.
            val lookaheadContinuation = if (preferBinge) ContinuationTarget.of(currentPlayingStream) else null
            prefetchSelectionSupplier.rankAndPrewarm(
                groups = groups,
                contentId = contentId,
                // Spec 51 P5: the runtime and binge exemption the press uses for the same episode.
                runtimeMinutes = nextVideo.runtime,
                episode = true,
                film = false,
                bingeOverride = lookaheadBingeGroup,
                exemptBingeGroup = limitExemptBingeGroup(awaitLimitInputs()),
                continuation = lookaheadContinuation
            )
        }
    )
}

/** S5: how much of the episode may remain when the lookahead prefetch fires. */
private const val BINGE_LOOKAHEAD_TRIGGER_MS = 6L * 60L * 1000L

internal fun PlayerRuntimeController.evaluatePostPlayOverlayVisibility(positionMs: Long, durationMs: Long) {
    maybePrefetchNextEpisodeForBinge(positionMs, durationMs)
    if (!hasRenderedFirstFrame) return
    // Short debrid/error clips must never arm next-episode auto-play (see #2819).
    val effectiveDurationEarly = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    if (isShortPlaceholderDuration(effectiveDurationEarly)) return
    if (_uiState.value.error != null) return

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.postPlayMode != null) {
            _uiState.update { it.copy(postPlayMode = null) }
        }
        return
    }
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return
    // A Previous press is finding a source (Spec 45): the next-episode card must not start Next under it.
    if (state.previousEpisodeSearch != null) return

    val effectiveDuration = effectiveDurationEarly
    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = effectiveSkipIntervals(),
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (!shouldShow) return

    if (_uiState.value.postPlayDismissedForCurrentEpisode) return

    val shouldEnterStillWatching = shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
        nextEpisodeHasAired = state.nextEpisode.hasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = stillWatchingEpisodeThresholdSetting,
    )

    if (shouldEnterStillWatching) {
        enterStillWatchingPromptMode()
    } else {
        _uiState.update {
            it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = state.nextEpisode))
        }
        if (state.nextEpisode.hasAired && streamAutoPlayNextEpisodeEnabledSetting) {
            playNextEpisode()
        }
    }
}

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank()) return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    val skipIntervals = effectiveSkipIntervals()
    if (skipIntervals.isEmpty()) {
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
        }
        return
    }

    // Don't evaluate skip intervals until player settings are loaded from DataStore.
    // Without this, autoSkipSegmentTypes is empty on first iterations, causing the
    // skip button to appear instead of auto-skipping.
    if (!playerSettingsInitialized) return

    // An interval open to the end of the file is over once it has been skipped to the end.
    val durationMs = currentPlaybackDurationMs()
    val active = findActiveSkipInterval(skipIntervals, positionMs)?.takeUnless {
        it.endTime == SkipInterval.OPEN_END && durationMs > 0L && positionMs >= durationMs - 1_000L
    }
    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        if (currentActive == null || active.type != currentActive.type || active.startTime != currentActive.startTime) {
            _uiState.update { it.copy(activeSkipInterval = active, skipIntervalDismissed = false) }
        }
        val segmentType = AutoSkipSegmentType.fromSkipIntervalType(active.type)
        val activeKey = active.autoSkipKey()
        if (
            segmentType != null &&
            segmentType in autoSkipSegmentTypes &&
            activeKey !in autoSkippedIntervalKeys
        ) {
            autoSkippedIntervalKeys.add(activeKey)
            skipInterval(active)
        }
    } else if (currentActive != null) {
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}

private fun SkipInterval.autoSkipKey(): String =
    "$provider:$type:$startTime:$endTime"

internal fun PlayerRuntimeController.tryShowParentalGuide() {
    val state = _uiState.value
    if (!state.parentalGuideHasShown && state.parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        _uiState.update { it.copy(showParentalGuide = true, parentalGuideHasShown = true) }
    }
}

internal fun PlayerRuntimeController.fetchParentalGuide(id: String?) {
    if (!parentalGuideEnabled) return
    if (id.isNullOrBlank()) return

    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return

    scope.launch {
        val guide = parentalGuideRepository.getParentalGuide(imdbId) ?: return@launch

        val warnings = guide.toParentalWarnings(context::getString).take(5)

        _uiState.update {
            it.copy(
                parentalWarnings = warnings,
                showParentalGuide = false,
                parentalGuideHasShown = false
            )
        }

        if (_uiState.value.isPlaying) {
            tryShowParentalGuide()
        }
    }
}
