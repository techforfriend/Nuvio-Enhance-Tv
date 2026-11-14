package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import com.nuvio.tv.core.util.languageCodeToName

@UnstableApi
internal fun PlayerRuntimeController.updateAvailableTracks(tracks: Tracks) {
    if (!tracks.groups.isEmpty()) stepClock.tracksRead(android.os.SystemClock.elapsedRealtime())
    val audioTracks = mutableListOf<TrackInfo>()
    val subtitleTracks = mutableListOf<TrackInfo>()
    var selectedAudioIndex = -1
    var selectedSubtitleIndex = -1

    var hasVideoTrack = false
    var firstVideoFormat: Format? = null
    var selectedVideoFormat: Format? = null
    var bestVideoTrackSupport = C.FORMAT_UNSUPPORTED_TYPE
    var selectedVideoTrackSupport = C.FORMAT_UNSUPPORTED_TYPE

    tracks.groups.forEachIndexed { groupIndex, trackGroup ->
        val trackType = trackGroup.type

        when (trackType) {
            C.TRACK_TYPE_VIDEO -> {
                if (trackGroup.length > 0) {
                    hasVideoTrack = true
                    if (firstVideoFormat == null) {
                        firstVideoFormat = trackGroup.getTrackFormat(0)
                    }
                }

                for (i in 0 until trackGroup.length) {
                    val support = trackGroup.getTrackSupport(i)
                    if (formatSupportRank(support) > formatSupportRank(bestVideoTrackSupport)) {
                        bestVideoTrackSupport = support
                    }
                    if (trackGroup.isTrackSelected(i)) {
                        val format = trackGroup.getTrackFormat(i)
                        val currentSelected = selectedVideoFormat
                        if (currentSelected == null || format.width > currentSelected.width ||
                            (format.width == currentSelected.width && (format.bitrate > currentSelected.bitrate))
                        ) {
                            selectedVideoFormat = format
                            selectedVideoTrackSupport = support
                        }
                    }
                }

                selectedVideoFormat?.let { format ->
                    if (format.frameRate > 0f) {
                        val raw = format.frameRate
                        val snapped = FrameRateUtils.snapToStandardRate(raw)
                        _uiState.update { currentState ->
                            if (currentState.detectedFrameRateSource == FrameRateSource.PROBE &&
                                currentState.detectedFrameRate > 0f
                            ) {
                                currentState
                            } else {
                                currentState.copy(
                                    detectedFrameRate = snapped,
                                    detectedFrameRateSource = FrameRateSource.TRACK
                                )
                            }
                        }
                        // nt6 AFR option 1: drive the display-mode switch from
                        // the format ExoPlayer just reported (self-gating; see
                        // PlayerRuntimeControllerAfrTrack.kt).
                        maybeRunTrackFormatAfr(rawFps = raw, format = format)
                    }
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) selectedAudioIndex = audioTracks.size

                    val codecName = TrackFormatNames.formatNameFromMime(format.sampleMimeType)
                    val channelLayout = TrackFormatNames.getChannelLayoutName(
                        format.channelCount
                    )
                    val langDisplay = format.language?.takeIf { it != "und" }?.let {
                        com.nuvio.tv.core.util.languageCodeToName(it)
                    }
                    val baseName = format.label ?: langDisplay ?: context.getString(R.string.player_track_audio_fallback, audioTracks.size + 1)
                    val suffix = listOfNotNull(codecName, channelLayout).joinToString(" ")
                    val displayName = if (suffix.isNotEmpty()) "$baseName ($suffix)" else baseName

                    audioTracks.add(
                        TrackInfo(
                            index = audioTracks.size,
                            name = displayName,
                            language = format.language,
                            trackId = format.id,
                            codec = codecName,
                            channelCount = format.channelCount.takeIf { it > 0 },
                            isSelected = isSelected,
                            sampleRate = format.sampleRate.takeIf { it > 0 }
                        )
                    )
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    // Skip addon subtitle tracks — they are managed separately
                    if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) selectedSubtitleIndex = subtitleTracks.size

                    val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val trackTexts = listOfNotNull(format.label, format.language, format.id)
                    val nameHintForced = trackTexts.any { it.contains("forced", ignoreCase = true) }
                    val isSongsAndSigns = trackTexts.any {
                        it.contains("songs", ignoreCase = true) && it.contains("sign", ignoreCase = true)
                    }
                    // Media3's MKV path sets no roleFlags (HLS/DASH/MP4 do), so the track name's own
                    // "SDH" carries most remuxes. Parsing MKV FlagHearingImpaired in dvmkv was rejected:
                    // roleFlags there re-rank Media3's automatic text pick (Spec 26/26l).
                    val isSdh = (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 ||
                        trackTexts.any { SDH_NAME_HINT.containsMatchIn(it) }

                    subtitleTracks.add(
                        TrackInfo(
                            index = subtitleTracks.size,
                            name = format.label ?: format.language ?: context.getString(R.string.player_track_subtitle_fallback, subtitleTracks.size + 1),
                            language = format.language,
                            trackId = format.id,
                            codec = TrackFormatNames.formatName(format.sampleMimeType, format.codecs),
                            isForced = hasForcedFlag || nameHintForced || isSongsAndSigns,
                            isSelected = isSelected,
                            isSdh = isSdh,
                            isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                        )
                    )
                }
            }
        }
    }

    currentStreamHasVideoTrack = hasVideoTrack
    val effectiveVideoFormat = selectedVideoFormat ?: firstVideoFormat
    if (effectiveVideoFormat != null) {
        currentVideoTrackMimeType = effectiveVideoFormat.sampleMimeType
        currentVideoTrackCodecs = effectiveVideoFormat.codecs
        currentVideoTrackWidth = effectiveVideoFormat.width.coerceAtLeast(0)
        currentVideoTrackHeight = effectiveVideoFormat.height.coerceAtLeast(0)
        currentVideoTrackBitrate = effectiveVideoFormat.bitrate
        // Spec 62 D12: an MKV without a Colour element has no colorInfo; the HEVC SPS still carries the
        // transfer (as the Stats panel reads it), so HDR10 remuxes get their output type (OSD badge,
        // Diagnostics, the HDR subtitle style).
        currentVideoTrackColorTransfer = PlaybackVideoColorTransfer.of(effectiveVideoFormat)
            .takeIf { it != Format.NO_VALUE }
        currentVideoTrackSelected = selectedVideoFormat != null
        currentVideoTrackBestSupport = if (selectedVideoFormat != null) {
            selectedVideoTrackSupport
        } else {
            bestVideoTrackSupport
        }
        currentVideoTrackIsLikelyVc1 = Vc1VideoFormatHeuristics.isLikelyVc1(
            sampleMimeType = effectiveVideoFormat.sampleMimeType,
            codecs = effectiveVideoFormat.codecs,
            label = effectiveVideoFormat.label
        )
        playbackAnalyticsDiagnostics.onVideoTrackSnapshot(effectiveVideoFormat)
        val videoTrackSignature = buildString {
            append(currentVideoTrackMimeType ?: "unknown")
            append('|')
            append(currentVideoTrackCodecs ?: "unknown")
            append('|')
            append(currentVideoTrackWidth)
            append('x')
            append(currentVideoTrackHeight)
            append("|vc1=")
            append(currentVideoTrackIsLikelyVc1)
            append("|selected=")
            append(currentVideoTrackSelected)
            append("|support=")
            append(Util.getFormatSupportString(currentVideoTrackBestSupport))
            append("|vc1Fallback=")
            append(isVc1SoftwareFallbackActiveForCurrentPlayback)
            append("|vc1TrackBypass=")
            append(isVc1TrackSelectionBypassActiveForCurrentPlayback)
        }
        if (videoTrackSignature != lastLoggedVideoTrackSignature) {
            lastLoggedVideoTrackSignature = videoTrackSignature
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: mime=${currentVideoTrackMimeType ?: "unknown"} " +
                        "codecs=${currentVideoTrackCodecs ?: "unknown"} " +
                        "size=${currentVideoTrackWidth}x${currentVideoTrackHeight} " +
                        "vc1=$currentVideoTrackIsLikelyVc1 " +
                        "selected=$currentVideoTrackSelected " +
                        "support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                        "vc1FallbackActive=$isVc1SoftwareFallbackActiveForCurrentPlayback " +
                        "vc1TrackBypassActive=$isVc1TrackSelectionBypassActiveForCurrentPlayback"
            )
        }
        if (currentVideoTrackIsLikelyVc1 &&
            !currentVideoTrackSelected &&
            isVc1SoftwareFallbackActiveForCurrentPlayback &&
            !isVc1TrackSelectionBypassActiveForCurrentPlayback
        ) {
            val currentPosition = _exoPlayer?.let(::exoCarryPositionMs) ?: 0L
            vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: VC-1 track present but unselected after software-preferred retry, " +
                        "forcing track-selection bypass support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                        "host=${currentStreamUrl.safeHost()} positionMs=$currentPosition"
            )
            scheduleDeferredPlayerReinitialize("VC-1 track bypass", currentPosition)
            return
        }
    } else {
        currentVideoTrackMimeType = null
        currentVideoTrackCodecs = null
        currentVideoTrackWidth = 0
        currentVideoTrackHeight = 0
        currentVideoTrackColorTransfer = null
        currentVideoTrackSelected = false
        currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
        currentVideoTrackIsLikelyVc1 = false
        lastLoggedVideoTrackSignature = null
    }
    // Spec 85 G4: a file ExoPlayer cannot play restarts on mpv; the rest reads tracks it never plays.
    if (maybeHopToMpvForTracks(tracks)) return

    hasScannedTextTracksOnce = true
    val chapters = chaptersOf(tracks)
    Log.d(
        PlayerRuntimeController.TAG,
        "TRACKS updated: internalSubs=${subtitleTracks.size}, selectedInternalIndex=$selectedSubtitleIndex, " +
                "chapters=${chapters.size}, " +
                "selectedAddon=${_uiState.value.selectedAddonSubtitle?.lang}, " +
                "pendingAddonLang=$pendingAddonSubtitleLanguage, pendingAddonTrackId=$pendingAddonSubtitleTrackId"
    )

    val pendingAddonTrackId = pendingAddonSubtitleTrackId
    if (!pendingAddonTrackId.isNullOrBlank()) {
        if (applyAddonSubtitleOverride(pendingAddonTrackId)) {
            Log.d(PlayerRuntimeController.TAG, "Selecting pending addon subtitle track id=$pendingAddonTrackId")
            pendingAddonSubtitleTrackId = null
            pendingAddonSubtitleLanguage = null
        }
    }

    val pendingLang = pendingAddonSubtitleLanguage
    if (
        pendingAddonSubtitleTrackId.isNullOrBlank() &&
        pendingLang != null &&
        subtitleTracks.isNotEmpty() &&
        _uiState.value.selectedAddonSubtitle == null
    ) {
        val preferredIndex = findBestInternalSubtitleTrackIndex(
            subtitleTracks = subtitleTracks,
            targets = listOf(pendingLang)
        )
        if (preferredIndex >= 0) {
            selectSubtitleTrack(preferredIndex)
            selectedSubtitleIndex = preferredIndex
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "Skipping pending subtitle track switch: no text track matches language=$pendingLang"
            )
        }
        pendingAddonSubtitleLanguage = null
    }

    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)?.let { restoredIndex ->
        selectedAudioIndex = restoredIndex
    }

    _uiState.update { state ->
        val finalSubtitleIndex = when {
            state.selectedAddonSubtitle != null -> -1
            selectedSubtitleIndex >= 0 -> selectedSubtitleIndex
            else -> state.selectedSubtitleTrackIndex
        }
        state.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = finalSubtitleIndex,
            chapters = chapters
        )
    }
    updateAudioControlAvailability(audioTracks, selectedAudioIndex)
    rememberEffectiveExoSubtitleSelectionForEngineSwitch(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex
    )
    applyPersistedTrackPreference(
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks
    )
    applyLosslessAudioDefaultIfUnset(audioTracks)
    if (currentStreamHasVideoTrack) {
        maybeScheduleFirstFrameWatchdog()
    } else {
        cancelFirstFrameWatchdog()
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    maybeAdjustLibassPipelineForTracks(tracks)
    refreshOsdTechBadges()
}

