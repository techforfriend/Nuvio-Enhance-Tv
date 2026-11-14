package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.util.languageCodeToName
import com.nuvio.tv.data.local.AFR_SETTLE_HOLD_MAX_SECONDS
import com.nuvio.tv.data.local.AFR_SETTLE_HOLD_MIN_SECONDS
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Audio & video side-panel pickers. */
internal enum class AudioVideoPicker { DV_HANDLING, AUDIO_LANGUAGE, SECONDARY_AUDIO_LANGUAGE, DECODER, CHANNELS, DENIED, MPV_HWDEC }

internal const val GROUP_AUDIO_VIDEO = "playback_audio_video"

/** Frame rate matching's segments, in order. */
private val FRAME_RATE_MATCHING_MODES = listOf(
    FrameRateMatchingMode.OFF,
    FrameRateMatchingMode.START,
    FrameRateMatchingMode.START_STOP
)

/**
 * Rows in the Audio & video group: secondary language + remember delay always, 17 ExoPlayer
 * rows (skip silence, decoder, downmix + its 2 options, tunneling, optical, 5 passthrough
 * formats, MAT, denied handling, DV5, HDR10+, inject), mpv hardware decoding.
 */
private fun audioVideoGroupCount(settings: PlayerSettings): Int {
    val engine = settings.internalPlayerEngine
    return 2 + (if (engine.usesExoPlayer) 17 else 0) + (if (engine.usesMpv) 1 else 0)
}

/**
 * Audio & video chip (stage playback-video): Video (frame rate matching with the capability
 * warning under it, resolution matching, Dolby Vision handling), Audio (preferred language), the
 * rest in the Advanced options group. Engine-only rows hide under the other engine (as built);
 * rows that need another choice dim with the reason ([playbackRowDimReason]).
 */
