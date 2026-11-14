package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** One option of a [SettingsPickerPanel] / [SettingsChecklistPanel] row. */
internal data class SettingsPickerOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val trailing: String? = null,
    val titleFontFamily: FontFamily? = null
)

/** The row a picker opens on: the selected option, else the first. */
internal fun <T> settingsPickerInitialIndex(options: List<SettingsPickerOption<T>>, selectedValue: T): Int =
    options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)

/**
 * The row a checklist opens on, counting the "all" row first when [hasAllRow]: the first ticked
 * option, else row 0 (the "all" row, which is the ticked one when nothing is).
 */
internal fun <T> settingsChecklistInitialIndex(
    options: List<SettingsPickerOption<T>>,
    checked: Set<T>,
    hasAllRow: Boolean
): Int {
    val first = options.indexOfFirst { it.value in checked }
    return when {
        first < 0 -> 0
        hasAllRow -> first + 1
        else -> first
    }
}

/**
 * A value picker (approved sandbox `u11-settings-marquee` stage 18): the compact side panel in its
 * own Dialog window, [title] as the eyebrow, an optional [caption], one row per option (description
 * wraps, the selected one ticked), then an optional [footnote]. It opens on the selected row; OK picks ([onSelect]) and closes
 * ([onDismiss]); Back closes.
 */
@Composable
internal fun <T> SettingsPickerPanel(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    caption: String? = null,
    footnote: String? = null
) {
    val initialIndex = remember { settingsPickerInitialIndex(options, selectedValue) }
    SettingsPanelList(title = title, initialIndex = initialIndex, onDismiss = onDismiss, caption = caption) { focusRequester ->
        itemsIndexed(options) { index, option ->
            SettingsPanelOptionRow(
                option = option,
                selected = option.value == selectedValue,
                onClick = {
                    onSelect(option.value)
                    onDismiss()
                },
                focusRequester = focusRequester.takeIf { index == initialIndex }
            )
        }
        if (footnote != null) {
            item(key = "footnote") {
                SettingsPanelCaption(
                    text = footnote,
                    modifier = Modifier.padding(top = NuvioTheme.marquee.settings.panelTextInset)
                )
            }
        }
    }
}

/**
 * A multi-choice panel (Playback's Allowed addons): like [SettingsPickerPanel], but OK ticks or
 * unticks a row and saves at once through [onCheckedChange]; Back closes. [allLabel] adds a first
 * row meaning "no restriction" (ticked when nothing is; OK clears every tick). [emptyText] shows
 * when there are no options.
 */
@Composable
internal fun <T> SettingsChecklistPanel(
    title: String,
    options: List<SettingsPickerOption<T>>,
    checked: Set<T>,
    onCheckedChange: (Set<T>) -> Unit,
    onDismiss: () -> Unit,
    allLabel: String? = null,
    emptyText: String? = null
) {
    // Ticks flip here at once; a new [checked] from the store (the echo of a save, or a sync
    // pull) replaces them.
    var current by remember(checked) { mutableStateOf(checked) }
    fun save(next: Set<T>) {
        current = next
        onCheckedChange(next)
    }
    val rowOffset = if (allLabel != null) 1 else 0
    val initialIndex = remember { settingsChecklistInitialIndex(options, checked, hasAllRow = allLabel != null) }
    SettingsPanelList(title = title, initialIndex = initialIndex, onDismiss = onDismiss) { focusRequester ->
        if (allLabel != null) {
            item(key = "all") {
                PlayerPanelRow(
                    title = allLabel,
                    selected = current.isEmpty(),
                    onClick = { save(emptySet()) },
                    focusRequester = focusRequester.takeIf { initialIndex == 0 },
                    trailingIconTint = NuvioTheme.marquee.readyText
                )
            }
        }
        if (options.isEmpty() && emptyText != null) {
            item(key = "empty") { SettingsPanelCaption(emptyText) }
        }
        itemsIndexed(options) { index, option ->
            val isChecked = option.value in current
            SettingsPanelOptionRow(
                option = option,
                selected = isChecked,
                onClick = { save(if (isChecked) current - option.value else current + option.value) },
                focusRequester = focusRequester.takeIf { index + rowOffset == initialIndex }
            )
        }
    }
}

/**
 * The picker / checklist shell: the side panel in a Dialog window with the [title] eyebrow, an
 * optional [caption], then a list scrolled to [initialIndex]; [rows] puts the requester it is
 * handed on that row, which takes focus once the window is up.
 */
