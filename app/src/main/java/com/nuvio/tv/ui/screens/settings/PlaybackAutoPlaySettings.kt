@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.annotation.StringRes
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.core.sync.isLocalOnlyProfileSetting
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.saveable.rememberSaveable
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog

/** The Player & streams side-panel pickers (stage playback-picker). */
internal enum class StreamsPicker { MODE, ENGINE, SOURCE, ALLOWED_ADDONS, REGEX, REUSE_CACHE, THRESHOLD_MODE }

internal const val GROUP_STREAMS = "playback_streams"

/** Last link cache choices, in hours (as built). */
private val REUSE_CACHE_HOURS = listOf(1, 2, 3, 6, 12, 24, 48, 72, 168)

/** "Next episode at" percentage stepper: 97-100 % in half steps, stored as a float. */
private const val THRESHOLD_PERCENT_MIN_HALVES = 194
private const val THRESHOLD_PERCENT_MAX_HALVES = 200

/** "Next episode at" minutes stepper: 0-3.5 min in half steps. */
private const val THRESHOLD_MINUTES_MAX_HALVES = 7

/** Whether the player_settings [key] never syncs (the row ends its subtitle with "This TV"). */
internal fun playerSettingThisTv(key: String): Boolean =
    isLocalOnlyProfileSetting(PlayerSettingsDataStore.FEATURE, key)

/** A dimmed row's subtitle is its reason (Q3); a live row keeps [subtitle]. */
@Composable
internal fun dimmedSubtitle(@StringRes reason: Int?, subtitle: String?): String? =
    reason?.let { stringResource(it) } ?: subtitle

/**
 * Player & streams chip (stages playback-streams / -advanced): the Sources switch (Spec 97 X6), the
 * Auto-play core rows, the rest in the Advanced options group. Rows that depend on another setting
 * dim with the reason ([playbackRowDimReason]).
 */