internal fun LazyListScope.audioVideoItems(
    settings: PlayerSettings,
    afrProblem: AfrProblem?,
    resolutionSwitchingSupported: Boolean,
    onOpenPicker: (AudioVideoPicker) -> Unit,
    onSetFrameRateMatchingMode: (FrameRateMatchingMode) -> Unit,
    onSetResolutionMatchingEnabled: (Boolean) -> Unit,
    onSetAfrSettleHoldSeconds: (Int) -> Unit,
    onDisableAfrAndResolution: () -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetRememberAudioDelayPerDevice: (Boolean) -> Unit,
    onSetDownmixEnabled: (Boolean) -> Unit,
    onSetMaintainOriginalAudioOnDownmix: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetForceOpticalPassthrough: (Boolean) -> Unit,
    onSetAllowAc3Passthrough: (Boolean) -> Unit,
    onSetAllowEac3Passthrough: (Boolean) -> Unit,
    onSetAllowTrueHdPassthrough: (Boolean) -> Unit,
    onSetMatPassthroughEnabled: (Boolean) -> Unit,
    onSetAllowDtsPassthrough: (Boolean) -> Unit,
    onSetAllowDtsHdPassthrough: (Boolean) -> Unit,
    onSetDv5ToDv81Enabled: (Boolean) -> Unit,
    onSetStripHdr10PlusSei: (Boolean) -> Unit,
    onSetInjectHdr10Sei: (Boolean) -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?,
    frameRateFocus: FocusRequester
) {
    val exo = settings.internalPlayerEngine.usesExoPlayer
    val mpv = settings.internalPlayerEngine.usesMpv

    item(key = "av_video_section") {
        SettingsSectionLabel(text = stringResource(R.string.video_section))
    }
    item(key = "av_frame_rate_matching") {
        SettingsSegmentRow(
            title = stringResource(R.string.playback_frame_rate_matching),
            subtitle = stringResource(R.string.playback_frame_rate_matching_sub),
            options = listOf(
                stringResource(R.string.playback_afr_off),
                stringResource(R.string.playback_afr_on_start),
                stringResource(R.string.playback_afr_start_stop)
            ),
            selectedIndex = FRAME_RATE_MATCHING_MODES.indexOf(settings.frameRateMatchingMode).coerceAtLeast(0),
            onSelect = { onSetFrameRateMatchingMode(FRAME_RATE_MATCHING_MODES[it]) },
            thisTv = playerSettingThisTv("frame_rate_matching_mode"),
            modifier = Modifier.optionalFocusRequester(entryFocus).focusRequester(frameRateFocus)
        )
    }
    if (afrProblem != null) {
        item(key = "av_afr_warning") {
            AfrCapabilityWarning(afrProblem)
        }
        item(key = "av_afr_fix") {
            SettingsValueRow(
                title = stringResource(afrFixLabel(afrProblem)),
                value = null,
                onClick = {
                    // The warning and this row leave with the problem: keep focus on the row above.
                    runCatching { frameRateFocus.requestFocus() }
                    when (afrProblem) {
                        AfrProblem.BOTH -> onDisableAfrAndResolution()
                        AfrProblem.FRAME_RATE -> onSetFrameRateMatchingMode(FrameRateMatchingMode.OFF)
                        AfrProblem.RESOLUTION -> onSetResolutionMatchingEnabled(false)
                    }
                },
                trailingIcon = null
            )
        }
    }
    item(key = "av_resolution_matching") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_resolution_matching),
            subtitle = stringResource(
                if (resolutionSwitchingSupported) R.string.playback_resolution_matching_desc
                else R.string.playback_resolution_matching_unsupported_sub
            ),
            checked = settings.resolutionMatchingEnabled,
            onCheckedChange = onSetResolutionMatchingEnabled,
            thisTv = playerSettingThisTv("resolution_matching_enabled")
        )
    }
    item(key = "av_afr_settle_hold") {
        val reason = playbackRowDimReason(PlaybackRow.AFR_SETTLE_HOLD, settings)
        val seconds = settings.afrSettleHoldSeconds
        SettingsStepperRow(
            title = stringResource(R.string.playback_afr_settle_hold),
            subtitle = dimmedSubtitle(reason, stringResource(R.string.playback_afr_settle_hold_sub)),
            value = seconds,
            min = AFR_SETTLE_HOLD_MIN_SECONDS,
            max = AFR_SETTLE_HOLD_MAX_SECONDS,
            step = 1,
            valueText = if (seconds == 0) stringResource(R.string.playback_afr_off)
            else stringResource(R.string.playback_value_seconds, seconds),
            onValueChange = onSetAfrSettleHoldSeconds,
            enabled = reason == null,
            // Approved mockup (Spec 62 D6): a dimmed row shows only its reason.
            thisTv = reason == null && playerSettingThisTv("afr_settle_hold_seconds")
        )
    }
    if (exo) {
        item(key = "av_dv_handling") {
            SettingsValueRow(
                title = stringResource(R.string.playback_dv_handling),
                value = stringResource(dvHandlingLabel(settings.dv7HandlingMode)),
                onClick = { onOpenPicker(AudioVideoPicker.DV_HANDLING) },
                thisTv = playerSettingThisTv("dv7_handling_mode")
            )
        }
    }
    item(key = "av_audio_section") {
        SettingsSectionLabel(text = stringResource(R.string.audio_section))
    }
    item(key = "av_audio_language") {
        SettingsValueRow(
            title = stringResource(R.string.playback_preferred_audio_lang),
            value = audioLanguageLabel(settings.preferredAudioLanguage),
            onClick = { onOpenPicker(AudioVideoPicker.AUDIO_LANGUAGE) }
        )
    }
    settingsAdvancedGroup(
        key = GROUP_AUDIO_VIDEO,
        count = audioVideoGroupCount(settings),
        open = groupOpen,
        onToggle = onToggleGroup
    ) {
        item(key = "av_passthrough_info") {
            SettingsNote(text = stringResource(R.string.audio_passthrough_info))
        }
        item(key = "av_secondary_audio_language") {
            SettingsValueRow(
                title = stringResource(R.string.sub_secondary_lang),
                value = settings.secondaryPreferredAudioLanguage?.let { audioLanguageLabel(it) }
                    ?: stringResource(R.string.sub_not_set),
                onClick = { onOpenPicker(AudioVideoPicker.SECONDARY_AUDIO_LANGUAGE) }
            )
        }
        if (exo) {
            playerSwitchItem(
                key = "av_skip_silence",
                title = R.string.audio_skip_silence,
                subtitle = R.string.audio_skip_silence_sub,
                checked = settings.skipSilence,
                onCheckedChange = onSetSkipSilence,
                settings = settings
            )
        }
        playerSwitchItem(
            key = "av_remember_audio_delay",
            title = R.string.audio_remember_delay_per_device,
            subtitle = R.string.audio_remember_delay_per_device_sub,
            checked = settings.rememberAudioDelayPerDevice,
            onCheckedChange = onSetRememberAudioDelayPerDevice,
            settings = settings,
            prefKey = "remember_audio_delay_per_device"
        )
        if (exo) {
            exoAudioItems(
                settings = settings,
                onOpenPicker = onOpenPicker,
                onSetDownmixEnabled = onSetDownmixEnabled,
                onSetMaintainOriginalAudioOnDownmix = onSetMaintainOriginalAudioOnDownmix,
                onSetTunnelingEnabled = onSetTunnelingEnabled,
                onSetForceOpticalPassthrough = onSetForceOpticalPassthrough,
                onSetAllowAc3Passthrough = onSetAllowAc3Passthrough,
                onSetAllowEac3Passthrough = onSetAllowEac3Passthrough,
                onSetAllowTrueHdPassthrough = onSetAllowTrueHdPassthrough,
                onSetMatPassthroughEnabled = onSetMatPassthroughEnabled,
                onSetAllowDtsPassthrough = onSetAllowDtsPassthrough,
                onSetAllowDtsHdPassthrough = onSetAllowDtsHdPassthrough
            )
            playerSwitchItem(
                key = "av_dv5_to_dv81",
                title = R.string.audio_dv5_to_dv81_title,
                subtitle = R.string.audio_dv5_to_dv81_sub,
                checked = settings.dv5ToDv81Enabled,
                onCheckedChange = onSetDv5ToDv81Enabled,
                settings = settings,
                prefKey = "experimental_dv5_to_dv81_enabled",
                dimRow = PlaybackRow.DV5_TO_DV81
            )
            playerSwitchItem(
                key = "av_strip_hdr10plus",
                title = R.string.audio_strip_hdr10plus_title,
                subtitle = R.string.audio_strip_hdr10plus_sub,
                checked = settings.stripHdr10PlusSei,
                onCheckedChange = onSetStripHdr10PlusSei,
                settings = settings,
                prefKey = "strip_hdr10plus_sei"
            )
            playerSwitchItem(
                key = "av_inject_hdr10",
                title = R.string.audio_inject_hdr10_sei_title,
                subtitle = R.string.audio_inject_hdr10_sei_sub,
                checked = settings.injectHdr10MetadataOnStrip,
                onCheckedChange = onSetInjectHdr10Sei,
                settings = settings,
                prefKey = "inject_hdr10_metadata_on_strip",
                dimRow = PlaybackRow.INJECT_HDR10
            )
        }
        if (mpv) {
            item(key = "av_mpv_hwdec") {
                SettingsValueRow(
                    title = stringResource(R.string.audio_mpv_hwdec_title),
                    value = stringResource(mpvHardwareDecodeLabel(settings.mpvHardwareDecodeMode)),
                    onClick = { onOpenPicker(AudioVideoPicker.MPV_HWDEC) },
                    thisTv = playerSettingThisTv("mpv_hardware_decode_mode")
                )
            }
        }
    }
}