private fun formatSupportRank(@C.FormatSupport formatSupport: Int): Int {
    return when (formatSupport) {
        C.FORMAT_HANDLED -> 4
        C.FORMAT_EXCEEDS_CAPABILITIES -> 3
        C.FORMAT_UNSUPPORTED_DRM -> 2
        C.FORMAT_UNSUPPORTED_SUBTYPE -> 1
        else -> 0
    }
}

private fun PlayerRuntimeController.rememberEffectiveExoSubtitleSelectionForEngineSwitch(
    subtitleTracks: List<TrackInfo>,
    selectedSubtitleIndex: Int
) {
    if (isUsingMpvEngine()) return

    val selection = when {
        selectedSubtitleIndex >= 0 -> {
            val selectedTrack = subtitleTracks.getOrNull(selectedSubtitleIndex) ?: return
            PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = _uiState.value,
                    language = selectedTrack.language,
                    name = selectedTrack.name,
                    trackId = selectedTrack.trackId,
                    isForced = selectedTrack.isForced,
                    selectedUiTrackOverride = selectedTrack
                )
            )
        }
        _uiState.value.selectedAddonSubtitle != null -> {
            val addon = _uiState.value.selectedAddonSubtitle ?: return
            PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                id = addon.id,
                url = addon.url,
                language = addon.lang,
                addonName = addon.addonName
            )
        }
        else -> null
    }

    if (selection != null) {
        trackSelection.effectiveSubtitleSelectionForEngineSwitch =
            PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
                streamUrl = currentStreamUrl,
                selection = selection
            )
    }
}

internal fun PlayerRuntimeController.maybeAdjustLibassPipelineForTracks(tracks: Tracks) {
    if (libassPipelineSwitchInFlight) return

    val hasAssSsaTrack = tracks.hasAssSsaTextTrack()
    if (hasAssSsaTrack) {
        hasDetectedAssSsaTrackForCurrentStream = true
    }
    // Don't rebuild just to drop libass, except when it's holding the MKV extractor on a DV
    // file and blocking conversion; then drop it so the DV extractor runs.
    val desiredUseLibass = requestedUseLibassByUser && hasDetectedAssSsaTrackForCurrentStream
    if (desiredUseLibass == activePlayerUsesLibass) return
    if (!desiredUseLibass) {
        val libassBlockingDvConvert = activePlayerUsesLibass &&
                isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                tracks.hasDolbyVisionConvertibleVideoTrack()
        if (!libassBlockingDvConvert) return
    }

    val player = _exoPlayer ?: return
    val resumePosition = exoCarryPositionMs(player).takeIf { it > 0L }
    libassPipelineOverrideForCurrentStream = desiredUseLibass
    libassPipelineSwitchInFlight = true

    // rebuildCurrentStream seeds the track carry: without it a selection restored moments
    // earlier vanishes with this instance (capture 2026-09-04 11:33, subtitles missing on
    // every libass startup rebuild).
    _uiState.update { state ->
        state.copy(
            pendingSeekPosition = resumePosition ?: state.pendingSeekPosition,
            showLoadingOverlay = state.loadingOverlayEnabled
        )
    }

    // Spec 50 I2: keep a user pause (was always false).
    val paused = rebuildStartsPaused()
    scope.launch {
        // Pending seek set above (it keeps an earlier one when there is no live position).
        rebuildCurrentStream("libass pipeline switch", positionMs = 0L, paused = paused)
    }
}

