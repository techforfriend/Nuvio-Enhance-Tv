package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.core.stream.ContentIdentity
import com.nuvio.tv.core.stream.ContentIdentityGuard
import com.nuvio.tv.core.stream.FailureSnapshot
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream

/**
 * The single call site for auto-play selection.
 *
 * StreamScreenViewModel had two calls to selectAutoPlayStream, each passing
 * the arguments assembled from PlayerSettingsDataStore, AddonRepository and
 * BingeGroupCacheDataStore. They differed in
 * exactly one argument. The second sits inside
 * `if (directFlowActive && persistedBingeGroup != null)`, so its
 * `preferBingeGroupInSelection = true` is equivalent to the first's
 * `persistedBingeGroup != null` -- which means the flag can be derived from
 * [Inputs] and only [bingeGroupOnly] needs to be passed.
 *
 * Why this exists rather than a third copy of the argument list: ranking
 * during the prefetch has to predict what the stream screen will pick, and one
 * implementation cannot diverge from itself.
 *
 * Selection logic lives in StreamAutoPlaySelector and StreamQualityRank. This
 * assembles arguments and applies the candidate filters every auto-play path
 * shares: the failure store's exclusions and demotions (Spec 65 P5), the
 * uncached-row demotion (Spec 86 R7), the stream limit's hidden set, the identity
 * guard (Spec 65 P3) and the software-decode skip (Spec 65 P8), so manual picks
 * (which never come through here) are untouched.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object AutoPlaySelection {

    /**
     * Settings-derived selector inputs, snapshotted once per loadStreams().
     * Every field is already a snapshot in the caller: playerSettings comes
     * from a single .first(), installedAddonOrder from one installed-addons
     * read (the Stream screen's awaitInstalledAddons, the player's first()),
     * and preferredBingeGroup from a single BingeGroupCacheDataStore.get().
     */
    data class Inputs(
        val mode: StreamAutoPlayMode,
        val regexPattern: String,
        val source: StreamAutoPlaySource,
        val installedAddonNames: Set<String>,
        val selectedAddons: Set<String>,
        val preferredBingeGroup: String?,
        /**
         * The failure store as of this rank (Spec 65 P5, FailedReleases): excluded streams are
         * never auto-selected, demoted ones only when nothing else qualifies.
         */
        val failures: FailureSnapshot = FailureSnapshot.EMPTY,
        /**
         * The stream list limit's hidden set (Spec 51 P5, `hiddenStreamKeys` by `limitKey`) over
         * the candidates this rank sees; never auto-selected. No default: every caller computes
         * it, so no path through [select] can skip the limit.
         */
        val hiddenKeys: Set<String>,
        /**
         * What was asked for (Spec 65 P3): candidates [ContentIdentityGuard] rejects are never
         * auto-selected. Null = no identity check. Not part of a prefetch snapshot's match: the
         * gate checks the cached winner against the press's identity instead (S3).
         */
        val identity: ContentIdentity? = null,
        /** The content is a film: the film size floors apply (Spec 65 D4). The prefetch sets it alone. */
        val filmSizeFloors: Boolean = identity?.film == true,
        /**
         * The source that was playing (Spec 65 P7): replaces the binge-group match with
         * [StreamContinuation]. Set only by the player's episode press and its binge lookahead.
         */
        val continuation: ContinuationTarget? = null,
        /**
         * The source the viewer prefers for this show (Spec 65 P6, D20): its first match among the
         * candidates plays before [continuation] and the binge group, in every mode. Read from
         * PreferredSourceDataStore once per rank by every caller, the prefetch included.
         */
        val preferred: PreferredSource? = null,
        /**
         * The shapes this device decodes in software (Spec 65 P8, D15): in the first-stream and
         * regex steps such a stream plays only when no other candidate does (a recently failed
         * one aside). [DecodeCapability.softwareOnly] at every caller, the prefetch included.
         */
        val softwareOnly: Set<VideoShape> = emptySet()
    ) {
        companion object {
            /**
             * Inputs straight from the saved settings: the stream screen and the
             * prefetch ranking, which must predict the same winner. The player's
             * next-episode / source-switch paths build theirs field by field
             * because they apply per-play overrides.
             */
            fun fromSettings(
                settings: PlayerSettings,
                installedAddonOrder: List<String>,
                preferredBingeGroup: String?,
                failures: FailureSnapshot,
                hiddenKeys: Set<String>,
                identity: ContentIdentity? = null,
                filmSizeFloors: Boolean = identity?.film == true,
                continuation: ContinuationTarget? = null,
                preferred: PreferredSource? = null,
                softwareOnly: Set<VideoShape> = emptySet()
            ): Inputs = Inputs(
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedAddonOrder.toSet(),
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                preferredBingeGroup = preferredBingeGroup,
                failures = failures,
                hiddenKeys = hiddenKeys,
                identity = identity,
                filmSizeFloors = filmSizeFloors,
                continuation = continuation,
                preferred = preferred,
                softwareOnly = softwareOnly
            )
        }
    }

    /**
     * The binge group a fresh play prefers: the group cached for [contentId]
     * (via [cachedGroup], BingeGroupCacheDataStore.get), only when both Prefer
     * Binge Group and Reuse Binge Group are on. The cache is not read otherwise.
     */
    suspend fun persistedBingeGroup(
        settings: PlayerSettings,
        contentId: String?,
        cachedGroup: suspend (String) -> String?
    ): String? {
        if (!settings.streamAutoPlayPreferBingeGroupForNextEpisode ||
            !settings.streamAutoPlayReuseBingeGroup
        ) return null
        return contentId?.let { cachedGroup(it) }
    }

    /**
     * Selects the stream to auto-play, or null for the picker.
     *
     * @param bingeGroupOnly when true, a binge-group miss (with [Inputs.continuation], a miss of
     *   every continuation tier) returns null instead of falling back to the configured mode --
     *   the eager pre-timeout check. A match of [Inputs.preferred] counts as the binge match.
     */
    fun select(
        streams: List<Stream>,
        inputs: Inputs,
        bingeGroupOnly: Boolean = false
    ): Stream? {
        val candidates = candidates(streams, inputs)
        val failed = inputs.failures.demotedAmong(candidates)
        // Spec 86 R7 (Spec 84 A10): a row its debrid service does not hold yet plays the service's
        // "downloading" clip, so it goes behind the rest like a recently failed link, never out.
        val demoted = { stream: Stream -> failed?.contains(stream) == true || stream.isReportedUncached() }
        return StreamAutoPlaySelector.selectAutoPlayStream(
            streams = candidates,
            mode = inputs.mode,
            regexPattern = inputs.regexPattern,
            source = inputs.source,
            installedAddonNames = inputs.installedAddonNames,
            selectedAddons = inputs.selectedAddons,
            preferredBingeGroup = inputs.preferredBingeGroup,
            preferBingeGroupInSelection = inputs.preferredBingeGroup != null,
            bingeGroupOnly = bingeGroupOnly,
            filmSizeFloors = inputs.filmSizeFloors,
            demoted = demoted,
            continuation = inputs.continuation,
            preferred = inputs.preferred,
            softwareDecoded = inputs.softwareOnly.takeUnless { it.isEmpty() }?.let { it::names }
        )
    }

    /** [streams] less failure exclusions, hidden keys and identity rejections: what [select] ranks. */
    internal fun candidates(streams: List<Stream>, inputs: Inputs): List<Stream> {
        val identity = inputs.identity
        val failures = inputs.failures
        if (failures.excluded.isEmpty() && inputs.hiddenKeys.isEmpty() && identity == null) return streams
        val allowed = streams.filterNot { it.limitKey() in inputs.hiddenKeys || failures.excludes(it) }
        if (identity == null) return allowed
        val rejected = ContentIdentityGuard.rejections(allowed, identity)
        // Every rejection is logged so the guard's false-positive rate is measurable (Spec 65 P3);
        // a screen that re-selects as streams arrive logs a rejection again on each pass.
        rejected.forEach { (stream, reason) ->
            Log.i(IDENTITY_TAG, "reject=$reason addon=${stream.addonName} name=${ContentIdentityGuard.releaseNameOf(stream)}")
        }
        return if (rejected.isEmpty()) allowed else allowed.filterNot { it in rejected }
    }

    private const val IDENTITY_TAG = "AutoPlayIdentity"
}

/**
 * AIOStreams reports the file as not held by its debrid service: `streamData.cached` (AIOStreams'
 * `service.cached`) is false; null = not reported, as for every other addon. Such a row ranks behind the rest in auto-play
 * ([AutoPlaySelection.select]) and the failover walk ([nextLiveSource]); a manual press is unchanged.
 */
internal fun Stream.isReportedUncached(): Boolean = streamData?.cached == false