internal fun LazyListScope.streamsItems(
    settings: PlayerSettings,
    onOpenPicker: (StreamsPicker) -> Unit,
    onSetTimeoutSeconds: (Int) -> Unit,
    onSetNextEpisodeEnabled: (Boolean) -> Unit,
    onSetAutoSwitchOnError: (Boolean) -> Unit,
    onSetEagerReadyEnabled: (Boolean) -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onSetFallbackEnabled: (Boolean) -> Unit,
    onSetStillWatchingEnabled: (Boolean) -> Unit,
    onSetStillWatchingThreshold: (Int) -> Unit,
    onSetPreferBingeGroup: (Boolean) -> Unit,
    onSetReuseBingeGroup: (Boolean) -> Unit,
    onSetThresholdPercent: (Float) -> Unit,
    onSetThresholdMinutes: (Float) -> Unit,
    onSetShowLoadingStatus: (Boolean) -> Unit,
    hideSourceNames: Boolean,
    onSetHideSourceNames: (Boolean) -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?
) {
    // Spec 97 X6 (sandbox s097-x6-hidden-names stage 1): first on the tab, a device setting.
    item(key = "streams_sources_section") {
        SettingsSectionLabel(text = stringResource(R.string.playback_section_sources))
    }
    item(key = "streams_hide_source_names") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_hide_source_names),
            subtitle = stringResource(R.string.playback_hide_source_names_sub),
            checked = hideSourceNames,
            onCheckedChange = onSetHideSourceNames,
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "streams_autoplay_section") {
        SettingsSectionLabel(text = stringResource(R.string.playback_section_autoplay))
    }
    item(key = "streams_mode") {
        SettingsValueRow(
            title = stringResource(R.string.playback_auto_stream_selection),
            value = streamAutoPlayModeLabel(settings.streamAutoPlayMode),
            onClick = { onOpenPicker(StreamsPicker.MODE) }
        )
    }
    item(key = "streams_timeout") {
        val seconds = settings.streamAutoPlayTimeoutSeconds
        SettingsStepperRow(
            title = stringResource(R.string.playback_selection_timeout),
            subtitle = stringResource(R.string.playback_selection_timeout_sub),
            values = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES,
            selected = seconds,
            valueText = when (seconds) {
                0 -> stringResource(R.string.autoplay_timeout_instant)
                PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED -> stringResource(R.string.autoplay_timeout_unlimited)
                else -> stringResource(R.string.playback_value_seconds, seconds)
            },
            onValueChange = onSetTimeoutSeconds
        )
    }
    item(key = "streams_next_episode") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_next_episode),
            subtitle = stringResource(R.string.playback_next_episode_sub),
            checked = settings.streamAutoPlayNextEpisodeEnabled,
            onCheckedChange = onSetNextEpisodeEnabled
        )
    }
    settingsAdvancedGroup(key = GROUP_STREAMS, count = 16, open = groupOpen, onToggle = onToggleGroup) {
        item(key = "streams_engine") {
            SettingsValueRow(
                title = stringResource(R.string.playback_internal_engine),
                value = internalEngineLabel(settings.internalPlayerEngine),
                onClick = { onOpenPicker(StreamsPicker.ENGINE) },
                thisTv = playerSettingThisTv("internal_player_engine")
            )
        }
        item(key = "streams_auto_switch") {
            SettingsSwitchRow(
                title = stringResource(R.string.playback_auto_switch_internal_player_on_error),
                subtitle = stringResource(R.string.playback_auto_switch_sub),
                checked = settings.autoSwitchInternalPlayerOnError,
                onCheckedChange = onSetAutoSwitchOnError,
                thisTv = playerSettingThisTv("auto_switch_internal_player_on_error")
            )
        }
        item(key = "streams_eager_ready") {
            SettingsSwitchRow(
                title = stringResource(R.string.autoplay_eager_ready_title),
                subtitle = stringResource(R.string.playback_eager_ready_sub),
                checked = settings.streamAutoPlayEagerReadyEnabled,
                onCheckedChange = onSetEagerReadyEnabled
            )
        }
        item(key = "streams_source_scope") {
            val reason = playbackRowDimReason(PlaybackRow.SOURCE_SCOPE, settings)
            SettingsValueRow(
                title = stringResource(R.string.playback_source_scope),
                subtitle = dimmedSubtitle(reason, null),
                value = sourceScopeLabel(settings.streamAutoPlaySource),
                onClick = { onOpenPicker(StreamsPicker.SOURCE) },
                enabled = reason == null
            )
        }
        item(key = "streams_allowed_addons") {
            val reason = playbackRowDimReason(PlaybackRow.ALLOWED_ADDONS, settings)
            val selected = settings.streamAutoPlaySelectedAddons
            SettingsValueRow(
                title = stringResource(R.string.playback_allowed_addons),
                subtitle = dimmedSubtitle(reason, null),
                value = if (selected.isEmpty()) {
                    stringResource(R.string.autoplay_all_addons)
                } else {
                    pluralStringResource(R.plurals.autoplay_addons_selected, selected.size, selected.size)
                },
                onClick = { onOpenPicker(StreamsPicker.ALLOWED_ADDONS) },
                enabled = reason == null
            )
        }
        item(key = "streams_regex") {
            val reason = playbackRowDimReason(PlaybackRow.REGEX, settings)
            SettingsValueRow(
                title = stringResource(R.string.autoplay_regex_title),
                subtitle = dimmedSubtitle(
                    reason,
                    settings.streamAutoPlayRegex.ifBlank { stringResource(R.string.autoplay_regex_placeholder) }
                ),
                value = null,
                onClick = { onOpenPicker(StreamsPicker.REGEX) },
                enabled = reason == null
            )
        }
        item(key = "streams_reuse_last_link") {
            SettingsSwitchRow(
                title = stringResource(R.string.playback_reuse_last_link),
                subtitle = stringResource(R.string.playback_reuse_last_link_sub),
                checked = settings.streamReuseLastLinkEnabled,
                onCheckedChange = onSetReuseLastLinkEnabled
            )
        }
        item(key = "streams_reuse_cache") {
            val reason = playbackRowDimReason(PlaybackRow.REUSE_CACHE, settings)
            SettingsValueRow(
                title = stringResource(R.string.autoplay_last_link_cache),
                subtitle = dimmedSubtitle(reason, null),
                value = formatReuseCacheDuration(settings.streamReuseLastLinkCacheHours),
                onClick = { onOpenPicker(StreamsPicker.REUSE_CACHE) },
                enabled = reason == null
            )
        }
        item(key = "streams_fallback") {
            val reason = playbackRowDimReason(PlaybackRow.FALLBACK, settings)
            SettingsSwitchRow(
                title = stringResource(R.string.autoplay_next_episode_fallback),
                subtitle = dimmedSubtitle(reason, stringResource(R.string.autoplay_next_episode_fallback_sub)),
                checked = settings.streamAutoPlayNextEpisodeFallbackEnabled,
                onCheckedChange = onSetFallbackEnabled,
                enabled = reason == null
            )
        }
        item(key = "streams_still_watching") {
            val reason = playbackRowDimReason(PlaybackRow.STILL_WATCHING, settings)
            SettingsSwitchRow(
                title = stringResource(R.string.still_watching_setting_title),
                subtitle = dimmedSubtitle(reason, stringResource(R.string.still_watching_setting_sub)),
                checked = settings.stillWatchingEnabled,
                onCheckedChange = onSetStillWatchingEnabled,
                enabled = reason == null
            )
        }
        item(key = "streams_still_watching_threshold") {
            val reason = playbackRowDimReason(PlaybackRow.STILL_WATCHING_THRESHOLD, settings)
            val threshold = settings.stillWatchingEpisodeThreshold
            SettingsStepperRow(
                title = stringResource(R.string.still_watching_threshold_title),
                subtitle = dimmedSubtitle(reason, stringResource(R.string.still_watching_threshold_sub)),
                value = threshold,
                min = PlayerSettings.MIN_STILL_WATCHING_EPISODE_THRESHOLD,
                max = PlayerSettings.MAX_STILL_WATCHING_EPISODE_THRESHOLD,
                step = 1,
                valueText = threshold.toString(),
                onValueChange = onSetStillWatchingThreshold,
                enabled = reason == null
            )
        }
        item(key = "streams_prefer_binge_group") {
            SettingsSwitchRow(
                title = stringResource(R.string.autoplay_prefer_binge_group),
                subtitle = stringResource(R.string.autoplay_prefer_binge_group_sub),
                checked = settings.streamAutoPlayPreferBingeGroupForNextEpisode,
                onCheckedChange = onSetPreferBingeGroup
            )
        }
        item(key = "streams_reuse_binge_group") {
            val reason = playbackRowDimReason(PlaybackRow.REUSE_BINGE_GROUP, settings)
            SettingsSwitchRow(
                title = stringResource(R.string.autoplay_reuse_binge_group),
                subtitle = dimmedSubtitle(reason, stringResource(R.string.autoplay_reuse_binge_group_sub)),
                checked = settings.streamAutoPlayReuseBingeGroup,
                onCheckedChange = onSetReuseBingeGroup,
                enabled = reason == null
            )
        }
        item(key = "streams_threshold_mode") {
            SettingsValueRow(
                title = stringResource(R.string.autoplay_threshold_mode),
                value = thresholdModeLabel(settings.nextEpisodeThresholdMode),
                onClick = { onOpenPicker(StreamsPicker.THRESHOLD_MODE) }
            )
        }
        item(key = "streams_threshold_value") {
            when (settings.nextEpisodeThresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> SettingsStepperRow(
                    title = stringResource(R.string.autoplay_threshold_pct_title),
                    subtitle = stringResource(R.string.autoplay_threshold_pct_sub),
                    value = (settings.nextEpisodeThresholdPercent * 2f).roundToInt(),
                    min = THRESHOLD_PERCENT_MIN_HALVES,
                    max = THRESHOLD_PERCENT_MAX_HALVES,
                    step = 1,
                    valueText = stringResource(
                        R.string.playback_value_percent,
                        formatHalfStepValue(settings.nextEpisodeThresholdPercent)
                    ),
                    onValueChange = { onSetThresholdPercent(it / 2f) }
                )
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> SettingsStepperRow(
                    title = stringResource(R.string.autoplay_threshold_min_title),
                    subtitle = stringResource(R.string.autoplay_threshold_pct_sub),
                    value = (settings.nextEpisodeThresholdMinutesBeforeEnd * 2f).roundToInt(),
                    min = 0,
                    max = THRESHOLD_MINUTES_MAX_HALVES,
                    step = 1,
                    valueText = stringResource(
                        R.string.playback_value_minutes,
                        formatHalfStepValue(settings.nextEpisodeThresholdMinutesBeforeEnd)
                    ),
                    onValueChange = { onSetThresholdMinutes(it / 2f) }
                )
            }
        }
        item(key = "streams_loading_status") {
            SettingsSwitchRow(
                title = stringResource(R.string.playback_show_loading_status),
                subtitle = stringResource(R.string.playback_show_loading_status_sub),
                checked = settings.showPlayerLoadingStatus,
                onCheckedChange = onSetShowLoadingStatus
            )
        }
    }
}