/**
 * Snapshot of the EFFECTIVE track selections on a player that is about to be
 * rebuilt, shaped for the engine-switch carry channel. Pure: [PlayerUiState] in,
 * nullable preference out (null = nothing worth carrying). Exo→Exo rebuilds match
 * strictly on trackId/name/language; only the MPV `languageIndexHint` is omitted
 * (`indexHint` IS built — a carry later consumed by a real Exo→MPV switch uses it).
 */
internal fun buildEffectiveTrackPreferenceSnapshot(
    state: PlayerUiState,
    subtitlesDeliberatelyDisabled: Boolean
): PlayerRuntimeController.TrackPreference? {
    val audio = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)?.let { track ->
        PlayerRuntimeController.RememberedTrackSelection(
            language = track.language,
            name = track.name,
            trackId = track.trackId
        )
    }
    val subtitle = when {
        state.selectedAddonSubtitle != null -> state.selectedAddonSubtitle.let { selected ->
            PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                id = selected.id,
                url = selected.url,
                language = selected.lang,
                addonName = selected.addonName
            )
        }
        state.selectedSubtitleTrackIndex >= 0 ->
            state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let { track ->
                PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                    PlayerRuntimeController.RememberedTrackSelection(
                        language = track.language,
                        name = track.name,
                        trackId = track.trackId,
                        indexHint = track.index.takeIf { it >= 0 },
                        isForcedHint = track.isForced
                    )
                )
            }
        // Preserve a deliberate off across the rebuild; otherwise a profile with a
        // preferred language could get subtitles re-enabled the user turned away.
        subtitlesDeliberatelyDisabled -> PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        else -> null
    }
    if (audio == null && subtitle == null) return null
    return PlayerRuntimeController.TrackPreference(audio = audio, subtitle = subtitle)
}

/**
 * Seeds the engine-switch carry channel with the effective selections before a
 * same-engine player rebuild. Called by rebuildCurrentStream for every same-engine
 * rebuild (Spec 21 P7: libass, deferred rungs, error-recovery rebuilds, passthrough
 * return, tunneled audio switch where its own carry already occupies the channel),
 * and directly by the two rebuilds outside it: the P5 bitstream retry and the
 * background-crash release that resumeForLifecycle rebuilds. The restore path consumes the channel
 * with top priority on the rebuilt player's first TRACKS update, exactly like
 * an engine switch; its presence also suppresses the lossless-audio default
 * from re-running over a carried user pick. An ADDON subtitle carry resolves
 * only after the rebuilt player's own addon re-fetch repopulates the list
 * (initial TRACKS defer, then direct attach — no pause-parking); if that fetch
 * fails, behavior equals pre-fix and the occupied channel simply expires with
 * the stream. Every recovery rebuild carries the full snapshot: none of them
 * deliberately changes a track selection — the PCM and denied-audio retries
 * re-map the audio DECODER (forced-PCM sink, FFmpeg renderer priority), and the
 * same selected track is expected to play through the new pipeline (review
 * 2026-09-04-212511 M1; the subtitle-only exclusion it removed rested on a
 * premise the rebuild code disproves).
 */
internal fun PlayerRuntimeController.seedTrackPreferenceCarryForPlayerRebuild() {
    val seed = buildRebuildCarrySeed(
        state = _uiState.value,
        subtitlesDeliberatelyDisabled = trackSelection.subtitleDisabledByPersistedPreference ||
            (trackSelection.rememberedTrackPreference?.subtitle == PlayerRuntimeController.RememberedSubtitleSelection.Disabled),
        channelOccupied = trackSelection.pendingEngineSwitchTrackPreference != null,
        streamUrl = currentStreamUrl
    ) ?: return
    trackSelection.pendingEngineSwitchTrackPreference = seed
}

/**
 * Pure seeding decision: null when the channel is occupied by a genuine
 * engine-switch carry (first-wins — never clobber) or when nothing is worth
 * carrying. Extracted so the guard and the seeded fields are pin-able in unit
 * tests; deleting the call or inverting the guard must fail them.
 */
internal fun buildRebuildCarrySeed(
    state: PlayerUiState,
    subtitlesDeliberatelyDisabled: Boolean,
    channelOccupied: Boolean,
    streamUrl: String
): PlayerRuntimeController.PendingEngineSwitchTrackPreference? {
    if (channelOccupied) return null
    val preference = buildEffectiveTrackPreferenceSnapshot(
        state = state,
        subtitlesDeliberatelyDisabled = subtitlesDeliberatelyDisabled
    ) ?: return null
    return PlayerRuntimeController.PendingEngineSwitchTrackPreference(
        streamUrl = streamUrl,
        preference = preference,
        sourceEngine = InternalPlayerEngine.EXOPLAYER
    )
}

private fun Tracks.hasAssSsaTextTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (index in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(index)
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) return true

            val hasAssCodec = format.codecs
                ?.split(',')
                ?.asSequence()
                ?.map { it.trim().lowercase(Locale.US) }
                ?.any { codec ->
                    codec == MimeTypes.TEXT_SSA ||
                            codec == "s_text/ass" ||
                            codec == "s_text/ssa" ||
                            codec.endsWith("/x-ssa")
                } == true
            if (hasAssCodec) return true
        }
    }
    return false
}

// Profiles the DolbyVisionExtractorsFactory can convert: 7 always, 5 only with DV5 conversion
// on. This check matches 5 either way, so a DV5 file may drop libass without converting.
private val DV_CONVERTIBLE_PROFILES = setOf(5, 7)

private fun Tracks.hasDolbyVisionConvertibleVideoTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (index in 0 until trackGroup.length) {
            val profile = DvCodecString.profile(trackGroup.getTrackFormat(index).codecs)
            if (profile in DV_CONVERTIBLE_PROFILES) return true
        }
    }
    return false
}

internal fun PlayerRuntimeController.normalizeTrackMatchValue(value: String?): String? = value
    ?.lowercase()
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

internal fun PlayerRuntimeController.maybeRestorePendingAudioSelectionAfterSubtitleRefresh(
    audioTracks: List<TrackInfo>
): Int? {
    val pending = pendingAudioSelectionAfterSubtitleRefresh ?: return null
    if (pending.streamUrl != currentStreamUrl) {
        pendingAudioSelectionAfterSubtitleRefresh = null
        return null
    }
    if (audioTracks.isEmpty()) return null

    val targetLang = normalizeTrackMatchValue(pending.language)
    val targetName = normalizeTrackMatchValue(pending.name)

    fun languageMatches(trackLanguage: String?): Boolean {
        val trackLang = normalizeTrackMatchValue(trackLanguage)
        return !targetLang.isNullOrBlank() &&
                !trackLang.isNullOrBlank() &&
                (trackLang == targetLang ||
                        trackLang.startsWith("$targetLang-") ||
                        trackLang.startsWith("${targetLang}_"))
    }

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName
        }
    } else {
        -1
    }

    val nameContainsIndex = if (exactNameIndex < 0 && !targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true
        }
    } else {
        -1
    }

    val languageIndex = if (exactNameIndex < 0 && nameContainsIndex < 0) {
        audioTracks.indexOfFirst { track -> languageMatches(track.language) }
    } else {
        -1
    }

    val index = when {
        exactNameIndex >= 0 -> exactNameIndex
        nameContainsIndex >= 0 -> nameContainsIndex
        else -> languageIndex
    }

    pendingAudioSelectionAfterSubtitleRefresh = null
    if (index < 0) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Audio restore skipped after subtitle refresh: no match for lang=$targetLang name=$targetName"
        )
        return null
    }

    val restoredTrack = audioTracks[index]
    Log.d(
        PlayerRuntimeController.TAG,
        "Restoring audio after subtitle refresh index=$index lang=${restoredTrack.language} name=${restoredTrack.name}"
    )
    selectAudioTrack(index)
    return index
}

internal fun PlayerRuntimeController.findMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val strictIndex = findStrictMatchingTrackIndex(
        tracks = tracks,
        target = target
    )
    if (strictIndex >= 0) {
        return strictIndex
    }

    return findLanguageFallbackTrackIndex(
        tracks = tracks,
        target = target
    )
}