/** The ExoPlayer audio rows of the group: decoders, downmix, tunneling, passthrough. */
private fun LazyListScope.exoAudioItems(
    settings: PlayerSettings,
    onOpenPicker: (AudioVideoPicker) -> Unit,
    onSetDownmixEnabled: (Boolean) -> Unit,
    onSetMaintainOriginalAudioOnDownmix: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetForceOpticalPassthrough: (Boolean) -> Unit,
    onSetAllowAc3Passthrough: (Boolean) -> Unit,
    onSetAllowEac3Passthrough: (Boolean) -> Unit,
    onSetAllowTrueHdPassthrough: (Boolean) -> Unit,
    onSetMatPassthroughEnabled: (Boolean) -> Unit,
    onSetAllowDtsPassthrough: (Boolean) -> Unit,
    onSetAllowDtsHdPassthrough: (Boolean) -> Unit
) {
    item(key = "av_advanced_warning") {
        SettingsNote(text = stringResource(R.string.audio_advanced_warning))
    }
    item(key = "av_decoder_priority") {
        SettingsValueRow(
            title = stringResource(R.string.audio_decoder_priority),
            value = stringResource(decoderPriorityLabel(settings.decoderPriority)),
            onClick = { onOpenPicker(AudioVideoPicker.DECODER) },
            thisTv = playerSettingThisTv("decoder_priority")
        )
    }
    playerSwitchItem(
        key = "av_downmix",
        title = R.string.audio_enable_downmix_title,
        subtitle = R.string.audio_enable_downmix_subtitle,
        checked = settings.downmixEnabled,
        onCheckedChange = onSetDownmixEnabled,
        settings = settings,
        prefKey = "downmix_enabled",
        dimRow = PlaybackRow.DOWNMIX
    )
    item(key = "av_downmix_channels") {
        val reason = playbackRowDimReason(PlaybackRow.DOWNMIX_OPTIONS, settings)
        SettingsValueRow(
            title = stringResource(R.string.audio_number_of_channels),
            subtitle = dimmedSubtitle(reason, null),
            value = settings.audioOutputChannels.displayLabel,
            onClick = { onOpenPicker(AudioVideoPicker.CHANNELS) },
            enabled = reason == null,
            thisTv = playerSettingThisTv("audio_output_channels")
        )
    }
    playerSwitchItem(
        key = "av_downmix_maintain_original",
        title = R.string.audio_maintain_original_audio_on_downmix_title,
        subtitle = R.string.audio_maintain_original_audio_on_downmix_subtitle,
        checked = settings.maintainOriginalAudioOnDownmix,
        onCheckedChange = onSetMaintainOriginalAudioOnDownmix,
        settings = settings,
        prefKey = "maintain_original_audio_on_downmix",
        dimRow = PlaybackRow.DOWNMIX_OPTIONS
    )
    playerSwitchItem(
        key = "av_tunneling",
        title = R.string.audio_tunneled,
        subtitle = R.string.audio_tunneled_sub,
        checked = settings.tunnelingEnabled,
        onCheckedChange = onSetTunnelingEnabled,
        settings = settings,
        prefKey = "tunneling_enabled",
        dimRow = PlaybackRow.TUNNELING
    )
    playerSwitchItem(
        key = "av_optical",
        title = R.string.audio_force_optical_passthrough,
        subtitle = R.string.audio_force_optical_passthrough_sub,
        checked = settings.forceOpticalPassthrough,
        onCheckedChange = onSetForceOpticalPassthrough,
        settings = settings,
        prefKey = "force_optical_passthrough",
        dimRow = PlaybackRow.OPTICAL
    )
    playerSwitchItem(
        key = "av_passthrough_ac3",
        title = R.string.audio_passthrough_ac3,
        subtitle = R.string.audio_passthrough_ac3_sub,
        checked = settings.allowAc3Passthrough,
        onCheckedChange = onSetAllowAc3Passthrough,
        settings = settings,
        prefKey = "allow_ac3_passthrough"
    )
    playerSwitchItem(
        key = "av_passthrough_eac3",
        title = R.string.audio_passthrough_eac3,
        subtitle = R.string.audio_passthrough_eac3_sub,
        checked = settings.allowEac3Passthrough,
        onCheckedChange = onSetAllowEac3Passthrough,
        settings = settings,
        prefKey = "allow_eac3_passthrough"
    )
    playerSwitchItem(
        key = "av_passthrough_truehd",
        title = R.string.audio_passthrough_truehd,
        subtitle = R.string.audio_passthrough_truehd_sub,
        checked = settings.allowTrueHdPassthrough,
        onCheckedChange = onSetAllowTrueHdPassthrough,
        settings = settings,
        prefKey = "allow_truehd_passthrough"
    )
    // MAT wraps TrueHD, so it sits under that switch and needs it on.
    playerSwitchItem(
        key = "av_mat_passthrough",
        title = R.string.audio_mat_passthrough,
        subtitle = R.string.audio_mat_passthrough_sub,
        checked = settings.matPassthroughEnabled,
        onCheckedChange = onSetMatPassthroughEnabled,
        settings = settings,
        prefKey = "mat_passthrough_enabled",
        dimRow = PlaybackRow.MAT
    )
    playerSwitchItem(
        key = "av_passthrough_dts",
        title = R.string.audio_passthrough_dts,
        subtitle = R.string.audio_passthrough_dts_sub,
        checked = settings.allowDtsPassthrough,
        onCheckedChange = onSetAllowDtsPassthrough,
        settings = settings,
        prefKey = "allow_dts_passthrough"
    )
    playerSwitchItem(
        key = "av_passthrough_dtshd",
        title = R.string.audio_passthrough_dtshd,
        subtitle = R.string.audio_passthrough_dtshd_sub,
        checked = settings.allowDtsHdPassthrough,
        onCheckedChange = onSetAllowDtsHdPassthrough,
        settings = settings,
        prefKey = "allow_dts_hd_passthrough"
    )
    item(key = "av_denied_handling") {
        SettingsValueRow(
            title = stringResource(R.string.denied_handling_title),
            value = stringResource(deniedHandlingLabel(settings.deniedCodecHandling)),
            onClick = { onOpenPicker(AudioVideoPicker.DENIED) },
            thisTv = playerSettingThisTv("denied_codec_handling")
        )
    }
}

