package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeAnchoredPanel
import com.nuvio.tv.ui.components.marquee.MarqueePickerOption
import com.nuvio.tv.ui.components.marquee.MarqueePickerOptionList
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import java.text.NumberFormat

// Live TV pickers (Spec 38 Q1; sandbox u38-filters-search v4 stages 1-4, `.l38p`, `.pk`).

/** The option value of a picker's All row (picker keys are never empty). */
internal const val PICKER_ALL = ""

/** The pill's last layout position; read when the panel opens, never observed. */
private class PillAnchor {
    var coordinates: LayoutCoordinates? = null
}

/**
 * One picker in the pill row: a [LiveTvPickerPill] whose OK calls [onOpen] (the pickers become the
 * shown filter, stage 1) and opens Discover's compact panel under it ([eyebrow], [options], built
 * only while it is open; a tick on [selectedValue]). OK on an option closes it and calls
 * [onSelect]; Back closes unchanged. Focus returns to the pill. Callers put `focusRequester` in
 * [modifier]. [showLabel] false draws the value only ([label] still names it to accessibility; the
 * View picker, Spec 67 D7); [alignEnd] lines the panel up with the pill's end.
 */
@Composable
internal fun LiveTvPicker(
    label: String,
    value: String,
    eyebrow: String,
    active: Boolean,
    all: Boolean,
    options: @Composable () -> List<MarqueePickerOption>,
    selectedValue: String,
    onOpen: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    alignEnd: Boolean = false
) {
    val pillRequester = remember { FocusRequester() }
    var openAt by remember { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    val close = {
        openAt = null
        scope.launch { pillRequester.requestFocusAfterFrames() }
        Unit
    }
    LiveTvPickerPill(
        label = label,
        value = value,
        active = active,
        all = all,
        showLabel = showLabel,
        onOpen = { bounds ->
            onOpen()
            openAt = bounds
        },
        modifier = modifier.focusRequester(pillRequester)
    )
    openAt?.let { bounds ->
        val initial = remember { FocusRequester() }
        MarqueeAnchoredPanel(anchor = bounds, onDismissRequest = close, alignEnd = alignEnd, initialFocus = initial) {
            MarqueePickerOptionList(
                title = eyebrow,
                options = options(),
                selectedValue = selectedValue,
                focusRequester = initial,
                onSelect = { picked ->
                    close()
                    onSelect(picked)
                }
            )
        }
    }
}

/**
 * `.l38p`: [label] in small type (unless not [showLabel]), the [value], a chevron; [pillHeight] tall like the filter pills.
 * Glass when the pickers are the shown filter ([active]) and it holds a value; at All ([all]) a
 * hairline with dim text; a value while Favorites or Recents show = a hairline with white text.
 * Focused = white, black text.
 */
@Composable
private fun LiveTvPickerPill(
    label: String,
    value: String,
    active: Boolean,
    all: Boolean,
    showLabel: Boolean,
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val anchor = remember { PillAnchor() }
    var focused by remember { mutableStateOf(false) }
    val glass = active && !all
    Surface(
        onClick = { anchor.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let(onOpen) },
        modifier = modifier
            .height(live.pillHeight)
            .onFocusChanged { focused = it.isFocused }
            .onGloballyPositioned { anchor.coordinates = it },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (glass) marquee.glassFill else Color.Transparent,
            contentColor = if (all) marquee.onImage2 else white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (glass) Border.None else Border(BorderStroke(1.dp, live.pillOutline), shape = CircleShape),
            focusedBorder = Border.None
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        val secondary = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = live.pillPaddingHorizontal, end = live.pickerPaddingEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showLabel) {
                Text(text = label, style = NuvioTheme.textStyles.caption, color = secondary, maxLines = 1)
                Spacer(modifier = Modifier.width(live.pickerInnerGap))
            }
            // Category values can be playlist groups of any length.
            Text(
                text = value,
                style = NuvioTheme.textStyles.cardLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = live.pickerValueMaxWidth)
            )
            Spacer(modifier = Modifier.width(live.pickerInnerGap))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_expand, label),
                tint = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage2,
                modifier = Modifier.size(live.pillIconSize)
            )
        }
    }
}

/**
 * A picker's options (stages 2-4): with pinned values, "YOUR CHANNELS" over them and "ALL" over the
 * rest; else no headings. [allLabel] names the All row ("All countries"), [label] a key's value.
 */
@Composable
internal fun liveTvPickerOptions(
    picker: Picker?,
    allLabel: String,
    numbers: NumberFormat,
    label: @Composable (String) -> String
): List<MarqueePickerOption> {
    if (picker == null) return emptyList()
    val sections = picker.pinned.isNotEmpty()
    val pinnedSection = if (sections) stringResource(R.string.live_tv_picker_yours) else null
    val allSection = if (sections) stringResource(R.string.live_tv_picker_all_section) else null
    val out = ArrayList<MarqueePickerOption>(picker.pinned.size + picker.values.size)
    for (v in picker.pinned) {
        out += MarqueePickerOption(label(v.key!!), v.key, count = numbers.format(v.count), section = pinnedSection)
    }
    for (v in picker.values) {
        val key = v.key
        out += MarqueePickerOption(
            label = if (key == null) allLabel else label(key),
            value = key ?: PICKER_ALL,
            count = numbers.format(v.count),
            section = allSection
        )
    }
    return out
}