internal fun PlayerRuntimeController.findMatchingTrackIndexForEngineSwitchToMpv(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection,
    sourceEngine: InternalPlayerEngine
): Int {
    val strictIndex = findStrictMatchingTrackIndex(
        tracks = tracks,
        target = target
    )
    if (strictIndex >= 0) {
        return strictIndex
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER && isUsingMpvEngine()) {
        val hintedIndex = findEngineSwitchHintTrackIndex(
            tracks = tracks,
            target = target
        )
        if (hintedIndex >= 0) {
            return hintedIndex
        }
    }

    return findLanguageFallbackTrackIndex(
        tracks = tracks,
        target = target
    )
}

private fun PlayerRuntimeController.findStrictMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetTrackId = normalizeTrackMatchValue(target.trackId)
    val targetName = normalizeTrackMatchValue(target.name)
    val targetLang = normalizeTrackMatchValue(target.language)

    val exactTrackIdIndex = if (!targetTrackId.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.trackId) == targetTrackId &&
                    (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang) &&
                    (targetName.isNullOrBlank() || normalizeTrackMatchValue(track.name) == targetName ||
                            normalizeTrackMatchValue(track.name)?.contains(targetName) == true)
        }
    } else {
        -1
    }
    if (exactTrackIdIndex >= 0) return exactTrackIdIndex

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName &&
                    (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
    } else {
        -1
    }
    if (exactNameIndex >= 0) return exactNameIndex

    val nameContainsIndex = if (!targetName.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true &&
                    (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
    } else {
        -1
    }
    if (nameContainsIndex >= 0) return nameContainsIndex

    return -1
}

private fun PlayerRuntimeController.findLanguageFallbackTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetLang = normalizeTrackMatchValue(target.language)
    return if (!targetLang.isNullOrBlank()) {
        // Detect the regional variant from the remembered selection's name/language
        val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = target.language,
            name = target.name,
            trackId = target.trackId
        )
        val langCandidates = tracks.indices.filter { index ->
            val trackLang = normalizeTrackMatchValue(tracks[index].language)
            !trackLang.isNullOrBlank() &&
                    (trackLang == targetLang ||
                            trackLang.startsWith("$targetLang-") ||
                            trackLang.startsWith("${targetLang}_"))
        }

        // If the remembered track was forced, only consider forced candidates.
        // This prevents restoring a non-forced subtitle when the user had a forced
        // track selected (e.g. switching episodes where the new one lacks forced subs).
        val filteredCandidates = if (target.isForcedHint == true) {
            langCandidates.filter { index -> tracks[index].isForced }
        } else {
            langCandidates
        }

        if (filteredCandidates.size <= 1) {
            filteredCandidates.firstOrNull() ?: -1
        } else {
            filteredCandidates.firstOrNull { index ->
                val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = tracks[index].language,
                    name = tracks[index].name,
                    trackId = tracks[index].trackId
                )
                trackVariant == targetVariant
            } ?: filteredCandidates.first()
        }
    } else {
        -1
    }
}

private fun PlayerRuntimeController.findEngineSwitchHintTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val indexHint = target.indexHint?.takeIf { it >= 0 } ?: -1
    val languageIndexHint = target.languageIndexHint?.takeIf { it >= 0 }
    val targetForced = target.isForcedHint
    val sparseMetadata = hasSparseMpvSubtitleMetadataForEngineSwitch(tracks)
    val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = target.language,
        name = target.name,
        trackId = target.trackId
    )

    val baseCandidates = tracks.indices.filter { index ->
        val track = tracks[index]
        target.language.isNullOrBlank() ||
                PlayerSubtitleUtils.matchesLanguageCode(track.language, target.language) ||
                PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                ) == targetVariant
    }
    val sparseCandidates = if (sparseMetadata) {
        if (targetForced == null) {
            tracks.indices.toList()
        } else {
            tracks.indices.filter { index -> tracks[index].isForced == targetForced }
                .ifEmpty { tracks.indices.toList() }
        }
    } else {
        emptyList()
    }

    if (baseCandidates.isEmpty()) {
        if (indexHint in sparseCandidates) {
            return indexHint
        }
        if (languageIndexHint != null && languageIndexHint in sparseCandidates.indices) {
            return sparseCandidates[languageIndexHint]
        }
        return -1
    }

    val preferredCandidates = if (targetForced == null) {
        baseCandidates
    } else {
        baseCandidates.filter { index -> tracks[index].isForced == targetForced }
            .ifEmpty { baseCandidates }
    }

    if (indexHint in preferredCandidates) {
        return indexHint
    }
    if (languageIndexHint != null && languageIndexHint in preferredCandidates.indices) {
        return preferredCandidates[languageIndexHint]
    }
    if (indexHint in baseCandidates) {
        return indexHint
    }
    if (indexHint in sparseCandidates) {
        return indexHint
    }
    if (languageIndexHint != null && languageIndexHint in sparseCandidates.indices) {
        return sparseCandidates[languageIndexHint]
    }

    return -1
}

private fun PlayerRuntimeController.hasSparseMpvSubtitleMetadataForEngineSwitch(
    tracks: List<TrackInfo>
): Boolean {
    if (tracks.isEmpty()) return false
    val sparseCount = tracks.count { track ->
        val normalizedName = normalizeTrackMatchValue(track.name)
        track.language.isNullOrBlank() &&
                (
                        normalizedName.isNullOrBlank() ||
                                normalizedName == "subtitle" ||
                                normalizedName.startsWith("subtitle ")
                        )
    }
    return sparseCount > 0 && sparseCount * 2 >= tracks.size
}

/**
 * Runs the track-preference restore on a tracks update: [TrackSelectionResolver] picks the
 * channel (URL-matched engine-switch carry, else persisted) and decides each step; this
 * executes them and writes what stays pending back to that channel.
 */