/**
 * A player switch row: "This TV" when [prefKey] never syncs, dimmed with the reason for [dimRow].
 * A dimmed switch reads off, so a stored value that has no effect never reads as active.
 */
private fun LazyListScope.playerSwitchItem(
    key: String,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    settings: PlayerSettings,
    prefKey: String? = null,
    dimRow: PlaybackRow? = null
) {
    item(key = key) {
        val reason = dimRow?.let { playbackRowDimReason(it, settings) }
        SettingsSwitchRow(
            title = stringResource(title),
            subtitle = dimmedSubtitle(reason, stringResource(subtitle)),
            checked = checked && reason == null,
            onCheckedChange = onCheckedChange,
            enabled = reason == null,
            thisTv = prefKey != null && playerSettingThisTv(prefKey)
        )
    }
}

/** The display cannot do what a matching switch asks: a quiet warning panel (copy as built). */
@Composable
private fun AfrCapabilityWarning(problem: AfrProblem) {
    val body = when (problem) {
        AfrProblem.BOTH -> R.string.playback_afr_capability_both_problem_body
        AfrProblem.FRAME_RATE -> R.string.playback_afr_capability_only_afr_unsupported_body
        AfrProblem.RESOLUTION -> R.string.playback_afr_capability_only_res_unsupported_body
    }
    SettingsResultsPanel {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = NuvioPrimitives.warning,
                modifier = Modifier.size(NuvioTheme.marquee.formRow.iconSize)
            )
            Text(
                text = stringResource(R.string.playback_afr_capability_unsupported_title),
                style = NuvioTheme.textStyles.settingsResultLabel,
                color = NuvioPrimitives.warning
            )
        }
        Text(
            text = stringResource(body),
            style = NuvioTheme.textStyles.settingsNote,
            color = NuvioTheme.marquee.onImage2
        )
    }
}