private fun formatHalfStepValue(value: Float): String {
    return if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@Composable
private fun streamAutoPlayModeLabel(mode: StreamAutoPlayMode): String = stringResource(
    when (mode) {
        StreamAutoPlayMode.MANUAL -> R.string.autoplay_mode_manual
        StreamAutoPlayMode.FIRST_STREAM -> R.string.autoplay_mode_first
        StreamAutoPlayMode.REGEX_MATCH -> R.string.autoplay_mode_regex
        StreamAutoPlayMode.QUALITY_RANK -> R.string.autoplay_mode_quality
    }
)

@Composable
private fun internalEngineLabel(engine: InternalPlayerEngine): String = stringResource(
    when (engine) {
        InternalPlayerEngine.EXOPLAYER -> R.string.playback_engine_exoplayer
        InternalPlayerEngine.MVP_PLAYER -> R.string.playback_engine_mvplayer
        InternalPlayerEngine.AUTO -> R.string.playback_player_auto
    }
)

@Composable
private fun sourceScopeLabel(source: StreamAutoPlaySource): String = stringResource(
    when (source) {
        StreamAutoPlaySource.ALL_SOURCES -> R.string.playback_scope_all
        StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> R.string.playback_scope_addons
    }
)

@Composable
private fun thresholdModeLabel(mode: NextEpisodeThresholdMode): String = stringResource(
    when (mode) {
        NextEpisodeThresholdMode.PERCENTAGE -> R.string.autoplay_threshold_pct
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> R.string.autoplay_threshold_min
    }
)

/** The open Player & streams picker as a side panel; OK saves and closes, Back closes. */
@Composable
internal fun StreamsPickerPanel(
    picker: StreamsPicker,
    settings: PlayerSettings,
    streamAddonNames: List<String>,
    onSetMode: (StreamAutoPlayMode) -> Unit,
    onSetSource: (StreamAutoPlaySource) -> Unit,
    onSetSelectedAddons: (Set<String>) -> Unit,
    onSetRegex: (String) -> Unit,
    onSetEngine: (InternalPlayerEngine) -> Unit,
    onSetReuseCacheHours: (Int) -> Unit,
    onSetThresholdMode: (NextEpisodeThresholdMode) -> Unit,
    onDismiss: () -> Unit
) {
    when (picker) {
        StreamsPicker.MODE -> SettingsPickerPanel(
            title = stringResource(R.string.playback_auto_stream_selection),
            options = listOf(
                SettingsPickerOption(
                    StreamAutoPlayMode.MANUAL,
                    stringResource(R.string.autoplay_mode_manual),
                    stringResource(R.string.playback_mode_manual_desc)
                ),
                SettingsPickerOption(
                    StreamAutoPlayMode.FIRST_STREAM,
                    stringResource(R.string.autoplay_mode_first),
                    stringResource(R.string.autoplay_mode_first_desc)
                ),
                SettingsPickerOption(
                    StreamAutoPlayMode.REGEX_MATCH,
                    stringResource(R.string.autoplay_mode_regex),
                    stringResource(R.string.playback_mode_regex_desc)
                ),
                SettingsPickerOption(
                    StreamAutoPlayMode.QUALITY_RANK,
                    stringResource(R.string.autoplay_mode_quality),
                    stringResource(R.string.autoplay_mode_quality_desc)
                )
            ),
            selectedValue = settings.streamAutoPlayMode,
            onSelect = onSetMode,
            onDismiss = onDismiss
        )
        StreamsPicker.ENGINE -> SettingsPickerPanel(
            title = stringResource(R.string.playback_internal_engine),
            options = listOf(
                SettingsPickerOption(
                    InternalPlayerEngine.EXOPLAYER,
                    stringResource(R.string.playback_engine_exoplayer),
                    stringResource(R.string.playback_engine_exoplayer_desc)
                ),
                SettingsPickerOption(
                    InternalPlayerEngine.MVP_PLAYER,
                    stringResource(R.string.playback_engine_mvplayer),
                    stringResource(R.string.playback_engine_mvplayer_desc)
                ),
                SettingsPickerOption(
                    InternalPlayerEngine.AUTO,
                    stringResource(R.string.playback_player_auto),
                    stringResource(R.string.playback_player_auto_desc)
                )
            ),
            selectedValue = settings.internalPlayerEngine,
            onSelect = onSetEngine,
            onDismiss = onDismiss
        )
        StreamsPicker.SOURCE -> SettingsPickerPanel(
            title = stringResource(R.string.playback_source_scope),
            options = listOf(
                SettingsPickerOption(
                    StreamAutoPlaySource.ALL_SOURCES,
                    stringResource(R.string.playback_scope_all),
                    stringResource(R.string.playback_scope_all_desc)
                ),
                SettingsPickerOption(
                    StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                    stringResource(R.string.playback_scope_addons),
                    stringResource(R.string.playback_scope_addons_desc)
                )
            ),
            selectedValue = settings.streamAutoPlaySource,
            onSelect = onSetSource,
            onDismiss = onDismiss
        )
        StreamsPicker.ALLOWED_ADDONS -> AllowedAddonsPanel(
            addonNames = streamAddonNames,
            selected = settings.streamAutoPlaySelectedAddons,
            onSelectionSaved = onSetSelectedAddons,
            onDismiss = onDismiss
        )
        StreamsPicker.REGEX -> RegexPatternPanel(
            current = settings.streamAutoPlayRegex,
            onSave = { pattern ->
                onSetRegex(pattern)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        StreamsPicker.REUSE_CACHE -> SettingsPickerPanel(
            title = stringResource(R.string.autoplay_last_link_cache),
            options = REUSE_CACHE_HOURS.map { hours -> SettingsPickerOption(hours, formatReuseCacheDuration(hours)) },
            selectedValue = settings.streamReuseLastLinkCacheHours,
            onSelect = onSetReuseCacheHours,
            onDismiss = onDismiss
        )
        StreamsPicker.THRESHOLD_MODE -> SettingsPickerPanel(
            title = stringResource(R.string.autoplay_threshold_mode),
            options = listOf(
                SettingsPickerOption(
                    NextEpisodeThresholdMode.PERCENTAGE,
                    stringResource(R.string.autoplay_threshold_pct),
                    stringResource(R.string.autoplay_threshold_pct_desc)
                ),
                SettingsPickerOption(
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END,
                    stringResource(R.string.autoplay_threshold_min),
                    stringResource(R.string.autoplay_threshold_min_desc)
                )
            ),
            selectedValue = settings.nextEpisodeThresholdMode,
            onSelect = onSetThresholdMode,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun formatReuseCacheDuration(hours: Int): String {
    return when {
        hours < 24 -> stringResource(
            if (hours == 1) R.string.cache_duration_hour_one else R.string.cache_duration_hour_other,
            hours
        )
        hours % 24 == 0 -> {
            val days = hours / 24
            stringResource(
                if (days == 1) R.string.cache_duration_day_one else R.string.cache_duration_day_other,
                days
            )
        }
        else -> {
            val days = hours / 24
            val remainingHours = hours % 24
            stringResource(R.string.cache_duration_days_hours, days, remainingHours)
        }
    }
}

/** Allowed addons (Q3 checklist panel): each tick saves; "All installed addons" clears the list. */
@Composable
private fun AllowedAddonsPanel(
    addonNames: List<String>,
    selected: Set<String>,
    onSelectionSaved: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val installed = selected.intersect(addonNames.toSet())
    SettingsChecklistPanel(
        title = stringResource(R.string.playback_allowed_addons),
        options = addonNames.map { SettingsPickerOption(it, it) },
        checked = installed,
        onCheckedChange = onSelectionSaved,
        onDismiss = {
            // Closing drops names no longer installed (as the old dialog's save on close did):
            // a stale-only set would show "All" here while the selector filters every addon out.
            if (installed != selected) onSelectionSaved(installed)
            onDismiss()
        },
        allLabel = stringResource(R.string.autoplay_all_addons),
        emptyText = stringResource(R.string.autoplay_no_items)
    )
}

/** The longest pattern the field takes (the presets stay under 80 characters). */
private const val REGEX_MAX_LENGTH = 200

/** The field's example while empty (a pattern, so not translated; as built). */
private const val REGEX_EXAMPLE = "4K|2160p|Remux"

/** Ready-made patterns (as built): label, pattern. */
internal val AUTO_PLAY_REGEX_PRESETS: List<Pair<Int, String>> = listOf(
    R.string.autoplay_regex_preset_any_1080p_plus to "(2160p|4k|1080p)",
    R.string.autoplay_regex_preset_4k_remux to "(2160p|4k|remux)",
    R.string.autoplay_regex_preset_1080p_standard to "(1080p|full\\s*hd)",
    R.string.autoplay_regex_preset_720p_smaller to "(720p|webrip|web-dl)",
    R.string.autoplay_regex_preset_web_sources to "(web[-\\s]?dl|webrip)",
    R.string.autoplay_regex_preset_bluray_quality to "(bluray|b[dr]rip|remux)",
    R.string.autoplay_regex_preset_hevc_x265 to "(hevc|x265|h\\.265)",
    R.string.autoplay_regex_preset_avc_x264 to "(x264|h\\.264|avc)",
    R.string.autoplay_regex_preset_hdr_dolby_vision to "(hdr|hdr10\\+?|dv|dolby\\s*vision)",
    R.string.autoplay_regex_preset_dolby_atmos_dts to "(atmos|truehd|dts[-\\s]?hd|dtsx?)",
    R.string.autoplay_regex_preset_english to "(\\beng\\b|english)",
    R.string.autoplay_regex_preset_no_cam_ts to "^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$",
    R.string.autoplay_regex_preset_no_remux_hdr to "(?is)^(?!.*\\b(hdr|hdr10|dv|dolby|vision|hevc|remux|2160p)\\b).+$"
)

/** Whether [pattern] (trimmed) can be saved: blank (no pattern) or a regex the selector can compile. */
internal fun isSavableAutoPlayRegex(pattern: String): Boolean {
    val value = pattern.trim()
    return value.isEmpty() || runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
}

/**
 * The regex pattern side panel: the field (focused, [REGEX_MAX_LENGTH] at most), the as-built hint
 * or the invalid-pattern line, then "No pattern" and the presets (OK puts one in the field),
 * Cancel / Save. Save hands the trimmed pattern to [onSave] unless it does not compile; an
 * unedited field just closes.
 */
@Composable
private fun RegexPatternPanel(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.settings
    val initial = current.take(REGEX_MAX_LENGTH)
    var draft by rememberSaveable { mutableStateOf(initial) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    fun put(pattern: String) {
        draft = pattern
        invalid = false
    }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = tokens.pickerWidth,
        insetVertical = tokens.pickerInsetVertical,
        initialFocus = fieldFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.autoplay_regex_title))
        MarqueeNameField(
            value = draft,
            onValueChange = ::put,
            maxLength = REGEX_MAX_LENGTH,
            placeholder = REGEX_EXAMPLE,
            focusRequester = fieldFocus,
            onDone = { saveFocus.requestFocus() }
        )
        Text(
            text = stringResource(if (invalid) R.string.autoplay_invalid_regex else R.string.autoplay_regex_matches),
            style = NuvioTheme.textStyles.settingsNote,
            color = if (invalid) NuvioTheme.colors.Error else NuvioTheme.marquee.onImage2,
            modifier = Modifier.padding(tokens.panelTextInset)
        )
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            item(key = "none") {
                PlayerPanelRow(
                    title = stringResource(R.string.playback_regex_none),
                    subtitle = stringResource(R.string.playback_regex_none_sub),
                    selected = draft.isBlank(),
                    onClick = { put("") },
                    trailingIconTint = NuvioTheme.marquee.readyText
                )
            }
            items(AUTO_PLAY_REGEX_PRESETS, key = { it.second }) { (label, pattern) ->
                PlayerPanelRow(
                    title = stringResource(label),
                    subtitle = pattern,
                    selected = draft == pattern,
                    onClick = { put(pattern) },
                    trailingIconTint = NuvioTheme.marquee.readyText,
                    subtitleMaxLines = Int.MAX_VALUE
                )
            }
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_save),
                onClick = {
                    when {
                        // Unedited: just close, so a stored pattern past the field's cap is never cut.
                        draft == initial -> onDismiss()
                        isSavableAutoPlayRegex(draft) -> onSave(draft.trim())
                        else -> invalid = true
                    }
                },
                modifier = Modifier.focusRequester(saveFocus)
            )
        }
    }
}