internal fun PlayerRuntimeController.applyPersistedTrackPreference(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>
) {
    val source = trackSelection.beginRestore(currentStreamUrl) ?: return
    val pending = source.preference
    var updatedSubtitleIndex: Int? = null
    var updatedAddonSubtitle: com.nuvio.tv.domain.model.Subtitle? = null

    val audioStep = pending.audio?.let { audioSelection ->
        // Task 3.9: an audio preference existed for this stream (whether or not it
        // matches a track here) — the lossless default must never run over it.
        trackSelection.persistedAudioPreferenceSeenForStream = true
        val step = TrackSelectionResolver.audioStep(audioSelection, audioTracks) { target ->
            findMatchingTrackIndex(audioTracks, target)
        }
        when (step) {
            TrackSelectionResolver.AudioStep.Deferred ->
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio deferred (no tracks yet)")
            is TrackSelectionResolver.AudioStep.Select -> {
                val index = step.index
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index lang=${audioTracks[index].language} name=${audioTracks[index].name}")
                selectAudioTrack(index)
                _uiState.update { it.copy(selectedAudioTrackIndex = index) }
            }
            is TrackSelectionResolver.AudioStep.AlreadySelected ->
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=${step.index} already selected, clearing")
            TrackSelectionResolver.AudioStep.NoMatch ->
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio no match for lang=${audioSelection.language} name=${audioSelection.name}, clearing")
        }
        step
    }

    val subtitleStep = pending.subtitle?.let { subtitleSelection ->
        val state = _uiState.value
        TrackSelectionResolver.subtitleStep(
            selection = subtitleSelection,
            subtitleTracks = subtitleTracks,
            addonSubtitles = state.addonSubtitles,
            addonSubtitlesLoading = state.isLoadingAddonSubtitles,
            carrySourceEngine = source.carrySourceEngine,
            usingMpv = isUsingMpvEngine(),
            sparseMpvSubtitleMetadata = hasSparseMpvSubtitleMetadataForEngineSwitch(subtitleTracks),
            matchInternal = { target, carryEngine ->
                if (carryEngine != null) {
                    findMatchingTrackIndexForEngineSwitchToMpv(
                        tracks = subtitleTracks,
                        target = target,
                        sourceEngine = carryEngine
                    )
                } else {
                    findMatchingTrackIndex(subtitleTracks, target)
                }
            }
        )
    }
    var keepSubtitle = subtitleStep?.keepsPending ?: false
    when (subtitleStep) {
        null -> Unit
        is TrackSelectionResolver.SubtitleStep.Disable -> {
            trackSelection.autoSubtitleSelected = true
            trackSelection.subtitleDisabledByPersistedPreference = true
            updatedSubtitleIndex = -1
            if (subtitleStep.reapply) {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle disabled (re-applying)")
                disableSubtitles()
            } else {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle already disabled, clearing")
            }
        }
        TrackSelectionResolver.SubtitleStep.InternalDeferred ->
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle deferred (no tracks yet)")
        is TrackSelectionResolver.SubtitleStep.InternalSelect -> {
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=${subtitleStep.index} (re-applying)")
            trackSelection.autoSubtitleSelected = true
            selectSubtitleTrack(subtitleStep.index)
            updatedSubtitleIndex = subtitleStep.index
        }
        is TrackSelectionResolver.SubtitleStep.InternalAlreadySelected -> {
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=${subtitleStep.index} already selected, keeping for pipeline restart")
            trackSelection.autoSubtitleSelected = true
            updatedSubtitleIndex = subtitleStep.index
        }
        TrackSelectionResolver.SubtitleStep.InternalSparseMpvDefer -> Unit
        is TrackSelectionResolver.SubtitleStep.InternalAddonFallback -> {
            val addonFallback = subtitleStep.addon
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF restore: internal no match, falling back to addon lang=${addonFallback.lang} variant=${subtitleStep.variant}"
            )
            trackSelection.autoSubtitleSelected = true
            trackSelection.subtitleAddonRestoredByPersistedPreference = true
            trackSelection.pendingRestoredAddonSubtitle = addonFallback
            selectAddonSubtitle(addonFallback)
            updatedAddonSubtitle = addonFallback
        }
        is TrackSelectionResolver.SubtitleStep.InternalNoMatch ->
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle no match, no addon fallback for variant=${subtitleStep.variant}, clearing")
        is TrackSelectionResolver.SubtitleStep.AddonSelect -> {
            val addonMatch = subtitleStep.addon
            Log.d(
                PlayerRuntimeController.TAG,
                "Restoring same-series addon subtitle lang=${addonMatch.lang} id=${addonMatch.id}"
            )
            trackSelection.autoSubtitleSelected = true
            trackSelection.subtitleAddonRestoredByPersistedPreference = true
            trackSelection.pendingRestoredAddonSubtitle = addonMatch
            selectAddonSubtitle(addonMatch)
            updatedAddonSubtitle = addonMatch
            // Checked after the selection: MPV must report the addon track selected.
            keepSubtitle = subtitleStep.keepsPendingAfterSelect { isMpvAddonSubtitleTrackActive(addonMatch) }
        }
        TrackSelectionResolver.SubtitleStep.AddonAwaitList -> {
            // Addon subtitles haven't loaded yet — keep the preference so it can be
            // restored once they arrive. Block auto-selection to prevent internal
            // tracks from overriding the user's choice.
            trackSelection.autoSubtitleSelected = true
            trackSelection.subtitleAddonRestoredByPersistedPreference = true
        }
        TrackSelectionResolver.SubtitleStep.AddonNoMatch -> {
            // The cleared preference lets tryAutoSelectPreferredSubtitleFromAvailableTracks
            // run its normal logic (prefer embedded over addon); reset the flags a defer
            // phase may have set.
            trackSelection.autoSubtitleSelected = false
            trackSelection.subtitleAddonRestoredByPersistedPreference = false
        }
    }

    _uiState.update { state ->
        state.copy(
            selectedSubtitleTrackIndex = updatedSubtitleIndex ?: state.selectedSubtitleTrackIndex,
            selectedAddonSubtitle = updatedAddonSubtitle ?: if (updatedSubtitleIndex != null) null else state.selectedAddonSubtitle
        )
    }
    val remaining = TrackSelectionResolver.remaining(
        pending = pending,
        keepAudio = audioStep?.keepsPending ?: false,
        keepSubtitle = keepSubtitle
    )
    trackSelection.finishRestore(source, remaining, currentStreamUrl, currentInternalPlayerEngine)
}

internal fun PlayerRuntimeController.subtitleLanguageTargets(): List<String> {
    val preferred = _uiState.value.subtitleStyle.preferredLanguage.lowercase()
    if (preferred == "none") return emptyList()
    val secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage?.lowercase()
    return listOfNotNull(preferred, secondary)
}

internal fun PlayerRuntimeController.findBestInternalSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>,
    forcedOnly: Boolean = false,
    normalOnly: Boolean = false,
    selectedAudioTrack: TrackInfo? = null
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (forcedOnly) {
            val forcedIndex = findBestForcedSubtitleTrackIndex(
                subtitleTracks = subtitleTracks,
                target = target,
                selectedAudioTrack = selectedAudioTrack
            )
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidateIndexes = subtitleTracks.indices.filter { index ->
            val track = subtitleTracks[index]
            (!normalOnly || !track.isForced) && subtitleTrackMatchesLanguage(track, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracks(subtitleTracks, normalOnly)
                if (brazilianFromGenericPt >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal pt-br via generic-pt tags index=$brazilianFromGenericPt"
                    )
                    return brazilianFromGenericPt
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            if (normalizedTarget == "es-419") {
                val latinoFromGenericEs = findLatinoSpanishInGenericEsTracks(subtitleTracks, normalOnly)
                if (latinoFromGenericEs >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal es-419 via generic-es tags index=$latinoFromGenericEs"
                    )
                    return latinoFromGenericEs
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        val preferredCandidateIndexes = candidateIndexes.filter { index -> !subtitleTracks[index].isForced }
            .takeIf { it.isNotEmpty() }
            ?: if (normalOnly) {
                // Forced tracks are the only candidates but we explicitly want non-forced.
                // Continue to the next target or let addon fallback handle it.
                continue
            } else {
                candidateIndexes
            }
        if (preferredCandidateIndexes.size == 1) {
            // For regional targets, verify the single candidate is actually the right variant.
            // A track with language="por" matches both "pt" and "pt-br" by language code,
            // but may be the wrong accent based on its name tags.
            if (normalizedTarget == "pt" || normalizedTarget == "es") {
                val track = subtitleTracks[preferredCandidateIndexes.first()]
                val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                )
                if (variant != normalizedTarget && variant != track.language?.lowercase()) {
                    // Single candidate is a different variant (e.g. PT-BR when we want PT).
                    // Skip it so the search can continue to secondary target or addon fallback.
                    continue
                }
            }
            return preferredCandidateIndexes.first()
        }

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = preferredCandidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        if (normalizedTarget == "es" || normalizedTarget == "es-419") {
            val tieBroken = breakSpanishSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = preferredCandidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return preferredCandidateIndexes.first()
    }
    return -1
}

private fun findBestForcedSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    target: String,
    selectedAudioTrack: TrackInfo?
): Int {
    // isForced is set from both the ExoPlayer SELECTION_FLAG_FORCED and name/label/id containing "forced"
    val directMatch = subtitleTracks.indexOfFirst { track ->
        track.isForced &&
            subtitleTrackMatchesLanguage(track, target) &&
            selectedAudioTrack != null &&
            subtitleTrackMatchesSelectedAudioLanguage(track, selectedAudioTrack)
    }
    if (directMatch >= 0) return directMatch

    // For regional variants (pt-br, es-419) the track may have a generic language code
    // (e.g. "pt") but carry regional tags in its name/trackId. Use detectTrackLanguageVariant
    // to resolve the actual variant and match against the target.
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    if (normalizedTarget == "pt-br" || normalizedTarget == "es-419") {
        return subtitleTracks.indexOfFirst { track ->
            track.isForced &&
                selectedAudioTrack != null &&
                subtitleTrackMatchesSelectedAudioLanguage(track, selectedAudioTrack) &&
                PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                ) == normalizedTarget
        }
    }
    return -1
}

