package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.ui.components.COMPACT_PANEL_FILL
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A compact panel [width] wide, centred over the [veil] in a [MarqueeVeilDialog] (the U5 consent
 * panel's shape; [color] = the panel fill, [CompactPanel]'s by default). It keeps the TV safe area above and below, so a long panel scrolls its own content inside. Back
 * calls [onDismissRequest]; [initialFocus] takes focus once per open. Text fields work inside
 * (the Dialog window takes the IME; `RenamePanel` / `RegexPatternPanel` precedent).
 */
@Composable
internal fun MarqueeCentredPanel(
    onDismissRequest: () -> Unit,
    veil: Color,
    width: Dp,
    initialFocus: FocusRequester? = null,
    color: Color = COMPACT_PANEL_FILL,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    MarqueeVeilDialog(onDismissRequest = onDismissRequest, veil = veil, initialFocus = initialFocus) {
        CompactPanel(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = NuvioTheme.layout.tvSafeVertical)
                .width(width),
            contentPadding = PaddingValues(
                start = tokens.panelPaddingHorizontal,
                top = tokens.panelPaddingTop,
                end = tokens.panelPaddingHorizontal,
                bottom = tokens.panelPaddingHorizontal
            ),
            color = color,
            content = content
        )
    }
}

/** A centred panel's action buttons: inset like its body text, `dialogActionTop` under it. */
@Composable
internal fun MarqueePanelActions(content: @Composable RowScope.() -> Unit) {
    val tokens = NuvioTheme.marquee.stream
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.dialogActionGap),
        modifier = Modifier.padding(
            start = tokens.dialogTextInset,
            top = tokens.dialogActionTop,
            end = tokens.dialogTextInset,
            bottom = tokens.dialogActionBottom
        ),
        content = content
    )
}