@StringRes
private fun afrFixLabel(problem: AfrProblem): Int = when (problem) {
    AfrProblem.BOTH -> R.string.playback_afr_capability_disable_both_button
    AfrProblem.FRAME_RATE -> R.string.playback_afr_capability_disable_button
    AfrProblem.RESOLUTION -> R.string.playback_afr_capability_disable_resolution_button
}

@StringRes
private fun dvHandlingLabel(mode: Dv7HandlingMode): Int = when (mode) {
    Dv7HandlingMode.AUTO -> R.string.dv7_mode_auto
    Dv7HandlingMode.HDR10_BASE_LAYER -> R.string.dv7_mode_hdr10_base_layer
    Dv7HandlingMode.DV81_LIBDOVI -> R.string.dv7_mode_dv81_libdovi
    Dv7HandlingMode.STRIP_DV -> R.string.dv7_mode_strip_dv
    Dv7HandlingMode.OFF -> R.string.dv7_mode_off
}

@StringRes
private fun decoderPriorityLabel(priority: Int): Int = when (priority) {
    0 -> R.string.audio_decoder_device_only
    2 -> R.string.audio_decoder_prefer_app
    else -> R.string.audio_decoder_prefer_device
}

@StringRes
private fun deniedHandlingLabel(mode: DeniedCodecHandling): Int = when (mode) {
    DeniedCodecHandling.DECODE_PCM -> R.string.denied_mode_pcm
    DeniedCodecHandling.TRANSCODE_AC3 -> R.string.denied_mode_ac3
}