private fun subtitleTrackMatchesLanguage(track: TrackInfo, target: String): Boolean {
    return trackMatchesLanguage(
        name = track.name,
        language = track.language,
        trackId = track.trackId,
        target = target
    )
}

private fun audioTrackMatchesLanguage(track: TrackInfo, target: String): Boolean {
    return trackMatchesLanguage(
        name = track.name,
        language = track.language,
        trackId = track.trackId,
        target = target
    )
}

private fun trackMatchesLanguage(
    name: String?,
    language: String?,
    trackId: String?,
    target: String
): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(language, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val targetName = languageCodeToName(target).lowercase(Locale.ROOT)
    val haystack = listOfNotNull(name, language, trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

internal fun PlayerRuntimeController.selectedAudioMatchesResolvedPreferredAudio(track: TrackInfo): Boolean {
    return mpvPreferredAudioLanguages.any { target -> audioTrackMatchesLanguage(track, target) }
}

private fun languageCodeAppearsInHaystack(haystack: String, normalizedTarget: String): Boolean {
    if (normalizedTarget.isBlank()) return false
    var searchFrom = 0
    while (searchFrom <= haystack.length - normalizedTarget.length) {
        val matchIndex = haystack.indexOf(normalizedTarget, startIndex = searchFrom)
        if (matchIndex < 0) return false

        val before = matchIndex - 1
        val after = matchIndex + normalizedTarget.length
        val startsAtBoundary = before < 0 || !haystack[before].isLetterOrDigit()
        val endsAtBoundary = after >= haystack.length || !haystack[after].isLetterOrDigit()
        if (startsAtBoundary && endsAtBoundary) return true

        searchFrom = matchIndex + 1
    }
    return false
}

private fun subtitleTrackMatchesSelectedAudioLanguage(
    subtitleTrack: TrackInfo,
    selectedAudioTrack: TrackInfo
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (subtitleTrackMatchesLanguage(subtitleTrack, audioLanguage)) return true
    }

    val subtitleLanguageName = subtitleTrack.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageCodeToName(it).lowercase(Locale.ROOT) }
    val audioHaystack = listOfNotNull(
        selectedAudioTrack.name,
        selectedAudioTrack.language,
        selectedAudioTrack.trackId
    ).joinToString(" ").lowercase(Locale.ROOT)
    return !subtitleLanguageName.isNullOrBlank() && audioHaystack.contains(subtitleLanguageName)
}

internal fun selectedAudioTrackForSubtitleMatching(state: PlayerUiState): TrackInfo? {
    return state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }
}

internal fun selectedAudioLanguageTarget(track: TrackInfo): String? {
    track.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { return it }

    val haystack = listOfNotNull(track.name, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return AVAILABLE_SUBTITLE_LANGUAGES.firstOrNull { language ->
        val code = language.code.lowercase(Locale.ROOT)
        val name = languageCodeToName(language.code).lowercase(Locale.ROOT)
        languageCodeAppearsInHaystack(haystack, code) || (name.isNotBlank() && haystack.contains(name))
    }?.code
}

private fun addonSubtitleIsForced(subtitle: Subtitle): Boolean {
    return listOf(subtitle.id, subtitle.url, subtitle.addonName).any {
        it.contains("forced", ignoreCase = true)
    }
}

private fun addonSubtitleMatchesLanguage(subtitle: Subtitle, target: String): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val targetName = languageCodeToName(target).lowercase(Locale.ROOT)
    val haystack = listOf(subtitle.lang, subtitle.id, subtitle.url, subtitle.addonName)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

private fun addonSubtitleMatchesSelectedAudioLanguage(
    subtitle: Subtitle,
    selectedAudioTrack: TrackInfo
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (addonSubtitleMatchesLanguage(subtitle, audioLanguage)) return true
    }

    val subtitleLanguageName = subtitle.lang
        .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageCodeToName(it).lowercase(Locale.ROOT) }
    val audioHaystack = listOfNotNull(
        selectedAudioTrack.name,
        selectedAudioTrack.language,
        selectedAudioTrack.trackId
    ).joinToString(" ").lowercase(Locale.ROOT)
    return !subtitleLanguageName.isNullOrBlank() && audioHaystack.contains(subtitleLanguageName)
}

