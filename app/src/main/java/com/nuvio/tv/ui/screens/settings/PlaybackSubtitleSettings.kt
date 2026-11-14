package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.util.languageCodeToName
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.screens.player.subtitleEdgeLabel
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Subtitles side panels: the two language pickers and the four colour panels (HDR text colour included). */
internal enum class SubtitlePicker {
    PREFERRED_LANGUAGE, SECONDARY_LANGUAGE, TEXT_COLOR, BACKGROUND_COLOR, OUTLINE_COLOR, HDR_TEXT_COLOR
}

internal const val GROUP_SUBTITLES = "playback_subtitles"

/** The stored preferred language meaning "no subtitles by default". */
private const val NO_PREFERRED_LANGUAGE = "none"

/**
 * Subtitles chip (stage playback-subs): languages, forced and addon subtitles, then Style (size,
 * offset, bold, text and background colour, text edge), then HDR and Dolby Vision (Spec 62 H2,
 * sandbox spec-62-h2-hdr-subtitles stages 1-2: the switch, HDR text colour, HDR brightness, Dim
 * player controls; the three dim until the switch is on); Outline colour, libass and the language
 * filters in the Advanced options group. Outline colour dims unless the edge is Outline.
 * [onHdrRowFocused] reports focus on the HDR rows, for the preview's HDR look.
 */
