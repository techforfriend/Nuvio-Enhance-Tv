package com.nuvio.tv.core.stream

import android.content.Context
import android.os.SystemClock
import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.core.player.ContinuationTarget
import com.nuvio.tv.core.player.DecodeCapability
import com.nuvio.tv.core.player.PrefetchedSelection
import com.nuvio.tv.core.player.SelectionSnapshot
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.PreferredSourceDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single implementation of "work out what the stream screen will auto-play,
 * and get it ready", run during a prefetch rather than after the Play press.
 *
 * Call sites (all through StreamPrefetchCache.prefetch's rank hook):
 *   - HomeViewModel: the front Continue Watching entry (S4a-3) and any
 *     focus-dwelled CW card (S4a-3b).
 *   - MetaDetailsViewModel: the hero button's target -- the movie itself, or
 *     a series' resume/next episode (S4a) -- and a focused episode (S4a-2).
 *   - PlayerRuntimeControllerMetadata: the binge lookahead for the next
 *     episode during playback (S5).
 *
 * It exists as one class rather than a copy per ViewModel for the same reason
 * AutoPlaySelection does. A second copy of "predict what the stream screen will
 * pick" can drift from the first silently. One implementation cannot diverge
 * from itself.
 *
 * Measured on the Continue Watching path (25 Jul 2026, Xiaomi S905X5M, 4K DV,
 * auto-play "Best quality", 101 streams): ranking went from 374 ms at the
 * press to 0.4 ms (R2, nt24). The cost did not shrink; it moved off the
 * critical path into the seconds the user spends looking at a screen.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
@Singleton
class PrefetchSelectionSupplier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val preferredSourceDataStore: PreferredSourceDataStore,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    private val failedReleases: FailedReleases
) {

    private val _uiSignals = MutableStateFlow<Map<String, SourcePrefetchSignal>>(emptyMap())

    /**
     * Hero-target source lines keyed by uiKey: READY with the winner's facts,
     * or EMPTY when nothing would play. Keyed so each details page on the back
     * stack keeps its own signal; only the [MAX_UI_SIGNALS] most recently
     * published keys are kept.
     */
    val uiSignals: StateFlow<Map<String, SourcePrefetchSignal>> = _uiSignals.asStateFlow()

    /**
     * Recency order lives here, not in [_uiSignals]: StateFlow drops a value
     * equal to the current one, so an identical republish could not refresh a
     * key's position there. Guarded by itself; oldest key first.
     */
    private val recentSignals = LinkedHashMap<String, SourcePrefetchSignal>()

    private fun publish(signal: SourcePrefetchSignal) {
        synchronized(recentSignals) {
            recentSignals.remove(signal.uiKey)
            recentSignals[signal.uiKey] = signal
            while (recentSignals.size > MAX_UI_SIGNALS) {
                recentSignals.remove(recentSignals.keys.first())
            }
            _uiSignals.value = recentSignals.toMap()
        }
    }

    /**
     * Rank [groups] as the stream screen would, then (when [prewarm]) prewarm the
     * winner's direct URL. Addon streams arrive already resolved, so the link is ready
     * as soon as it is ranked.
     *
     * Returns the winner plus the settings snapshot it was chosen under, for
     * StreamPrefetchCache to hold and PrefetchedSelectionGate to verify at the
     * press. Null when nothing would be auto-played.
     *
     * [prewarm] false (focus-dwell callers, upstream-sync T30): rank only. Focus is
     * not consent, and the prewarm GET on a debrid resolve URL asks the service for
     * that file; the press warms the link itself (StreamScreen, S1g).
     *
     * [runtimeMinutes] / [episode] are the target's as the caller already knows them (Spec 51
     * D8: nothing waits for one), for the stream list limit; [exemptBingeGroup] is the player's
     * binge exemption (P5), null elsewhere. [film]: the target is a film
     * ([ContentIdentity.isFilmType]), so the film size floors apply as at the press (Spec 65 D4);
     * the identity guard itself runs at the press, through PrefetchedSelectionGate.
     */
    suspend fun rankAndPrewarm(
        groups: List<AddonStreams>,
        contentId: String?,
        runtimeMinutes: Int?,
        episode: Boolean,
        film: Boolean,
        bingeOverride: String? = null,
        exemptBingeGroup: String? = null,
        uiKey: String? = null,
        prewarm: Boolean = true,
        continuation: ContinuationTarget? = null
    ): PrefetchedSelection? {
        val rankT0 = SystemClock.elapsedRealtime()
        val selection = rank(groups, contentId, bingeOverride, runtimeMinutes, episode, film, exemptBingeGroup, continuation)
        // §2.7(a): the cache's total_ms covers this call AND any prewarm.
        // Logged here so the rank component needs no subtraction.
        val winnerLabel = if (selection == null) "none" else "yes"
        android.util.Log.i(
            TAG,
            "PREFETCH rank_only winner=$winnerLabel " +
                "ms=${SystemClock.elapsedRealtime() - rankT0}"
        )
        if (selection == null) {
            if (uiKey != null) publish(SourcePrefetchSignal(uiKey, SourcePrefetchPhase.EMPTY, null))
            return null
        }
        if (prewarm) {
            // 0.8.3-nt2: warm the exact URL the press will open for a direct-play
            // stream. StreamScreenViewModel.playbackInfoFor opens
            // winner.getStreamUrl() verbatim, and the datasource's head/tail
            // windows are keyed by exact Uri equality - so warming this precise url
            // lets consumeHead/peekTail hit on press and skips BOTH cold
            // connections (the head probe and the Cues tail read).
            selection.winner.getStreamUrl()?.takeIf { it.isNotBlank() }?.let { directUrl ->
                com.nuvio.tv.core.player.PlayerPlaybackNetworking
                    .prewarmPlaybackConnection(
                        directUrl,
                        selection.winner.behaviorHints?.proxyHeaders?.request
                    )
                android.util.Log.i(TAG, "PREFETCH prewarm direct")
            }
        } else {
            android.util.Log.i(TAG, "PREFETCH prewarm skipped (focus)")
        }
        if (uiKey != null) {
            publish(
                SourcePrefetchSignal(
                    uiKey,
                    SourcePrefetchPhase.READY,
                    ReleaseFactsParser.factsFor(selection.winner),
                    streamBadgePresentation.badgesFor(selection.winner),
                    winner = selection.winner
                )
            )
        }
        return selection
    }

    /**
     * Every input is read HERE and snapshotted; the stream screen compares its
     * own snapshot before consuming the winner, so a setting that moved in
     * between discards the cached pick rather than acting on a stale one.
     *
     * The ordering step is not optional: StreamQualityRank.rank is a stable
     * sort, so incoming order decides ties. Ranking the raw scrape output would
     * break ties differently from the presented list.
     */
    private suspend fun rank(
        groups: List<AddonStreams>,
        contentId: String?,
        bingeOverride: String?,
        runtimeMinutes: Int?,
        episode: Boolean,
        film: Boolean,
        exemptBingeGroup: String?,
        continuation: ContinuationTarget?
    ): PrefetchedSelection? {
        val settings = playerSettingsDataStore.playerSettings.first()
        // Spec 65 P6: the show's preferred source, as the press reads it (the same store, the same
        // id). It plays in MANUAL mode too (D20), so only MANUAL without one ranks nothing.
        val preferred = contentId?.let { preferredSourceDataStore.get(it) }
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && preferred == null) return null

        val installedAddonOrder = addonRepository.getInstalledAddons()
            .first()
            .enabledAddons()
            .map { it.displayName }

        // nt10: a caller that KNOWS the group the press will prefer supplies
        // it. The binge lookahead does -- it is running inside the playback
        // whose group the next-episode path will match against -- and
        // without it the two disagree whenever Prefer Binge Group is on and
        // Reuse Binge Group is off. The 27 Jul capture measured that
        // disagreement: the lookahead picked a 7.5 GB stream on
        // nexus-198, the press selected a 2.1 GB one on nexus-170, so the
        // prewarm had warmed the wrong node (2,093 ms cold probe).
        // The cache-backed derivation stays the default for the three
        // callers with no playback in progress.
        val preferredBingeGroup = bingeOverride
            ?: AutoPlaySelection.persistedBingeGroup(settings, contentId, bingeGroupCacheDataStore::get)

        val ordered = StreamAutoPlaySelector.orderAddonStreams(groups, installedAddonOrder)
        val allStreams = ordered.flatMap { it.streams }

        // Spec 51 P5: the limit and link as of now; the press computes its own set over its
        // own list, and the gate hits only when the two agree.
        val hiddenKeys = hiddenStreamKeys(
            streams = allStreams,
            limit = playerSettingsDataStore.streamLimitInputs.first(),
            transport = { NetworkTransportReader.current(context) },
            runtimeMinutes = runtimeMinutes,
            episode = episode,
            exemptBingeGroup = exemptBingeGroup
        )
        val inputs = AutoPlaySelection.Inputs.fromSettings(
            settings = settings,
            installedAddonOrder = installedAddonOrder,
            preferredBingeGroup = preferredBingeGroup,
            failures = failedReleases.snapshot(),
            hiddenKeys = hiddenKeys,
            filmSizeFloors = film,
            continuation = continuation,
            preferred = preferred,
            // Spec 65 P8: the device's decoders, as the press reads them (one probe per process).
            softwareOnly = DecodeCapability.softwareOnly()
        )
        val winner = AutoPlaySelection.select(
            streams = allStreams,
            inputs = inputs
        ) ?: return null

        return PrefetchedSelection(
            snapshot = SelectionSnapshot(
                inputs = inputs,
                installedAddonOrder = installedAddonOrder
            ),
            winner = winner,
            candidates = allStreams
        )
    }

    private companion object {
        // Logged under StreamPrefetch rather than a per-ViewModel tag: these are
        // prefetch-phase events, they belong beside PREFETCH rank in a capture,
        // and it keeps the existing measurement harnesses' tag filter unchanged.
        const val TAG = "StreamPrefetch"

        /**
         * Details pages kept before the oldest key is dropped. Signals are tiny, so
         * the cap sits well above any plausible back stack: an evicted page shows
         * SEARCHING forever on Back. Residual: a stack deeper than 16 still does.
         */
        const val MAX_UI_SIGNALS = 16
    }
}
