package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlayerEvent
import com.nuvio.tv.core.player.PlayerSubtitleUtils
import com.nuvio.tv.core.player.TrackInfo
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.mapper.dropFileExtension
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.PanelAdjustRow
import com.nuvio.tv.ui.components.PanelColorDots
import com.nuvio.tv.ui.components.PanelSegments
import com.nuvio.tv.ui.components.PanelStepperRow
import com.nuvio.tv.ui.components.PanelToggle
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

private const val SubtitleOffLanguageKey = "__off__"
private const val SubtitleUnknownLanguageKey = "__unknown__"

/** Font size, opacity and bottom offset steps (the store clamps size 50..200, offset -20..50). */
private const val SubtitleSizeStepPercent = 10
private const val SubtitleOpacityStepPercent = 10
private const val SubtitleOffsetStep = 5

private val OverlayTextColors = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88)
)

private val OverlayOutlineColors = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C)
)

/**
 * Subtitles panel (approved sandbox `u6-player-panels-marquee` stages 6-7): the compact panel
 * bottom-end over clear video (Spec 25 Q1), eyebrow, the "Subtitle Style" / "Back to tracks"
 * action row, then the track rows (None first, the source at the row end) or the style view.
 */
@Composable
internal fun SubtitleSelectionOverlay(
    visible: Boolean,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    subtitleStyle: SubtitleStyleSettings,
    /** Spec 62 H2c: HDR output with the HDR style on; [subtitleStyle] then carries the HDR text colour. */
    isEditingHdrStyle: Boolean,
    subtitleDelayMs: Int,
    installedSubtitleAddonOrder: List<String>,
    isLoadingAddons: Boolean,
    useLibass: Boolean,
    isUsingMpv: Boolean,
    playingFileName: String?,
    accent: () -> Color,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val noneLabel = stringResource(R.string.subtitle_none)
    val unknownLabel = stringResource(R.string.subtitle_language_unknown)
    val builtInLabel = stringResource(R.string.subtitle_built_in)
    val forcedLabel = stringResource(R.string.sub_forced_lang)
    val defaultLabel = stringResource(R.string.subtitle_track_default)
    val sdhLabel = stringResource(R.string.subtitle_track_sdh)
    val matchesFileLabel = stringResource(R.string.subtitle_matches_file)

    // Session snapshots: the open panel works from the state at open time, like the
    // three-rail predecessor, so mid-session addon arrivals don't reshuffle focus.
    val sessionInternalTracks = remember(visible) { internalTracks.map(TrackInfo::copy) }
    // Live keys (0.8.3): addon subtitles now arrive progressively (sidecar
    // pipeline), so the snapshot re-takes when the list grows; the selection id
    // and open-time scroll position stay frozen so focus does not reshuffle.
    val sessionAddonSubtitles = remember(visible, addonSubtitles) { addonSubtitles.map(Subtitle::copy) }
    val sessionInstalledOrder = remember(visible) { installedSubtitleAddonOrder.toList() }
    val sessionSelectedOptionId = remember(visible) {
        selectedSubtitleOptionId(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = selectedInternalIndex,
            selectedAddonSubtitle = selectedAddonSubtitle
        )
    }
    // Flat track list: every language's options concatenated in the language order the
    // rail builder already encodes (preferred languages first, showOnlyPreferredLanguages
    // honoured, alphabetical within). The drill-down rails are gone (finding #3); the
    // language ordering machinery is reused as the flat sort. Recomputes on progressive
    // addon arrival (0.8.3 sidecar pipeline) via the sessionAddonSubtitles key.
    val flatOptions = remember(visible, sessionAddonSubtitles) {
        val languageItems = buildSubtitleLanguageRailItems(
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            preferredLanguage = subtitleStyle.preferredLanguage,
            secondaryPreferredLanguage = subtitleStyle.secondaryPreferredLanguage,
            showOnlyPreferredLanguages = subtitleStyle.showOnlyPreferredLanguages,
            currentLanguageKey = selectedSubtitleLanguageKey(
                internalTracks = sessionInternalTracks,
                selectedInternalIndex = selectedInternalIndex,
                selectedAddonSubtitle = selectedAddonSubtitle
            ),
            unknownLabel = unknownLabel
        )
        languageItems
            .flatMap { language ->
                buildSubtitleOptionRailItems(
                    selectedLanguageKey = language.key,
                    internalTracks = sessionInternalTracks,
                    addonSubtitles = sessionAddonSubtitles,
                    installedAddonOrder = sessionInstalledOrder,
                    builtInLabel = builtInLabel,
                    forcedLabel = forcedLabel,
                    defaultLabel = defaultLabel,
                    sdhLabel = sdhLabel,
                    playingFileName = playingFileName
                ).map { item ->
                    // Flat list needs the language on every row (the drill-down implied
                    // it); keep the track's own name only when it adds information.
                    val variant = item.title.takeIf { title ->
                        title.isNotBlank() &&
                            !title.equals(language.label, ignoreCase = true) &&
                            !title.equals(language.key, ignoreCase = true)
                    }
                    item.copy(
                        title = if (variant != null) "${language.label} $variant" else language.label
                    )
                }
            }
    }

    var editorOpen by remember(visible) { mutableStateOf(false) }
    var currentSelectedOptionId by remember(visible) { mutableStateOf(sessionSelectedOptionId) }
    val isSelectedSubtitleAss = remember(
        flatOptions,
        currentSelectedOptionId,
        sessionInternalTracks,
        selectedInternalIndex,
        selectedAddonSubtitle
    ) {
        val selectedOption = flatOptions.firstOrNull { it.id == currentSelectedOptionId }
        when (selectedOption?.kind) {
            SubtitleOptionKind.INTERNAL -> {
                val track = selectedOption.internalTrackIndex?.let { sessionInternalTracks.getOrNull(it) }
                PlayerSubtitleUtils.isAssOrSsaTrack(track?.codec, track?.name)
            }
            SubtitleOptionKind.ADDON -> PlayerSubtitleUtils.isAssOrSsaUrl(selectedOption.addonSubtitle?.url)
            // Subtitles off in-panel: fall back to live state.
            null -> PlayerSubtitleUtils.isSelectedSubtitleAssOrSsa(
                selectedAddonSubtitle,
                sessionInternalTracks.getOrNull(selectedInternalIndex)
            )
        }
    }
    // Upstream 0.9.0: style controls are disabled while an ASS/SSA track is selected
    // under libass or MPV (they render the embedded styling themselves).
    val isStyleDisabledByLibass = isSelectedSubtitleAss && (useLibass || isUsingMpv)
    // Without libass, Media3 draws the file's own sizes and colours (exoSubtitleLook).
    val isOwnSsaStyle = isSelectedSubtitleAss && !useLibass && !isUsingMpv
    val listFocusRequester = remember { FocusRequester() }
    val delayFocusRequester = remember { FocusRequester() }
    val listState = remember(visible) {
        val selectedIndex = flatOptions.indexOfFirst { it.id == sessionSelectedOptionId }
        LazyListState(firstVisibleItemIndex = (selectedIndex + 1).coerceAtLeast(0))
    }

    LaunchedEffect(visible, editorOpen) {
        if (!visible) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { (if (editorOpen) delayFocusRequester else listFocusRequester).requestFocus() }
    }

    PlayerCompactPanel(
        visible = visible,
        title = stringResource(R.string.subtitle_dialog_title),
        modifier = modifier
    ) {
        PanelActionRow(
            label = if (editorOpen) {
                stringResource(R.string.panel_back_to_tracks)
            } else {
                stringResource(R.string.subtitle_style_title)
            },
            icon = if (editorOpen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.TextFields,
            onClick = { editorOpen = !editorOpen }
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        if (editorOpen) {
            SubtitleStyleContent(
                subtitleStyle = subtitleStyle,
                isEditingHdrStyle = isEditingHdrStyle,
                subtitleDelayMs = subtitleDelayMs,
                delayFocusRequester = delayFocusRequester,
                onEvent = onEvent,
                isStyleDisabledByLibass = isStyleDisabledByLibass,
                isOwnSsaStyle = isOwnSsaStyle
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .fillMaxWidth()
            ) {
                item(key = "subtitle_off") {
                    PlayerPanelRow(
                        title = noneLabel,
                        selected = currentSelectedOptionId == null,
                        onClick = {
                            currentSelectedOptionId = null
                            onDisableSubtitles()
                        },
                        focusRequester = if (currentSelectedOptionId == null) listFocusRequester else null
                    )
                }
                if (flatOptions.isEmpty() && isLoadingAddons) {
                    item(key = "subtitle_loading") {
                        Text(
                            text = stringResource(R.string.subtitle_loading_addon),
                            style = NuvioTheme.textStyles.meta,
                            color = NuvioTheme.marquee.onImage3,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
                items(items = flatOptions, key = { it.id }) { item ->
                    PlayerPanelRow(
                        title = item.title,
                        titleEnd = item.sourceLabel,
                        subtitle = item.meta,
                        titleTag = item.tag,
                        subtitleMark = matchesFileLabel.takeIf { item.matchesFile },
                        markColor = accent,
                        markLeading = true,
                        selected = item.id == currentSelectedOptionId,
                        onClick = {
                            when (item.kind) {
                                SubtitleOptionKind.INTERNAL -> item.internalTrackIndex?.let { trackIndex ->
                                    currentSelectedOptionId = item.id
                                    onInternalTrackSelected(trackIndex)
                                }
                                SubtitleOptionKind.ADDON -> item.addonSubtitle?.let { subtitle ->
                                    currentSelectedOptionId = item.id
                                    onAddonSubtitleSelected(subtitle)
                                }
                            }
                        },
                        focusRequester = if (item.id == currentSelectedOptionId) listFocusRequester else null
                    )
                }
            }
        }
    }
}

/**
 * Style view (stage 7), one control per line: Delay (opens the delay strip), Font Size, Bold,
 * Text Color, Text Opacity, Text edge (outline colours on its line, the edge segments under it),
 * Bottom Offset, Reset Defaults. A control that does not apply dims and leaves focus (Reset dims to
 * PanelActionRow's own .45). [isEditingHdrStyle] (Spec 62 H2c, sandbox spec-62-h2-hdr-subtitles
 * stage 5): Text Color and Text Opacity read "(HDR)" and set the HDR colour, and Brightness (HDR)
 * follows Text Opacity; the rest stays shared.
 */
@Composable
private fun SubtitleStyleContent(
    subtitleStyle: SubtitleStyleSettings,
    isEditingHdrStyle: Boolean,
    subtitleDelayMs: Int,
    delayFocusRequester: FocusRequester,
    onEvent: (PlayerEvent) -> Unit,
    isStyleDisabledByLibass: Boolean,
    isOwnSsaStyle: Boolean
) {
    // Upstream 0.9.0 (68e943f77/e96a94748): libass and MPV render ASS/SSA styling themselves,
    // so every style control is inert for those tracks.
    // Fork divergence: Delay stays active: it is timing, not styling, and works under
    // libass/MPV (SubtitleOffsetRenderer / mpv sub-delay); upstream gates it, which orphans the
    // delay strip's only entry point for ASS tracks.
    val styleEnabled = !isStyleDisabledByLibass
    // SSA without libass: the file's sizes and colours win, so those controls go inert too.
    val sizeColorEnabled = styleEnabled && !isOwnSsaStyle
    val textOpacity = textOpacityPercent(subtitleStyle.textColor)
    val edgeStyles = SubtitleEdgeStyle.entries
    val sizeMinus = remember { FocusRequester() }
    val sizePlus = remember { FocusRequester() }
    val opacityMinus = remember { FocusRequester() }
    val opacityPlus = remember { FocusRequester() }
    val brightnessMinus = remember { FocusRequester() }
    val brightnessPlus = remember { FocusRequester() }
    val offsetMinus = remember { FocusRequester() }
    val offsetPlus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        SubtitleDelayRow(
            value = formatSubtitleDelay(subtitleDelayMs),
            focusRequester = delayFocusRequester,
            onClick = { onEvent(PlayerEvent.OnShowSubtitleDelayOverlay) }
        )
        if (isOwnSsaStyle) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(NuvioTheme.marquee.playerPanel.segmentFill, RoundedCornerShape(NuvioTheme.radii.md))
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NuvioTheme.marquee.onImage2,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.subtitle_ssa_own_style),
                    style = NuvioTheme.textStyles.panelHelper,
                    color = NuvioTheme.marquee.onImage2
                )
            }
        }
        PanelStepperRow(
            label = stringResource(R.string.subtitle_style_font_size),
            value = "${subtitleStyle.size}%",
            enabled = sizeColorEnabled,
            canDecrease = true,
            canIncrease = true,
            minusFocusRequester = sizeMinus,
            plusFocusRequester = sizePlus,
            onDecrease = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - SubtitleSizeStepPercent)) },
            onIncrease = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + SubtitleSizeStepPercent)) }
        )
        PanelAdjustRow(label = stringResource(R.string.subtitle_style_bold)) {
            PanelToggle(
                text = stringResource(
                    if (subtitleStyle.bold) R.string.subtitle_style_on else R.string.subtitle_style_off
                ),
                enabled = styleEnabled,
                onClick = { onEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
            )
        }
        PanelAdjustRow(
            label = stringResource(
                if (isEditingHdrStyle) R.string.subtitle_style_text_color_hdr else R.string.subtitle_style_text_color
            )
        ) {
            PanelColorDots(
                colors = OverlayTextColors,
                selectedArgb = subtitleStyle.textColor,
                enabled = sizeColorEnabled,
                onSelect = { onEvent(PlayerEvent.OnSetSubtitleTextColor(it, hdr = isEditingHdrStyle)) }
            )
        }
        PanelStepperRow(
            label = stringResource(
                if (isEditingHdrStyle) R.string.subtitle_style_text_opacity_hdr else R.string.subtitle_style_text_opacity
            ),
            value = "$textOpacity%",
            enabled = sizeColorEnabled,
            canDecrease = true,
            canIncrease = true,
            minusFocusRequester = opacityMinus,
            plusFocusRequester = opacityPlus,
            onDecrease = {
                onEvent(
                    PlayerEvent.OnSetSubtitleTextColor(
                        stepTextOpacity(subtitleStyle.textColor, -SubtitleOpacityStepPercent),
                        hdr = isEditingHdrStyle
                    )
                )
            },
            onIncrease = {
                onEvent(
                    PlayerEvent.OnSetSubtitleTextColor(
                        stepTextOpacity(subtitleStyle.textColor, SubtitleOpacityStepPercent),
                        hdr = isEditingHdrStyle
                    )
                )
            }
        )
        if (isEditingHdrStyle) {
            val brightness = subtitleStyle.hdrBrightnessPercent
            PanelStepperRow(
                label = stringResource(R.string.subtitle_style_brightness_hdr),
                value = "$brightness%",
                enabled = sizeColorEnabled,
                canDecrease = brightness > SubtitleStyleSettings.HDR_BRIGHTNESS_MIN,
                canIncrease = brightness < SubtitleStyleSettings.HDR_BRIGHTNESS_MAX,
                minusFocusRequester = brightnessMinus,
                plusFocusRequester = brightnessPlus,
                // A stepper at its limit leaves focus (PanelStepperRow): hand it to the other one first.
                // No canIncrease/canDecrease guard (unlike AudioSelectionOverlay): the store snaps the
                // value to 10 % steps in MIN..MAX, so the opposite stepper is always enabled here.
                onDecrease = {
                    val next = brightness - SubtitleStyleSettings.HDR_BRIGHTNESS_STEP
                    if (next <= SubtitleStyleSettings.HDR_BRIGHTNESS_MIN) runCatching { brightnessPlus.requestFocus() }
                    onEvent(PlayerEvent.OnSetSubtitleHdrBrightness(next))
                },
                onIncrease = {
                    val next = brightness + SubtitleStyleSettings.HDR_BRIGHTNESS_STEP
                    if (next >= SubtitleStyleSettings.HDR_BRIGHTNESS_MAX) runCatching { brightnessMinus.requestFocus() }
                    onEvent(PlayerEvent.OnSetSubtitleHdrBrightness(next))
                }
            )
        }
        PanelAdjustRow(
            label = stringResource(R.string.subtitle_edge_style),
            below = {
                PanelSegments(
                    options = edgeStyles.map { subtitleEdgeLabel(it) },
                    selectedIndex = edgeStyles.indexOf(subtitleStyle.edgeStyle),
                    enabled = styleEnabled,
                    onSelect = { onEvent(PlayerEvent.OnSetSubtitleEdgeStyle(edgeStyles[it])) }
                )
            }
        ) {
            // Outline colour applies to Outline only; dimmed and inert otherwise.
            PanelColorDots(
                colors = OverlayOutlineColors,
                selectedArgb = subtitleStyle.outlineColor,
                small = true,
                enabled = styleEnabled && subtitleStyle.edgeStyle == SubtitleEdgeStyle.OUTLINE,
                onSelect = { onEvent(PlayerEvent.OnSetSubtitleOutlineColor(it)) }
            )
        }
        PanelStepperRow(
            label = stringResource(R.string.subtitle_style_bottom_offset),
            value = subtitleStyle.verticalOffset.toString(),
            enabled = styleEnabled,
            canDecrease = true,
            canIncrease = true,
            minusFocusRequester = offsetMinus,
            plusFocusRequester = offsetPlus,
            onDecrease = {
                onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - SubtitleOffsetStep))
            },
            onIncrease = {
                onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + SubtitleOffsetStep))
            }
        )
        PanelActionRow(
            label = stringResource(R.string.subtitle_reset_defaults),
            enabled = styleEnabled,
            onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults(hdr = isEditingHdrStyle)) },
            modifier = Modifier
                .padding(top = NuvioTheme.marquee.playerPanel.actionTop)
                .focusProperties { canFocus = styleEnabled }
        )
    }
}