internal fun LazyListScope.subtitleItems(
    settings: PlayerSettings,
    onOpenPicker: (SubtitlePicker) -> Unit,
    onSetUseForcedSubtitles: (Boolean) -> Unit,
    onSetAddonSubtitlesEnabled: (Boolean) -> Unit,
    onSetSize: (Int) -> Unit,
    onSetVerticalOffset: (Int) -> Unit,
    onSetBold: (Boolean) -> Unit,
    onSetEdgeStyle: (SubtitleEdgeStyle) -> Unit,
    onSetHdrStyleEnabled: (Boolean) -> Unit,
    onSetHdrBrightness: (Int) -> Unit,
    onSetHdrDimPlayerControls: (Boolean) -> Unit,
    onHdrRowFocused: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetShowOnlyPreferredLanguages: (Boolean) -> Unit,
    onSetStripSdh: (Boolean) -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?
) {
    val style = settings.subtitleStyle
    item(key = "subs_preferred_language") {
        SettingsValueRow(
            title = stringResource(R.string.sub_preferred_lang),
            value = if (style.preferredLanguage == NO_PREFERRED_LANGUAGE) {
                stringResource(R.string.action_none)
            } else {
                // Any code, listed or not, gets its own name.
                languageCodeToName(style.preferredLanguage)
            },
            onClick = { onOpenPicker(SubtitlePicker.PREFERRED_LANGUAGE) },
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "subs_secondary_language") {
        SettingsValueRow(
            title = stringResource(R.string.sub_secondary_lang),
            value = style.secondaryPreferredLanguage?.let(::languageCodeToName)
                ?: stringResource(R.string.sub_not_set),
            onClick = { onOpenPicker(SubtitlePicker.SECONDARY_LANGUAGE) }
        )
    }
    item(key = "subs_forced") {
        SettingsSwitchRow(
            title = stringResource(R.string.sub_use_forced_subtitles),
            subtitle = stringResource(R.string.sub_use_forced_subtitles_desc),
            checked = style.useForcedSubtitles,
            onCheckedChange = onSetUseForcedSubtitles
        )
    }
    item(key = "subs_addon") {
        SettingsSwitchRow(
            title = stringResource(R.string.sub_addon_subtitles),
            subtitle = stringResource(R.string.sub_addon_subtitles_desc),
            checked = settings.addonSubtitlesEnabled,
            onCheckedChange = onSetAddonSubtitlesEnabled
        )
    }
    item(key = "subs_style_section") {
        SettingsSectionLabel(text = stringResource(R.string.sub_style_section))
    }
    item(key = "subs_size") {
        SettingsStepperRow(
            title = stringResource(R.string.sub_size),
            value = style.size,
            min = 50,
            max = 200,
            step = 10,
            valueText = "${style.size}%",
            onValueChange = onSetSize
        )
    }
    item(key = "subs_offset") {
        SettingsStepperRow(
            title = stringResource(R.string.sub_offset),
            value = style.verticalOffset,
            min = -20,
            max = 50,
            step = 1,
            valueText = "${style.verticalOffset}%",
            onValueChange = onSetVerticalOffset
        )
    }
    item(key = "subs_bold") {
        SettingsSwitchRow(
            title = stringResource(R.string.sub_bold),
            checked = style.bold,
            onCheckedChange = onSetBold
        )
    }
    item(key = "subs_text_colour") {
        SubtitleColorRow(
            title = R.string.sub_text_colour,
            argb = style.textColor,
            target = SubtitleColorTarget.TEXT,
            onClick = { onOpenPicker(SubtitlePicker.TEXT_COLOR) }
        )
    }
    item(key = "subs_background") {
        SubtitleColorRow(
            title = R.string.sub_background,
            argb = style.backgroundColor,
            target = SubtitleColorTarget.BACKGROUND,
            onClick = { onOpenPicker(SubtitlePicker.BACKGROUND_COLOR) }
        )
    }
    item(key = "subs_text_edge") {
        val edges = SubtitleEdgeStyle.entries
        SettingsSegmentRow(
            title = stringResource(R.string.subtitle_edge_style),
            options = edges.map { subtitleEdgeLabel(it) },
            selectedIndex = edges.indexOf(style.edgeStyle).coerceAtLeast(0),
            onSelect = { onSetEdgeStyle(edges[it]) }
        )
    }
    item(key = "subs_hdr_section") {
        SettingsSectionLabel(text = stringResource(R.string.sub_hdr_section))
    }
    val hdrFocus = Modifier.onFocusChanged { onHdrRowFocused(it.hasFocus) }
    item(key = "subs_hdr_style") {
        SettingsSwitchRow(
            title = stringResource(R.string.sub_hdr_style),
            subtitle = stringResource(R.string.sub_hdr_style_sub),
            checked = style.hdrStyleEnabled,
            onCheckedChange = onSetHdrStyleEnabled,
            modifier = hdrFocus
        )
    }
    item(key = "subs_hdr_text_colour") {
        SubtitleColorRow(
            title = R.string.sub_hdr_text_colour,
            argb = style.hdrTextColor,
            target = SubtitleColorTarget.TEXT,
            onClick = { onOpenPicker(SubtitlePicker.HDR_TEXT_COLOR) },
            dimReason = playbackRowDimReason(PlaybackRow.HDR_SUBTITLE_STYLE, settings),
            modifier = hdrFocus
        )
    }
    item(key = "subs_hdr_brightness") {
        val reason = playbackRowDimReason(PlaybackRow.HDR_SUBTITLE_STYLE, settings)
        SettingsStepperRow(
            title = stringResource(R.string.sub_hdr_brightness),
            subtitle = dimmedSubtitle(reason, stringResource(R.string.sub_hdr_brightness_sub)),
            value = style.hdrBrightnessPercent,
            min = SubtitleStyleSettings.HDR_BRIGHTNESS_MIN,
            max = SubtitleStyleSettings.HDR_BRIGHTNESS_MAX,
            step = SubtitleStyleSettings.HDR_BRIGHTNESS_STEP,
            valueText = "${style.hdrBrightnessPercent}%",
            onValueChange = onSetHdrBrightness,
            enabled = reason == null,
            modifier = hdrFocus
        )
    }
    item(key = "subs_hdr_dim_controls") {
        val reason = playbackRowDimReason(PlaybackRow.HDR_SUBTITLE_STYLE, settings)
        SettingsSwitchRow(
            title = stringResource(R.string.sub_hdr_dim_controls),
            subtitle = dimmedSubtitle(reason, stringResource(R.string.sub_hdr_dim_controls_sub)),
            checked = style.hdrDimPlayerControls,
            onCheckedChange = onSetHdrDimPlayerControls,
            enabled = reason == null,
            modifier = hdrFocus
        )
    }
    settingsAdvancedGroup(key = GROUP_SUBTITLES, count = 4, open = groupOpen, onToggle = onToggleGroup) {
        item(key = "subs_outline_colour") {
            val reason = playbackRowDimReason(PlaybackRow.OUTLINE_COLOR, settings)
            SubtitleColorRow(
                title = R.string.sub_outline_colour,
                argb = style.outlineColor,
                target = SubtitleColorTarget.OUTLINE,
                onClick = { onOpenPicker(SubtitlePicker.OUTLINE_COLOR) },
                dimReason = reason
            )
        }
        item(key = "subs_libass") {
            SettingsSwitchRow(
                title = stringResource(R.string.sub_libass),
                subtitle = stringResource(R.string.sub_libass_sub),
                checked = settings.useLibass,
                onCheckedChange = onSetUseLibass,
                thisTv = playerSettingThisTv("use_libass")
            )
        }
        item(key = "subs_only_preferred") {
            SettingsSwitchRow(
                title = stringResource(R.string.sub_show_only_preferred_languages),
                subtitle = stringResource(R.string.sub_show_only_preferred_languages_desc),
                checked = style.showOnlyPreferredLanguages,
                onCheckedChange = onSetShowOnlyPreferredLanguages
            )
        }
        item(key = "subs_strip_sdh") {
            SettingsSwitchRow(
                title = stringResource(R.string.sub_strip_sdh),
                subtitle = stringResource(R.string.sub_strip_sdh_desc),
                checked = style.stripSdh,
                onCheckedChange = onSetStripSdh
            )
        }
    }
}

/** A colour row: the stored colour's chip and name; dimmed with [dimReason] as its subtitle. */
@Composable
private fun SubtitleColorRow(
    @StringRes title: Int,
    argb: Int,
    target: SubtitleColorTarget,
    onClick: () -> Unit,
    @StringRes dimReason: Int? = null,
    modifier: Modifier = Modifier
) {
    SettingsColorRow(
        title = stringResource(title),
        color = Color(argb),
        value = subtitleColorLabel(argb, target),
        onClick = onClick,
        modifier = modifier,
        subtitle = dimmedSubtitle(dimReason, null),
        enabled = dimReason == null
    )
}

/** "Name", "Name · N%" while partly transparent, "#RRGGBB" (with the same suffix) off the list. */
@Composable
private fun subtitleColorLabel(argb: Int, target: SubtitleColorTarget): String {
    val name = subtitleSwatchFor(argb, target.swatches)?.let { stringResource(it.name) } ?: subtitleColorHex(argb)
    return if (subtitleOpacityShown(argb)) {
        stringResource(R.string.sub_colour_with_opacity, name, subtitleOpacityPercent(argb))
    } else {
        name
    }
}

/** The open Subtitles panel; a picker saves and closes on OK, Back closes. */
@Composable
internal fun SubtitlePickerPanel(
    picker: SubtitlePicker,
    settings: PlayerSettings,
    onSetPreferredLanguage: (String) -> Unit,
    onSetSecondaryLanguage: (String?) -> Unit,
    onSetTextColor: (Int) -> Unit,
    onSetBackgroundColor: (Int) -> Unit,
    onSetOutlineColor: (Int) -> Unit,
    onSetHdrTextColor: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val style = settings.subtitleStyle
    when (picker) {
        SubtitlePicker.PREFERRED_LANGUAGE -> LanguageSelectionDialog(
            title = stringResource(R.string.sub_preferred_lang),
            selectedLanguage = style.preferredLanguage.takeUnless { it == NO_PREFERRED_LANGUAGE },
            showNoneOption = true,
            onLanguageSelected = { onSetPreferredLanguage(it ?: NO_PREFERRED_LANGUAGE) },
            onDismiss = onDismiss
        )
        SubtitlePicker.SECONDARY_LANGUAGE -> LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = style.secondaryPreferredLanguage,
            showNoneOption = true,
            onLanguageSelected = onSetSecondaryLanguage,
            onDismiss = onDismiss
        )
        SubtitlePicker.TEXT_COLOR -> SubtitleColorPanel(
            title = stringResource(R.string.sub_text_colour),
            target = SubtitleColorTarget.TEXT,
            stored = style.textColor,
            onSetColor = onSetTextColor,
            onDismiss = onDismiss
        )
        SubtitlePicker.BACKGROUND_COLOR -> SubtitleColorPanel(
            title = stringResource(R.string.sub_background),
            target = SubtitleColorTarget.BACKGROUND,
            stored = style.backgroundColor,
            onSetColor = onSetBackgroundColor,
            onDismiss = onDismiss
        )
        SubtitlePicker.OUTLINE_COLOR -> SubtitleColorPanel(
            title = stringResource(R.string.sub_outline_colour),
            target = SubtitleColorTarget.OUTLINE,
            stored = style.outlineColor,
            onSetColor = onSetOutlineColor,
            onDismiss = onDismiss
        )
        SubtitlePicker.HDR_TEXT_COLOR -> SubtitleColorPanel(
            title = stringResource(R.string.sub_hdr_text_colour),
            target = SubtitleColorTarget.TEXT,
            stored = style.hdrTextColor,
            onSetColor = onSetHdrTextColor,
            onDismiss = onDismiss
        )
    }
}