@StringRes
private fun mpvHardwareDecodeLabel(mode: MpvHardwareDecodeMode): Int = when (mode) {
    MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> R.string.audio_mpv_hwdec_legacy_direct_copy
    MpvHardwareDecodeMode.AUTO_SAFE -> R.string.audio_mpv_hwdec_auto_safe
    MpvHardwareDecodeMode.HARDWARE_COPY -> R.string.audio_mpv_hwdec_hardware_copy
    MpvHardwareDecodeMode.HARDWARE_DIRECT -> R.string.audio_mpv_hwdec_hardware_direct
    MpvHardwareDecodeMode.DISABLED -> R.string.audio_mpv_hwdec_disabled
}

/** A stored audio language: the special choices by name, else the language's name. */
@Composable
private fun audioLanguageLabel(code: String): String = when {
    code == AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
    code == AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
    code.equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) -> stringResource(R.string.audio_lang_original)
    else -> languageCodeToName(code)
}

/** The open Audio & video picker as a side panel; OK saves and closes, Back closes. */
@Composable
internal fun AudioVideoPickerPanel(
    picker: AudioVideoPicker,
    settings: PlayerSettings,
    onSetDv7HandlingMode: (Dv7HandlingMode) -> Unit,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetAudioOutputChannels: (AudioOutputChannels) -> Unit,
    onSetDeniedHandling: (DeniedCodecHandling) -> Unit,
    onSetMpvHardwareDecodeMode: (MpvHardwareDecodeMode) -> Unit,
    onDismiss: () -> Unit
) {
    when (picker) {
        AudioVideoPicker.DV_HANDLING -> SettingsPickerPanel(
            title = stringResource(R.string.playback_dv_handling),
            caption = stringResource(R.string.dv7_handling_dialog_subtitle),
            options = listOf(
                Dv7HandlingMode.AUTO to R.string.dv7_mode_auto_desc,
                Dv7HandlingMode.HDR10_BASE_LAYER to R.string.dv7_mode_hdr10_base_layer_desc,
                Dv7HandlingMode.DV81_LIBDOVI to R.string.dv7_mode_dv81_libdovi_desc,
                Dv7HandlingMode.STRIP_DV to R.string.dv7_mode_strip_dv_desc,
                Dv7HandlingMode.OFF to R.string.dv7_mode_off_desc
            ).map { (mode, desc) ->
                SettingsPickerOption(mode, stringResource(dvHandlingLabel(mode)), stringResource(desc))
            },
            selectedValue = settings.dv7HandlingMode,
            onSelect = onSetDv7HandlingMode,
            onDismiss = onDismiss
        )
        AudioVideoPicker.AUDIO_LANGUAGE -> {
            val originalHint = stringResource(R.string.audio_lang_original_hint)
            val special = listOf(AudioLanguageOption.DEFAULT, AudioLanguageOption.DEVICE, AudioLanguageOption.ORIGINAL)
            SettingsPickerPanel(
                title = stringResource(R.string.playback_preferred_audio_lang),
                options = special.map { code ->
                    SettingsPickerOption(
                        value = code,
                        title = audioLanguageLabel(code),
                        description = originalHint.takeIf { code == AudioLanguageOption.ORIGINAL }
                    )
                } + AVAILABLE_SUBTITLE_LANGUAGES.sortedBy { it.displayName.lowercase() }.map {
                    SettingsPickerOption(value = it.code, title = it.displayName, trailing = it.code.uppercase())
                },
                selectedValue = settings.preferredAudioLanguage,
                onSelect = onSetPreferredAudioLanguage,
                onDismiss = onDismiss
            )
        }
        AudioVideoPicker.SECONDARY_AUDIO_LANGUAGE -> LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = settings.secondaryPreferredAudioLanguage,
            showNoneOption = true,
            extraOptions = listOf(AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)),
            onLanguageSelected = onSetSecondaryPreferredAudioLanguage,
            onDismiss = onDismiss
        )
        AudioVideoPicker.DECODER -> SettingsPickerPanel(
            title = stringResource(R.string.audio_decoder_priority),
            caption = stringResource(R.string.audio_decoder_controls),
            options = listOf(
                0 to R.string.audio_decoder_device_only_desc,
                1 to R.string.audio_decoder_prefer_device_desc,
                2 to R.string.audio_decoder_prefer_app_desc
            ).map { (priority, desc) ->
                SettingsPickerOption(priority, stringResource(decoderPriorityLabel(priority)), stringResource(desc))
            },
            selectedValue = settings.decoderPriority,
            onSelect = onSetDecoderPriority,
            onDismiss = onDismiss
        )
        AudioVideoPicker.CHANNELS -> SettingsPickerPanel(
            title = stringResource(R.string.audio_number_of_channels),
            caption = stringResource(R.string.audio_number_of_channels_desc),
            options = AudioOutputChannels.entries.map { SettingsPickerOption(it, it.displayLabel) },
            selectedValue = settings.audioOutputChannels,
            onSelect = onSetAudioOutputChannels,
            onDismiss = onDismiss
        )
        AudioVideoPicker.DENIED -> SettingsPickerPanel(
            title = stringResource(R.string.denied_handling_title),
            caption = stringResource(R.string.denied_handling_dialog_subtitle),
            options = listOf(
                DeniedCodecHandling.DECODE_PCM to R.string.denied_mode_pcm_desc,
                DeniedCodecHandling.TRANSCODE_AC3 to R.string.denied_mode_ac3_desc
            ).map { (mode, desc) ->
                SettingsPickerOption(mode, stringResource(deniedHandlingLabel(mode)), stringResource(desc))
            },
            selectedValue = settings.deniedCodecHandling,
            onSelect = onSetDeniedHandling,
            onDismiss = onDismiss
        )
        AudioVideoPicker.MPV_HWDEC -> SettingsPickerPanel(
            title = stringResource(R.string.audio_mpv_hwdec_title),
            caption = stringResource(R.string.audio_mpv_hwdec_dialog_subtitle),
            options = listOf(
                MpvHardwareDecodeMode.AUTO_SAFE to R.string.audio_mpv_hwdec_auto_safe_desc,
                MpvHardwareDecodeMode.HARDWARE_COPY to R.string.audio_mpv_hwdec_hardware_copy_desc,
                MpvHardwareDecodeMode.HARDWARE_DIRECT to R.string.audio_mpv_hwdec_hardware_direct_desc,
                MpvHardwareDecodeMode.DISABLED to R.string.audio_mpv_hwdec_disabled_desc,
                MpvHardwareDecodeMode.LEGACY_DIRECT_COPY to R.string.audio_mpv_hwdec_legacy_direct_copy_desc
            ).map { (mode, desc) ->
                SettingsPickerOption(mode, stringResource(mpvHardwareDecodeLabel(mode)), stringResource(desc))
            },
            selectedValue = settings.mpvHardwareDecodeMode,
            onSelect = onSetMpvHardwareDecodeMode,
            onDismiss = onDismiss
        )
    }
}