/** Stage 7's Delay line: label, value and a chevron; the whole row opens the delay strip. */
@Composable
private fun SubtitleDelayRow(
    value: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val content = if (isFocused) NuvioPrimitives.black else NuvioPrimitives.white
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) NuvioPrimitives.white else Color.Transparent)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(
                start = tokens.adjustPaddingHorizontal,
                top = tokens.adjustPaddingTop,
                end = tokens.adjustPaddingHorizontal,
                bottom = tokens.adjustPaddingBottom
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.adjustGap)
    ) {
        Text(
            text = stringResource(R.string.subtitle_tab_delay),
            style = NuvioTheme.textStyles.meta,
            color = if (isFocused) content else NuvioTheme.marquee.onImage2,
            modifier = Modifier.weight(1f)
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(text = value, style = NuvioTheme.textStyles.osdTime, color = content, maxLines = 1)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** The opacity percent (0..100) of a stored ARGB subtitle colour. */
internal fun textOpacityPercent(argb: Int): Int = ((argb ushr 24) * 100f / 255f).roundToInt()

/** [argb] with its opacity moved [deltaPercent] points (clamped to 0..100); RGB unchanged. */
internal fun stepTextOpacity(argb: Int, deltaPercent: Int): Int {
    val percent = (textOpacityPercent(argb) + deltaPercent).coerceIn(0, 100)
    val alpha = (percent * 255f / 100f).roundToInt()
    return (argb and 0x00FFFFFF) or (alpha shl 24)
}

private data class SubtitleLanguageRailItem(
    val key: String,
    val label: String
)

private enum class SubtitleOptionKind {
    INTERNAL,
    ADDON
}

private data class SubtitleOptionRailItem(
    val id: String,
    val kind: SubtitleOptionKind,
    val title: String,
    val sourceLabel: String,
    val meta: String?,
    val internalTrackIndex: Int? = null,
    val addonSubtitle: Subtitle? = null,
    val tag: String? = null,
    val matchesFile: Boolean = false
)

private fun buildSubtitleLanguageRailItems(
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    showOnlyPreferredLanguages: Boolean,
    currentLanguageKey: String,
    unknownLabel: String
): List<SubtitleLanguageRailItem> {
    val counts = linkedMapOf<String, Int>()
    internalTracks.forEach { track ->
        val key = normalizeOverlayLanguageKeyForTrack(track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = normalizeOverlayLanguageKey(subtitle.lang)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = preferredOverlayLanguageOrder(
        preferredLanguage = preferredLanguage,
        secondaryPreferredLanguage = secondaryPreferredLanguage
    )

    val languageEntries = if (showOnlyPreferredLanguages) {
        val preferredKeys = preferredOrder.toSet()
        counts.entries.filter { entry ->
            entry.key in preferredKeys || entry.key == currentLanguageKey
        }
    } else {
        counts.entries
    }

    val sortedItems = languageEntries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                { entry ->
                    val preferredIndex = preferredOrder.indexOf(entry.key)
                    if (preferredIndex >= 0) preferredIndex else Int.MAX_VALUE
                },
                { entry -> subtitleLanguageSortLabel(entry.key) }
            )
        )
        .map { (key, _) ->
            SubtitleLanguageRailItem(key = key, label = subtitleLanguageLabel(key, unknownLabel))
        }

    return sortedItems
}

private fun preferredOverlayLanguageOrder(
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<String> {
    fun toOverlayLanguageKey(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
        if (normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED) return null
        return normalizeOverlayLanguageKey(language)
            .takeUnless { it == SubtitleUnknownLanguageKey }
    }

    return listOfNotNull(
        toOverlayLanguageKey(preferredLanguage),
        toOverlayLanguageKey(secondaryPreferredLanguage)
    ).distinct()
}

private fun buildSubtitleOptionRailItems(
    selectedLanguageKey: String,
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    installedAddonOrder: List<String>,
    builtInLabel: String,
    forcedLabel: String,
    defaultLabel: String,
    sdhLabel: String,
    playingFileName: String?
): List<SubtitleOptionRailItem> {
    val addonOrderMap = installedAddonOrder.withIndex().associate { (index, name) -> name to index }
    val internalItems = internalTracks
        .filter { normalizeOverlayLanguageKeyForTrack(it) == selectedLanguageKey }
        .map { track ->
            SubtitleOptionRailItem(
                id = "internal:${track.index}",
                kind = SubtitleOptionKind.INTERNAL,
                title = track.name,
                sourceLabel = builtInLabel,
                meta = listOfNotNull(
                    track.codec,
                    forcedLabel.takeIf { track.isForced },
                    defaultLabel.takeIf { track.isDefault }
                ).joinToString(" · ").ifBlank { null },
                internalTrackIndex = track.index,
                tag = sdhLabel.takeIf { track.isSdh }
            )
        }

    val addonItems = addonSubtitles
        .withIndex()
        .filter { (_, subtitle) -> normalizeOverlayLanguageKey(subtitle.lang) == selectedLanguageKey }
        .sortedWith(
            compareBy(
                { (index, subtitle) -> addonOrderMap[subtitle.addonName] ?: Int.MAX_VALUE },
                { (index, _) -> index }
            )
        )
        .distinctBy { (_, subtitle) -> addonSubtitleOptionId(subtitle) }
        .map { (_, subtitle) ->
            val optionId = addonSubtitleOptionId(subtitle)
            SubtitleOptionRailItem(
                id = optionId,
                kind = SubtitleOptionKind.ADDON,
                title = Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)),
                sourceLabel = subtitle.addonName,
                meta = subtitle.releaseName,
                addonSubtitle = subtitle,
                matchesFile = subtitleMatchesFile(subtitle.releaseName, subtitle.releaseGroup, playingFileName)
            )
        }

    return internalItems + addonItems
}

private fun selectedSubtitleLanguageKey(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String {
    val selectedAddonKey = selectedAddonSubtitle?.let { normalizeOverlayLanguageKey(it.lang) }
    if (selectedAddonKey != null) return selectedAddonKey

    val selectedInternalKey = internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
        ?: internalTracks.firstOrNull { it.isSelected }
            ?.let { normalizeOverlayLanguageKeyForTrack(it) }
    if (selectedInternalKey != null) return selectedInternalKey

    return SubtitleOffLanguageKey
}

private fun selectedSubtitleOptionId(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String? {
    selectedAddonSubtitle?.let { subtitle ->
        return addonSubtitleOptionId(subtitle)
    }

    internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { track ->
            return "internal:${track.index}"
        }

    internalTracks
        .firstOrNull { it.isSelected }
        ?.let { track ->
            return "internal:${track.index}"
        }

    return null
}

private fun addonSubtitleOptionId(subtitle: Subtitle): String {
    return "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.url}"
}

private fun normalizeOverlayLanguageKey(language: String?): String {
    if (language.isNullOrBlank()) return SubtitleUnknownLanguageKey
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

/**
 * Variant-aware language key for embedded tracks. Inspects name/label/trackId
 * to detect regional accents (e.g. Brazilian Portuguese, Latin American Spanish)
 * even when the language field is generic ("por", "spa").
 */
private fun normalizeOverlayLanguageKeyForTrack(track: TrackInfo): String {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    return when (variant) {
        "pt-br", "es-419" -> variant
        else -> variant
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun subtitleLanguageLabel(key: String, unknownLabel: String): String {
    return when (key) {
        SubtitleUnknownLanguageKey -> unknownLabel
        else -> Subtitle.languageCodeToName(key)
    }
}

private fun subtitleLanguageSortLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> "\uFFFF"
    else -> Subtitle.languageCodeToName(key).lowercase()
}

/**
 * "Matches your file" (st12): the subtitle's release name is the playing file's name word for word
 * (its extension aside), or the file's name holds the subtitle's release group as whole words
 * ("D-Z0N3" is two). A partial release name is no evidence: "Tears of Steel" alone fits every
 * release of the film.
 */
internal fun subtitleMatchesFile(releaseName: String?, releaseGroup: String?, playingFileName: String?): Boolean {
    val playing = releaseWords(playingFileName?.let(::dropFileExtension))
    if (playing.isEmpty()) return false
    val group = releaseWords(releaseGroup)
    val groupUsable = group.sumOf { it.length } >= 2 && group.any { word -> word.any { it.isLetter() } }
    if (groupUsable && playing.windowed(group.size).any { it == group }) return true
    val release = releaseWords(releaseName)
    return release.isNotEmpty() && release == playing
}

private fun releaseWords(text: String?): List<String> =
    text?.lowercase()?.split(RELEASE_WORD_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

private val RELEASE_WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
