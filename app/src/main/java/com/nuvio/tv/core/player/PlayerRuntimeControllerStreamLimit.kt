package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.core.stream.StreamLimitInputs
import com.nuvio.tv.core.stream.HiddenStream
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.StreamFacetMemo
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.deriveStreamList
import com.nuvio.tv.core.stream.hiddenStreamVerdicts
import com.nuvio.tv.core.stream.keptIn
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.core.stream.sourceChipAddonNames
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Spec 51 L2b: the stream list limits in the player (Spec 58 F3 adds the resolution limit; "over
 * the limit" below means over either). The Sources and episode-streams panels derive
 * their lists as the Stream screen does (StreamListView), and every automatic pick (Next /
 * Previous press, binge lookahead, dead-source failover) skips the same hidden set. The player
 * adds the playing stream: never hidden, and when it is itself over the limit with Prefer Binge
 * Group on, its binge group is exempt everywhere (r1), so what Next may pick is what the lists show.
 * The compositions are pure functions of PlayerUiState; the controller only supplies the held
 * limit, the link and whether the playing item is an episode.
 */

/**
 * The playing stream under the limit: its [key] in the playing item's list, whether it is itself
 * [overLimit], and the binge group it exempts (r1). Empty when the playing stream is not in the
 * list (R4-4: no key and no exemption, the protective side).
 */
internal data class PlayingStreamLimit(
    val key: String? = null,
    val overLimit: Boolean = false,
    val exemptBingeGroup: String? = null
)

/**
 * The playing stream (as of [state]) in [playingItemStreams], the playing item's source list,
 * under [limit]: its own verdict, which the playing-stream rule does not soften; exempt only with
 * Prefer Binge Group on. Judged within the whole list, as the lists judge it (Spec 59 N-10: a row
 * from an untested domain takes its debrid service's table from the other rows).
 */
internal fun playingStreamLimit(
    playingItemStreams: List<Stream>,
    state: PlayerUiState,
    limit: StreamLimitInputs?,
    transport: () -> NetworkTransport,
    episode: Boolean
): PlayingStreamLimit {
    val playing = playingItemStreams.getOrNull(playingStreamIndex(playingItemStreams, state)) ?: return PlayingStreamLimit()
    val key = playing.limitKey()
    val over = key in hiddenStreamVerdicts(playingItemStreams, limit, transport, state.runtimeMinutes, episode)
    return PlayingStreamLimit(
        key = key,
        overLimit = over,
        exemptBingeGroup = playing.behaviorHints?.bingeGroup
            ?.takeIf { over && state.streamAutoPlayPreferBingeGroupForNextEpisode }
    )
}

/** The playing stream's index in [streams] under [state], or -1 (also the failure store's lookup, Spec 65 P5). */
internal fun playingStreamIndex(streams: List<Stream>, state: PlayerUiState): Int = findCurrentStreamIndex(
    streams = streams,
    currentStreamInfoHash = state.currentStreamInfoHash,
    currentStreamFileIdx = state.currentStreamFileIdx,
    currentStreamAddonName = state.currentStreamAddonName,
    currentStreamUrl = state.currentStreamUrl
)

/** The Sources list's hidden set (P4): [state]'s list under its runtime, never [playing] nor its exempt group. */
internal fun sourceListHiddenVerdicts(
    state: PlayerUiState,
    playing: PlayingStreamLimit,
    limit: StreamLimitInputs?,
    transport: () -> NetworkTransport,
    episode: Boolean
): Map<String, HiddenStream> = hiddenStreamVerdicts(
    streams = state.sourceAllStreams,
    limit = limit,
    transport = transport,
    runtimeMinutes = state.runtimeMinutes,
    episode = episode,
    playingKey = playing.key,
    exemptBingeGroup = playing.exemptBingeGroup
)

/**
 * The episode-streams list's hidden set (P4): its target's runtime and [playing]'s exemption (R4-3,
 * so the panel lists what Next may pick). When the list is the playing episode's own, its playing
 * stream is never hidden either (found in that list).
 */
internal fun episodeListHiddenVerdicts(
    state: PlayerUiState,
    playing: PlayingStreamLimit,
    limit: StreamLimitInputs?,
    transport: () -> NetworkTransport
): Map<String, HiddenStream> {
    val streams = state.episodeAllStreams
    if (streams.isEmpty()) return emptyMap()
    val playingEpisodesList = state.episodeStreamsForVideoId != null && state.episodeStreamsForVideoId == state.currentVideoId
    return hiddenStreamVerdicts(
        streams = streams,
        limit = limit,
        transport = transport,
        runtimeMinutes = state.episodeStreamsRuntimeMinutes,
        episode = true,
        playingKey = if (playingEpisodesList) streams.getOrNull(playingStreamIndex(streams, state))?.limitKey() else null,
        exemptBingeGroup = playing.exemptBingeGroup
    )
}

/**
 * What the player's lists re-derive on besides their own streams, pick and chips, and the limit
 * (M3, R4-1): the playing item's runtime, the playing stream's identity (a source or episode
 * switch moves the playing key and the exemption), and Prefer Binge Group (the exemption).
 */
internal data class StreamLimitDeriveKey(
    val runtimeMinutes: Int?,
    val videoId: String?,
    val streamUrl: String?,
    val streamInfoHash: String?,
    val streamFileIdx: Int?,
    val streamAddonName: String?,
    val preferBingeGroup: Boolean
)

internal fun streamLimitDeriveKey(state: PlayerUiState): StreamLimitDeriveKey = StreamLimitDeriveKey(
    runtimeMinutes = state.runtimeMinutes,
    videoId = state.currentVideoId,
    streamUrl = state.currentStreamUrl,
    streamInfoHash = state.currentStreamInfoHash,
    streamFileIdx = state.currentStreamFileIdx,
    streamAddonName = state.currentStreamAddonName,
    preferBingeGroup = state.streamAutoPlayPreferBingeGroupForNextEpisode
)

/** Holds the limit and re-derives both lists on it and on every [StreamLimitDeriveKey] change. */
internal fun PlayerRuntimeController.observeStreamLimit() {
    scope.launch {
        combine(
            playerSettingsDataStore.streamLimitInputs,
            _uiState.map(::streamLimitDeriveKey).distinctUntilChanged()
        ) { limit, _ -> limit }.collect { limit ->
            streamLimitInputs = limit
            rederiveStreamLists()
        }
    }
}

/**
 * The limit for a suspend caller (R4-9, R5-4): the held value, else one `.first()` read; never a
 * default. Non-suspend readers (the failover) read [PlayerRuntimeController.streamLimitInputs]
 * itself, safe because the list they walk is only filled by a load that awaited this.
 */
internal suspend fun PlayerRuntimeController.awaitLimitInputs(): StreamLimitInputs {
    streamLimitInputs?.let { return it }
    val read = playerSettingsDataStore.streamLimitInputs.first()
    // The collector may have filled it meanwhile, with the newer value.
    return streamLimitInputs ?: read.also { streamLimitInputs = it }
}

/** The link, read only when the limit needs it (hiddenStreamVerdicts: NETWORK_TEST with a table). */
internal val PlayerRuntimeController.transportNow: () -> NetworkTransport
    get() = { NetworkTransportReader.current(context) }

/** The playing item is an episode (the estimate's D5 size rule). */
private fun PlayerRuntimeController.playingItemIsEpisode(): Boolean = currentSourceStreamsRequest()?.episode != null

/** The playing stream as of [state], in its item's source list (empty when the list is another item's). */
private fun PlayerRuntimeController.playingStreamLimitNow(
    state: PlayerUiState,
    limit: StreamLimitInputs? = streamLimitInputs
): PlayingStreamLimit = playingStreamLimit(sourceStreamsForPlayingItem(state), state, limit, transportNow, playingItemIsEpisode())

/**
 * The binge group exempt from [limit] now (r1): the one helper the binge lookahead and the Next /
 * Previous press both call, so the stream the lookahead prewarms is the one the press may pick
 * (the nt10 rule). The lookahead calls it from its IO rank with plain controller reads, as its
 * binge-group read already does; a stale read costs only the prewarm.
 */
internal fun PlayerRuntimeController.limitExemptBingeGroup(limit: StreamLimitInputs?): String? =
    playingStreamLimitNow(_uiState.value, limit).exemptBingeGroup

/** The Sources list's hidden keys as of [state]: the dead-source failover's set, computed at use (R4-1). */
internal fun PlayerRuntimeController.sourceHiddenKeysNow(state: PlayerUiState): Set<String> =
    sourceListHiddenVerdicts(state, playingStreamLimitNow(state), streamLimitInputs, transportNow, playingItemIsEpisode()).keys

/**
 * Applies [transform] and derives both lists after it (M3): every update that changes a list's
 * streams, its target or the playing item goes through here, so the episode list's exemption
 * follows a Sources load too. Logs `LIMIT playing_over_limit` once per stream (R4-13, D8's cost)
 * after the update, which may rerun.
 */
internal fun PlayerRuntimeController.updateAndDeriveStreamLists(transform: (PlayerUiState) -> PlayerUiState) {
    var playing = PlayingStreamLimit()
    _uiState.update { current ->
        val state = transform(current)
        val limit = streamLimitInputs
        playing = playingStreamLimitNow(state, limit)
        state.withSourceList(sourceListHiddenVerdicts(state, playing, limit, transportNow, playingItemIsEpisode()))
            .withEpisodeList(episodeListHiddenVerdicts(state, playing, limit, transportNow))
    }
    notePlayingOverLimit(playing)
}

/** Re-derives both lists under the held limit, the runtime and the playing stream now. Main. */
internal fun PlayerRuntimeController.rederiveStreamLists() = updateAndDeriveStreamLists { it }

private fun PlayerRuntimeController.notePlayingOverLimit(playing: PlayingStreamLimit) {
    if (!playing.overLimit) {
        // Only a found stream within the limit clears it; a list without the playing stream says nothing.
        if (playing.key != null) playingOverLimitKey = null
        return
    }
    if (playingOverLimitKey == playing.key) return
    playingOverLimitKey = playing.key
    Log.i(PlayerRuntimeController.TAG, "LIMIT playing_over_limit exempt=${playing.exemptBingeGroup != null}")
}

/** A user's pick in the Sources panel; the hidden set stands (M7). */
internal fun PlayerRuntimeController.selectSourceList(selection: StreamListSelection) {
    _uiState.update { state ->
        if (state.sourceListSelection == selection) state
        else state.copy(sourceListSelection = selection).withSourceList(state.sourceListView.hiddenVerdicts)
    }
}

/** A user's pick in the episode-streams panel; the hidden set stands (M7). */
internal fun PlayerRuntimeController.selectEpisodeList(selection: StreamListSelection) {
    _uiState.update { state ->
        if (state.episodeListSelection == selection) state
        else state.copy(episodeListSelection = selection).withEpisodeList(state.episodeListView.hiddenVerdicts)
    }
}

/**
 * The show's preferred source (Spec 65 P6), held for both lists: its matches lead them. Read under
 * the content id the binge cache uses; a film's Sources list has no row menu, and nothing stored.
 */
internal fun PlayerRuntimeController.observePreferredSource() {
    val id = contentId ?: return
    scope.launch {
        preferredSourceDataStore.observe(id).collect { preferred ->
            updateAndDeriveStreamLists { it.copy(preferredSource = preferred) }
        }
    }
}

/** Prefers [stream]'s source for the show, or stops preferring one (null). */
internal fun PlayerRuntimeController.preferSource(stream: Stream?) {
    val id = contentId ?: return
    scope.launch { preferredSourceDataStore.set(id, stream?.let(PreferredSource::of)) }
}

/** The Sources panel's filter (Spec 65 P1); the hidden set stands. */
internal fun PlayerRuntimeController.filterSourceList(filter: FacetFilter) {
    _uiState.update { state ->
        if (state.sourceFacetFilter == filter) state
        else state.copy(sourceFacetFilter = filter).withSourceList(state.sourceListView.hiddenVerdicts)
    }
}

/** The episode-streams panel's filter (Spec 65 P1); the hidden set stands. */
internal fun PlayerRuntimeController.filterEpisodeList(filter: FacetFilter) {
    _uiState.update { state ->
        if (state.episodeFacetFilter == filter) state
        else state.copy(episodeFacetFilter = filter).withEpisodeList(state.episodeListView.hiddenVerdicts)
    }
}

/** [PlayerUiState.sourceAllStreams] derived under its pick, its filter, [hidden] and the addon list (P4). */
internal fun PlayerUiState.withSourceList(hidden: Map<String, HiddenStream>): PlayerUiState = copy(
    sourceListView = deriveStreamList(
        all = sourceAllStreams,
        selection = sourceListSelection,
        hidden = hidden,
        chipAddons = sourceChipAddonNames(sourceAvailableAddons, sourceChips),
        filter = sourceFacetFilter,
        facetsOf = StreamFacetMemo::valuesOf,
        preferred = preferredSource
    )
)

/** [chips] as the source chips, re-derived; a pick whose chip left the row drops to All (m3, n1). */
internal fun PlayerUiState.withSourceChips(chips: List<SourceChipItem>): PlayerUiState =
    copy(
        sourceChips = chips,
        sourceListSelection = sourceListSelection.keptIn(sourceChipAddonNames(sourceAvailableAddons, chips))
    ).withSourceList(sourceListView.hiddenVerdicts)

/** [PlayerUiState.episodeAllStreams] derived under its pick, its filter, [hidden] and the addon list (P4). */
internal fun PlayerUiState.withEpisodeList(hidden: Map<String, HiddenStream>): PlayerUiState = copy(
    episodeListView = deriveStreamList(
        all = episodeAllStreams,
        selection = episodeListSelection,
        hidden = hidden,
        chipAddons = sourceChipAddonNames(episodeAvailableAddons, episodeSourceChips),
        filter = episodeFacetFilter,
        facetsOf = StreamFacetMemo::valuesOf,
        preferred = preferredSource
    )
)

/** [chips] as the episode-streams chips, re-derived as [withSourceChips]. */
internal fun PlayerUiState.withEpisodeChips(chips: List<SourceChipItem>): PlayerUiState =
    copy(
        episodeSourceChips = chips,
        episodeListSelection = episodeListSelection.keptIn(sourceChipAddonNames(episodeAvailableAddons, chips))
    ).withEpisodeList(episodeListView.hiddenVerdicts)