internal fun PlayerRuntimeController.findBrazilianPortugueseInGenericPtTracks(
    subtitleTracks: List<TrackInfo>,
    normalOnly: Boolean = false
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        if (normalOnly && subtitleTracks[index].isForced) return@filter false
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    val brazilianNonForced = genericPtIndexes.filter { index ->
        !subtitleTracks[index].isForced &&
                subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    }
    if (brazilianNonForced.isNotEmpty()) return brazilianNonForced.first()

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakPortugueseSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    }

    return if (normalizedTarget == "pt-br") {
        candidateIndexes.firstOrNull { hasBrazilianTags(it) && !hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropeanTags(it) && !hasBrazilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.findLatinoSpanishInGenericEsTracks(
    subtitleTracks: List<TrackInfo>,
    normalOnly: Boolean = false
): Int {
    val genericEsIndexes = subtitleTracks.indices.filter { index ->
        if (normalOnly && subtitleTracks[index].isForced) return@filter false
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "es"
    }
    if (genericEsIndexes.isEmpty()) return -1

    val latinoNonForced = genericEsIndexes.filter { index ->
        !subtitleTracks[index].isForced &&
                subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }
    if (latinoNonForced.isNotEmpty()) return latinoNonForced.first()

    return genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    } ?: genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakSpanishSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasLatinoTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS)
    }

    fun hasCastilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }

    return if (normalizedTarget == "es-419") {
        candidateIndexes.firstOrNull { hasLatinoTags(it) && !hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasLatinoTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasCastilianTags(it) && !hasLatinoTags(it) }
            ?: candidateIndexes.firstOrNull { hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { !hasLatinoTags(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.subtitleHasAnyTag(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}

/**
 * Checks whether the selected audio track matches the subtitle target language for the purpose
 * of activating forced-only subtitle mode. This is more lenient than [audioTrackMatchesLanguage]
 * for regional variants: if the target is "pt-br" and the audio language is generic "pt",
 * we consider it a match because the audio is likely Brazilian Portuguese even without explicit
 * regional tags. Same logic applies to "es-419" matching generic "es" audio.
 */
private fun audioMatchesSubtitleTargetForForced(audioTrack: TrackInfo, target: String): Boolean {
    if (audioTrackMatchesLanguage(audioTrack, target)) return true

    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val baseTarget = normalizedTarget.substringBefore('-')
    if (baseTarget == normalizedTarget) return false // not a regional variant

    // Check if the audio track's base language matches the target's base language.
    // e.g. audio="pt" matches target="pt-br", audio="es" matches target="es-419"
    val audioVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = audioTrack.language,
        name = audioTrack.name,
        trackId = audioTrack.trackId
    )
    // If audio is generic base (e.g. "pt") or the same regional variant (e.g. "pt-br"), match.
    return audioVariant == baseTarget || audioVariant == normalizedTarget
}

internal fun PlayerRuntimeController.tryAutoSelectPreferredSubtitleFromAvailableTracks() {
    if (trackSelection.isUserExplicitSubtitleSelection) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: user explicitly selected current subtitle")
        return
    }
    val state = _uiState.value
    val preferredTargets = subtitleLanguageTargets()
    val primaryTarget = preferredTargets.firstOrNull()

    if (trackSelection.autoSubtitleSelected) {
        val currentSelectedLang = when {
            state.selectedAddonSubtitle != null -> state.selectedAddonSubtitle.lang
            state.selectedSubtitleTrackIndex >= 0 -> state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.language
            else -> null
        }
        val isPrimarySatisfied = primaryTarget != null && currentSelectedLang != null &&
            PlayerSubtitleUtils.matchesLanguageCode(currentSelectedLang, primaryTarget)
        val hasBetterAddonMatch = !isPrimarySatisfied && primaryTarget != null &&
            state.addonSubtitles.any { PlayerSubtitleUtils.matchesLanguageCode(it.lang, primaryTarget) }

        if (!hasBetterAddonMatch) return
    }
    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val useForcedSubtitles = state.subtitleStyle.useForcedSubtitles
    val forcedTarget = when {
        !useForcedSubtitles -> null
        primaryTarget != null && selectedAudioTrack != null && audioMatchesSubtitleTargetForForced(selectedAudioTrack, primaryTarget) ->
            primaryTarget
        primaryTarget == null &&
            selectedAudioTrack != null &&
            selectedAudioMatchesResolvedPreferredAudio(selectedAudioTrack) ->
            selectedAudioLanguageTarget(selectedAudioTrack)
        else -> null
    }
    val forcedOnly = forcedTarget != null
    val targets = when {
        forcedTarget != null -> listOf(forcedTarget)
        primaryTarget != null -> preferredTargets
        else -> emptyList()
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "AUTO_SUB eval: targets=$targets, forcedOnly=$forcedOnly, selectedAudio=${selectedAudioTrack?.language}/${selectedAudioTrack?.name}, scannedText=$hasScannedTextTracksOnce, " +
            "internalCount=${state.subtitleTracks.size}, selectedInternal=${state.selectedSubtitleTrackIndex}, " +
            "addonCount=${state.addonSubtitles.size}, selectedAddon=${state.selectedAddonSubtitle?.lang}"
    )
    if (useForcedSubtitles && selectedAudioTrack == null) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer: selected audio track unknown")
        return
    }
    if (targets.isEmpty()) {
        trackSelection.autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred=none")
        if (isUsingMpvEngine()) {
            mpvView?.disableSubtitles()
        }
        return
    }

    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = targets,
        forcedOnly = forcedOnly,
        normalOnly = !forcedOnly,
        selectedAudioTrack = selectedAudioTrack
    )
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        // Determine which target position this internal match satisfies,
        // taking regional variant into account so that e.g. a PT-BR track
        // is not treated as a primary match when the user wants PT.
        val matchedTrack = state.subtitleTracks[internalIndex]
        val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.name,
            trackId = matchedTrack.trackId
        )
        val matchedTargetPosition = targets.indexOfFirst { target ->
            val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                    PlayerSubtitleUtils.matchesLanguageCode(trackVariant, target)
        }
        val addonSubtitlesLoaded = !state.isLoadingAddonSubtitles
        if (matchedTargetPosition > 0 && !addonSubtitlesLoaded) {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB defer: internal match is secondary target pos=$matchedTargetPosition, addons still loading"
            )
            return
        }
        // If internal match is secondary and a primary addon match exists, prefer the addon.
        if (matchedTargetPosition > 0 && addonSubtitlesLoaded) {
            val primaryTarget = targets.first()
            val primaryAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, primaryTarget)
            }
            if (primaryAddonMatch != null) {
                trackSelection.autoSubtitleSelected = true
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB pick addon (primary) over internal (secondary): addon lang=${primaryAddonMatch.lang} vs internal variant=$trackVariant"
                )
                selectAddonSubtitle(primaryAddonMatch)
                return
            }
        }
        trackSelection.autoSubtitleSelected = true
        val currentInternal = state.selectedSubtitleTrackIndex
        val currentAddon = state.selectedAddonSubtitle
        if (currentInternal != internalIndex || currentAddon != null) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal index=$internalIndex lang=${state.subtitleTracks[internalIndex].language}")
            selectSubtitleTrack(internalIndex)
            _uiState.update { it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null) }
        } else {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred internal already selected")
        }
        return
    }

    if (forcedOnly) {
        if (!hasScannedTextTracksOnce) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer forced: text tracks not scanned yet")
            return
        }
        if (state.isLoadingAddonSubtitles) {
            // Disable any non-forced subtitle ExoPlayer auto-selected while we wait.
            val hasNonForcedSubtitleActive = state.selectedSubtitleTrackIndex >= 0 &&
                state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.isForced != true
            if (hasNonForcedSubtitleActive) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SUB forced: disabling non-forced subtitle while addons load")
                disableSubtitles()
                _uiState.update { it.copy(selectedSubtitleTrackIndex = -1) }
            }
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer forced: addon subtitles still loading")
            return
        }
        val forcedAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
            addonSubtitleIsForced(subtitle) &&
                addonSubtitleMatchesLanguage(subtitle, forcedTarget) &&
                selectedAudioTrack != null &&
                addonSubtitleMatchesSelectedAudioLanguage(subtitle, selectedAudioTrack)
        }
        if (forcedAddonMatch != null) {
            trackSelection.autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick forced addon lang=${forcedAddonMatch.lang} id=${forcedAddonMatch.id}")
            selectAddonSubtitle(forcedAddonMatch)
            return
        }
        trackSelection.autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: forced subtitles requested but no forced match found")
        disableSubtitles()
        return
    }

    val selectedAddon = state.selectedAddonSubtitle
    val selectedAddonMatchesTarget = selectedAddon != null &&
        (!useForcedSubtitles || !addonSubtitleIsForced(selectedAddon)) &&
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(selectedAddon.lang, target) }
    if (selectedAddonMatchesTarget) {
        val selectedMatchesPrimary = PlayerSubtitleUtils.matchesLanguageCode(
            selectedAddon.lang, targets.first()
        )
        if (selectedMatchesPrimary) {
            trackSelection.autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: matching addon already selected (primary match)")
            return
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "AUTO_SUB: selected addon ${selectedAddon.lang} matches secondary target, checking for primary addon"
        )
    }

    // Wait until we have at least one full text-track scan to avoid choosing addon too early.
    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: text tracks not scanned yet")
        return
    }

    val playerReady = if (isUsingMpvEngine()) {
        mpvView != null
    } else {
        _exoPlayer?.playbackState == Player.STATE_READY
    }
    if (!playerReady) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: player not ready")
        return
    }

    val addonMatch = run {
        // Try each target in priority order so primary language is preferred over secondary.
        for (target in targets) {
            val match = state.addonSubtitles.firstOrNull { subtitle ->
                (!useForcedSubtitles || !addonSubtitleIsForced(subtitle)) &&
                    PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
            if (match != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB addon fallback: target=$target matched addon lang=${match.lang} id=${match.id} " +
                            "(addons=${state.addonSubtitles.size}, targets=$targets)"
                )
                return@run match
            }
        }
        null
    }
    if (addonMatch != null) {
        trackSelection.autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick addon lang=${addonMatch.lang} id=${addonMatch.id}")
        selectAddonSubtitle(addonMatch)
    } else {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB no addon match for targets=$targets")
    }
}