/**
 * A colour panel: Opacity (Left / Right in [SUBTITLE_MIN_OPACITY]..100 steps of 10, saved at once;
 * hidden while the colour is None), then the swatches with their chips. OK on a swatch saves it at
 * the current opacity and closes; it opens on the stored colour's swatch.
 */
@Composable
private fun SubtitleColorPanel(
    title: String,
    target: SubtitleColorTarget,
    stored: Int,
    onSetColor: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Steps build on this copy, so quick presses never step from a store echo that has not arrived.
    var argb by remember { mutableIntStateOf(stored) }
    val swatches = target.swatches
    val selected = subtitleSwatchFor(argb, swatches)
    // None is alpha 0; the steps never go below SUBTITLE_MIN_OPACITY, so this holds while open.
    val showOpacity = subtitleOpacityPercent(argb) > 0
    val rowOffset = if (showOpacity) 1 else 0
    val initialIndex = remember { rowOffset + swatches.indexOf(selected).coerceAtLeast(0) }
    SettingsPanelList(title = title, initialIndex = initialIndex, onDismiss = onDismiss) { focusRequester ->
        if (showOpacity) {
            item(key = "opacity") {
                PlayerPanelRow(
                    title = stringResource(R.string.sub_opacity),
                    selected = false,
                    onClick = {},
                    titleEnd = "‹  ${subtitleOpacityPercent(argb)}%  ›",
                    modifier = Modifier.sideStepKeys(enabled = true) { delta ->
                        val current = subtitleOpacityPercent(argb)
                        val next = stepValue(SUBTITLE_MIN_OPACITY, 100, 10, current, delta)
                        if (next != current) {
                            argb = subtitleColorWithOpacity(argb, next)
                            onSetColor(argb)
                        }
                    }
                )
            }
        }
        itemsIndexed(swatches) { index, swatch ->
            PlayerPanelRow(
                title = stringResource(swatch.name),
                selected = swatch == selected,
                onClick = {
                    onSetColor(subtitlePickSwatch(swatch, argb))
                    onDismiss()
                },
                focusRequester = focusRequester.takeIf { index + rowOffset == initialIndex },
                trailingIconTint = NuvioTheme.marquee.readyText,
                leading = { SettingsColorChip(Color(swatch.argb)) }
            )
        }
    }
}