@Composable
internal fun SettingsPanelList(
    title: String,
    initialIndex: Int,
    onDismiss: () -> Unit,
    caption: String? = null,
    rows: LazyListScope.(focusRequester: FocusRequester) -> Unit
) {
    val tokens = NuvioTheme.marquee.settings
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = tokens.pickerWidth,
        insetVertical = tokens.pickerInsetVertical,
        initialFocus = focusRequester
    ) {
        PanelEyebrow(text = title)
        if (!caption.isNullOrBlank()) SettingsPanelCaption(caption)
        LazyColumn(state = listState) { rows(focusRequester) }
    }
}

/** One option row (`.l8pk`): title in its own font, trailing code, wrapping description, green tick. */
@Composable
internal fun <T> SettingsPanelOptionRow(
    option: SettingsPickerOption<T>,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    PlayerPanelRow(
        title = option.title,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        subtitle = option.description,
        titleEnd = option.trailing,
        focusRequester = focusRequester,
        trailingIconTint = NuvioTheme.marquee.readyText,
        titleFontFamily = option.titleFontFamily,
        subtitleMaxLines = Int.MAX_VALUE
    )
}

/**
 * An option row carrying a stepper (Spec 51 P7, sandbox `spec-51-network-stream-limit` stage
 * set-picker, `.s51stp`): the option as [SettingsPanelOptionRow] draws it, then a line under it,
 * [stepLabel] and ‹ [valueText] ›. One focus stop: Left / Right step [value] over [min]..[max] in
 * [step]s with focus kept, as [SettingsStepperRow] (consumed even at the ends, [sideStepKeys];
 * [onValueChange] only on a change); OK picks the option ([onClick]). The line rests dimmed while
 * the option is not [selected] and lights while the row has focus.
 */
@Composable
internal fun <T> SettingsPanelStepperOptionRow(
    option: SettingsPickerOption<T>,
    selected: Boolean,
    stepLabel: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    var lit by remember { mutableStateOf(false) }
    Column {
        SettingsPanelOptionRow(
            option = option,
            selected = selected,
            onClick = onClick,
            focusRequester = focusRequester,
            // Ahead of the row's own focus target, which both observe (CMP-FOCUS-02).
            modifier = Modifier
                .sideStepKeys(enabled = true) { delta ->
                    val next = stepValue(min, max, step, value, delta)
                    if (next != value) onValueChange(next)
                }
                .onFocusChanged { lit = it.hasFocus }
        )
        val chevronTint = if (lit) NuvioPrimitives.white else marquee.onImage3
        val textStyle = MaterialTheme.typography.bodyMedium
        Row(
            modifier = Modifier
                .padding(start = tokens.optionStepperInset, top = tokens.optionStepperTop, end = tokens.optionStepperInset)
                .fillMaxWidth()
                .alpha(if (selected || lit) 1f else tokens.optionStepperDimAlpha)
                .clip(RoundedCornerShape(tokens.optionStepperRadius))
                .background(tokens.optionStepperFill)
                .padding(
                    horizontal = tokens.optionStepperPaddingHorizontal,
                    vertical = tokens.optionStepperPaddingVertical
                ),
            horizontalArrangement = Arrangement.spacedBy(tokens.optionStepperGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stepLabel,
                style = textStyle.copy(fontWeight = FontWeight.Medium),
                color = NuvioPrimitives.white,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.cd_decrease),
                tint = chevronTint,
                modifier = Modifier.size(tokens.optionStepperIconSize)
            )
            Text(
                text = valueText,
                style = textStyle.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = tokens.optionStepperValueMinWidth)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_increase),
                tint = chevronTint,
                modifier = Modifier.size(tokens.optionStepperIconSize)
            )
        }
    }
}

/** `.s52msg.err` (Spec 52 panels: the TMDB key's check, the update's download): the error icon, then [text] in red. */
@Composable
internal fun SettingsPanelErrorLine(text: String) {
    val tokens = NuvioTheme.marquee.settings
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.messageIconGap),
        modifier = Modifier.padding(horizontal = tokens.panelTextInset)
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = NuvioPrimitives.errorOnImage,
            modifier = Modifier.size(tokens.messageIconSize)
        )
        Text(
            text = text,
            style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium),
            color = NuvioPrimitives.errorOnImage
        )
    }
}

/** A panel's caption, empty-state line or foot note (`.l8p .note`), inset like its eyebrow. */
@Composable
internal fun SettingsPanelCaption(text: String, modifier: Modifier = Modifier) {
    val inset = NuvioTheme.marquee.settings.panelTextInset
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = modifier.padding(start = inset, end = inset, bottom = inset)
    )
}