internal fun PlayerRuntimeController.applySubtitlePreferences(preferred: String, secondary: String?) {
    if (isUsingMpvEngine()) {
        mpvView?.applySubtitleLanguagePreferences(preferred, secondary)
        if (preferred == "none") {
            // Same guard as the Exo none-branch: "none" = no AUTO pick, not
            // "subtitles off" — don't wipe a restored/remembered selection
            // (uiState is the only selection signal under MPV).
            if (_uiState.value.selectedAddonSubtitle == null &&
                _uiState.value.selectedSubtitleTrackIndex < 0
            ) {
                mpvView?.disableSubtitles()
                _uiState.update {
                    it.copy(
                        selectedSubtitleTrackIndex = -1,
                        selectedAddonSubtitle = null
                    )
                }
            }
        }
        return
    }

    _exoPlayer?.let { player ->
        val builder = player.trackSelectionParameters.buildUpon()

        if (preferred == "none") {
            // "None" means no AUTO language pick, not "subtitles off" — never
            // disable under a selection the ENGINE still reports live (capture
            // 2026-09-04 23:39: the first-frame diagnostics write re-emitted
            // this with preferred=none ~40ms after the engine confirmed the
            // restored pick; the disable landed after override application and
            // the next selection pass dropped the text renderer). uiState alone
            // is stale-positive across a mid-session rebuild; the engine's own
            // selection is the truth. A sidecar addon subtitle keeps TEXT
            // already disabled, so re-disabling there is a no-op.
            val engineHasSelectedTextTrack = player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSelected(it) }
            }
            if (!engineHasSelectedTextTrack) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            builder.setPreferredTextLanguage(null)
        } else {
            val userDisabledSubtitles = trackSelection.autoSubtitleSelected &&
                    _uiState.value.selectedSubtitleTrackIndex == -1 &&
                    _uiState.value.selectedAddonSubtitle == null
            // Suppress ExoPlayer auto-select when forced mode is active —
            // our custom logic handles track selection.
            val useForcedSubtitles = _uiState.value.subtitleStyle.useForcedSubtitles
            val shouldSuppressExoAutoSelect = useForcedSubtitles && !trackSelection.autoSubtitleSelected
            if (!userDisabledSubtitles && !shouldSuppressExoAutoSelect) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            if (!shouldSuppressExoAutoSelect) {
                builder.setPreferredTextLanguage(preferred)
            }
        }

        // Update forced flag handling: when forced subtitles are disabled,
        // tell ExoPlayer to ignore SELECTION_FLAG_FORCED.
        val useForcedSubtitles = _uiState.value.subtitleStyle.useForcedSubtitles
        val currentFlags = player.trackSelectionParameters.ignoredTextSelectionFlags
        val newFlags = if (!useForcedSubtitles) {
            currentFlags or C.SELECTION_FLAG_FORCED
        } else {
            currentFlags and C.SELECTION_FLAG_FORCED.inv()
        }
        builder.setIgnoredTextSelectionFlags(newFlags)

        player.trackSelectionParameters = builder.build()
    }
}

/**
 * Publishes the OSD tech badges ([osdTechBadges]) from the video track fields, the first-frame HDR
 * label + Dolby Vision profile, the selected audio track ([osdAudioCodec]) and its output mode, the
 * renderer's frame rate against the AFR-applied display mode, the stream size, and this playback's
 * [PlaybackDegradation]s; the first degradation for a stream URL also raises the amber toast
 * ([PlayerUiState.degradedNotice]). Triggers: end of [updateAvailableTracks], first frame, the audio
 * renderer's input-format change (so a switched or refined codec lands), an output-mode change
 * ([refreshAudioOutputMode]), a track-AFR mode switch or AFR turned off, and the hash path filling a
 * missing size. ExoPlayer only: the mpv engine fills none of the track fields, so it publishes no
 * badges. Player (Main) thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.refreshOsdTechBadges() {
    if (isUsingMpvEngine()) {
        // mpv makes none of these recoveries, so an ExoPlayer-era notice no longer holds either.
        _uiState.update { s ->
            if (s.osdTechBadges.isEmpty() && s.degradedNotice == null) s
            else s.copy(osdTechBadges = emptyList(), degradedNotice = null)
        }
        return
    }
    val state = _uiState.value
    val selectedAudio = state.audioTracks.firstOrNull { it.isSelected }
    val frameRate = _exoPlayer?.videoFormat?.frameRate?.takeIf { it > 0f }
    val refreshHz = state.displayModeInfo?.refreshRate
    val diagnostics = lastPlaybackDiagnostics
    val degradations = buildSet {
        if (isAudioDisabledForCurrentPlayback) add(PlaybackDegradation.AUDIO_OFF)
        if (hasTriedAudioPcmFallback) add(PlaybackDegradation.AUDIO_PCM)
        if (isSafeAudioModeActiveForCurrentPlayback) add(PlaybackDegradation.AUDIO_SAFE)
        if (isDolbyVisionFallback(
                diagnostics.dvSourceProfile, osdVideoHdrType, diagnostics.dv7ModeRequested, diagnostics.displayDv
            )
        ) add(PlaybackDegradation.DOLBY_VISION)
        if (isVc1SoftwareFallbackActiveForCurrentPlayback) add(PlaybackDegradation.VC1_SOFTWARE)
        // While track AFR is switching, displayModeInfo is still the previous (or seeded) mode;
        // its finally refreshes again once the mode is applied.
        if (!afrTrackSwitchInFlight && !state.afrProbeRunning && isFrameRateMismatch(frameRate, refreshHz)) {
            add(PlaybackDegradation.FRAME_RATE)
        }
    }
    val badges = osdTechBadges(
        width = currentVideoTrackWidth,
        height = currentVideoTrackHeight,
        hdrType = osdVideoHdrType,
        audioCodec = osdAudioCodec(selectedAudio, _exoPlayer?.audioFormat),
        audioChannelCount = selectedAudio?.channelCount,
        sizeBytes = currentVideoSize,
        dvProfile = diagnostics.dvSourceProfile,
        dvEnhancementLayer = diagnostics.dvElType,
        audioOutput = state.audioOutputMode,
        frameRate = frameRate,
        displayRefreshHz = refreshHz,
        degradations = degradations
    )
    val step = degradedNoticeStep(
        raisedForCurrentUrl = degradedNoticeStreamUrl == currentStreamUrl,
        firstFrameRendered = hasRenderedFirstFrame,
        degradations = degradations
    )
    if (step is DegradedNoticeStep.Raise) degradedNoticeStreamUrl = currentStreamUrl
    val notice = when (step) {
        is DegradedNoticeStep.Raise -> degradedNoticeText(step.degradation, frameRate, refreshHz)
        DegradedNoticeStep.Keep -> state.degradedNotice
        DegradedNoticeStep.Clear -> null
    }
    _uiState.update { s ->
        if (s.osdTechBadges == badges && s.degradedNotice == notice) s
        else s.copy(osdTechBadges = badges, degradedNotice = notice)
    }
}

/** The toast copy for [degradation] (data st8); the UI adds the small Stats pointer. */
private fun PlayerRuntimeController.degradedNoticeText(
    degradation: PlaybackDegradation,
    frameRate: Float?,
    refreshHz: Float?
): String = when (degradation) {
    PlaybackDegradation.AUDIO_OFF -> context.getString(R.string.player_degraded_audio_off)
    PlaybackDegradation.AUDIO_PCM -> context.getString(R.string.player_degraded_audio_pcm)
    PlaybackDegradation.AUDIO_SAFE -> context.getString(R.string.player_degraded_audio_safe)
    PlaybackDegradation.DOLBY_VISION ->
        context.getString(R.string.player_degraded_dolby_vision, osdVideoHdrType.orEmpty())
    PlaybackDegradation.VC1_SOFTWARE -> context.getString(R.string.player_degraded_vc1)
    PlaybackDegradation.FRAME_RATE -> context.getString(
        R.string.player_degraded_frame_rate,
        frameRate?.let(::frameRateLabel).orEmpty(),
        refreshHz?.let(::frameRateLabel).orEmpty()
    )
}

/**
 * Re-derives [AudioOutputMode] from the sinks and publishes it when it changed (then the badges).
 * Called on the ExoPlayer progress tick: the MAT wrapper engages after isPlaying flips (nt32), so
 * one-shot audio events can read it too early. Null under mpv and until the sink is configured.
 * Player (Main) thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.refreshAudioOutputMode() {
    val sink = playbackSpeedAwareAudioSink
    val mode = if (isUsingMpvEngine()) null else audioOutputMode(
        matActive = matRoutingAudioSink?.isMatActive() == true,
        sinkInputMime = sink?.lastConfiguredInputFormat?.sampleMimeType,
        directPlayback = sink?.isDirectPlaybackActive() == true,
        sourceMime = _exoPlayer?.audioFormat?.sampleMimeType
    )
    if (mode == _uiState.value.audioOutputMode) return
    _uiState.update { it.copy(audioOutputMode = mode) }
    refreshOsdTechBadges()
}

/** A subtitle track named for the hearing impaired ("English (SDH)", "Hearing Impaired"). */
private val SDH_NAME_HINT = Regex("\\bSDH\\b|hearing[ -]impaired", RegexOption.IGNORE_CASE)
