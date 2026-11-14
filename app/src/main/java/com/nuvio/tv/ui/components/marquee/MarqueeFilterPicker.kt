package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One choice in a [MarqueeFilterPicker]; [detail] follows the label in small type (a catalog's
 * addon); [count] sits at the row's end (Live TV's channel counts, Spec 38 stages 2-4); set one of
 * the two, [detail] wins. A [section]
 * heading is drawn above the first option of each run of options sharing it.
 */
@Immutable
internal data class MarqueePickerOption(
    val label: String,
    val value: String,
    val detail: String? = null,
    val count: String? = null,
    val section: String? = null
)

/** The pill's last layout position; read when the panel opens, never observed (pills scroll). */
private class PickerAnchor {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Discover filter (approved sandbox `u4-search-discover-marquee` stage 10): a [MarqueeFilterPill]
 * ("Type  Movie  v") that opens the compact panel `search.pickerOffset` under it. The panel's
 * veil dims the whole screen, top bar included ([MarqueeVeilDialog]). Focus opens on
 * [selectedValue] (else the first row); Up/Down move, OK selects and closes, Back closes
 * unchanged; focus then returns to the pill. Callers put
 * `focusRequester`/`focusProperties` in [modifier] (they precede the pill's focus target).
 */
@Composable
internal fun MarqueeFilterPicker(
    label: String,
    value: String,
    options: List<MarqueePickerOption>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillRequester = remember { FocusRequester() }
    var openAt by remember { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    val close = {
        openAt = null
        scope.launch { pillRequester.requestFocusAfterFrames() }
        Unit
    }

    MarqueeFilterPill(
        label = label,
        value = value,
        pillRequester = pillRequester,
        onOpen = { bounds -> if (options.isNotEmpty()) openAt = bounds },
        modifier = modifier
    )

    openAt?.let { pillBounds ->
        val initialRequester = remember { FocusRequester() }
        MarqueeAnchoredPanel(anchor = pillBounds, onDismissRequest = close, initialFocus = initialRequester) {
            MarqueePickerOptionList(
                title = label,
                options = options,
                selectedValue = selectedValue,
                focusRequester = initialRequester,
                onSelect = { selected ->
                    close()
                    onSelect(selected)
                }
            )
        }
    }
}

/**
 * The glass filter pill ("Type  Movie  v"; focused = white): [onOpen] gets its window bounds (the
 * panel's anchor). [pillRequester] lets the caller return focus to it. Callers put
 * `focusRequester`/`focusProperties` in [modifier] (they precede the pill's focus target).
 */
@Composable
internal fun MarqueeFilterPill(
    label: String,
    value: String,
    pillRequester: FocusRequester,
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val search = marquee.search
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val anchor = remember { PickerAnchor() }
    var focused by remember { mutableStateOf(false) }

    Button(
        onClick = { anchor.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let(onOpen) },
        modifier = modifier
            .height(search.filterPillHeight)
            .focusRequester(pillRequester)
            .onFocusChanged { focused = it.isFocused }
            .onGloballyPositioned { anchor.coordinates = it },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = marquee.glassFill,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        contentPadding = PaddingValues(start = search.filterPillPaddingStart, end = search.filterPillPaddingEnd)
    ) {
        val secondary = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3
        Text(text = label, style = NuvioTheme.textStyles.caption, color = secondary, maxLines = 1)
        Spacer(modifier = Modifier.width(search.filterPillInnerGap))
        Text(text = value, style = NuvioTheme.textStyles.button, maxLines = 1)
        Spacer(modifier = Modifier.width(search.filterPillInnerGap))
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.cd_expand, label),
            tint = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage2,
            modifier = Modifier.size(search.filterPillIconSize)
        )
    }
}

/**
 * A compact panel (`search.pickerWidth`) `search.pickerOffset` under a pill's window bounds
 * [anchor], in a [MarqueeVeilDialog] (`search.pickerDim`). Its start edge meets the pill's start
 * ([alignEnd]: its end edge the pill's end), mirrored in RTL and kept on screen; it ends at least
 * `search.pickerBottomMargin` above the screen bottom (a long list scrolls inside). Back calls
 * [onDismissRequest]; [initialFocus] takes focus when it is first given and whenever it changes.
 */
@Composable
internal fun MarqueeAnchoredPanel(
    anchor: Rect,
    onDismissRequest: () -> Unit,
    alignEnd: Boolean = false,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val search = NuvioTheme.marquee.search
    MarqueeVeilDialog(onDismissRequest = onDismissRequest, veil = search.pickerDim, initialFocus = initialFocus) {
        val density = LocalDensity.current
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelWidthPx = with(density) { search.pickerWidth.roundToPx() }
            val x = (if (alignEnd != isRtl) anchor.right.roundToInt() - panelWidthPx else anchor.left.roundToInt())
                .coerceIn(0, (constraints.maxWidth - panelWidthPx).coerceAtLeast(0))
            val y = anchor.bottom.roundToInt() + with(density) { search.pickerOffset.roundToPx() }
            val maxPanelHeight = (maxHeight - with(density) { y.toDp() } - search.pickerBottomMargin)
                .coerceAtLeast(MIN_PANEL_HEIGHT)
            CompactPanel(
                modifier = Modifier
                    .absoluteOffset { IntOffset(x, y) }
                    .width(search.pickerWidth)
                    .heightIn(max = maxPanelHeight),
                content = content
            )
        }
    }
}

/**
 * A picker's content in a compact panel: the [title] eyebrow over one row per option (a tick on
 * [selectedValue]), section headings where an option's [MarqueePickerOption.section] changes,
 * scrolling inside; [focusRequester] sits on the selected row (else the first).
 */
@Composable
internal fun MarqueePickerOptionList(
    title: String,
    options: List<MarqueePickerOption>,
    selectedValue: String?,
    focusRequester: FocusRequester,
    onSelect: (String) -> Unit
) {
    val focusIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
    PanelEyebrow(text = title)
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        options.forEachIndexed { index, option ->
            if (option.section != null && option.section != options.getOrNull(index - 1)?.section) {
                PickerSection(option.section)
            }
            PlayerPanelRow(
                title = option.label,
                selected = option.value == selectedValue,
                onClick = { onSelect(option.value) },
                titleEnd = option.detail ?: option.count,
                titleEndInline = option.count == null,
                focusRequester = if (index == focusIndex) focusRequester else null
            )
        }
    }
}

/** `.l38sec`: a section heading inside the option list ("YOUR CHANNELS", "ALL"; Spec 73 "THIS TV"). */
@Composable
internal fun PickerSection(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.techBadge,
        color = NuvioTheme.marquee.onImage3,
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

private val MIN_PANEL_HEIGHT = 120.dp
