package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** How a pinned chip shows that it is the one in effect. */
internal enum class PinnedChipLook {
    /** Not in effect. */
    PLAIN,

    /** A saved filter that is on: filled, as a filter value that is on. */
    FILLED,

    /** The addon view showing: an inset white ring, as the shown view in the sources sheet. */
    RINGED
}

/**
 * A chip pinned to the Sources row (Spec 65 D12, sandbox `spec-65-stream-filters` v5 stage 2): a
 * `stream.chipHeight` glass pill with a small muted [originIcon] (the sheet it was pinned from),
 * [label] and, for an addon, its stream [count]. [look] marks the one in effect; focused = white
 * fill, black text, no scale. Callers put `focusRequester` / `onFocusChanged` in [modifier].
 */
@Composable
internal fun MarqueePinnedChip(
    label: String,
    originIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    look: PinnedChipLook = PinnedChipLook.PLAIN,
    count: Int? = null
) {
    val marquee = NuvioTheme.marquee
    val stream = marquee.stream
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(stream.chipHeight)
            .onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = if (look == PinnedChipLook.FILLED) stream.circleOnFill else marquee.glassFill,
            contentColor = if (look == PinnedChipLook.PLAIN) marquee.onImage2 else white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        border = ButtonDefaults.border(
            border = if (look == PinnedChipLook.RINGED) {
                Border(BorderStroke(stream.chipSelectedRing, white), shape = CircleShape)
            } else {
                Border.None
            },
            focusedBorder = Border.None,
            pressedBorder = Border.None
        ),
        contentPadding = PaddingValues(horizontal = stream.chipPadding)
    ) {
        Icon(
            imageVector = originIcon,
            contentDescription = null,
            tint = when {
                focused -> black.copy(alpha = SUFFIX_ALPHA)
                look == PinnedChipLook.PLAIN -> marquee.onImage3
                else -> white.copy(alpha = ACTIVE_DETAIL_ALPHA)
            },
            modifier = Modifier.size(stream.pinMarkSize)
        )
        Spacer(modifier = Modifier.width(stream.chipInnerGap))
        Text(text = label, style = NuvioTheme.textStyles.cardLabel, maxLines = 1)
        if (count != null) {
            Spacer(modifier = Modifier.width(stream.chipInnerGap))
            Text(
                text = count.toString(),
                style = NuvioTheme.textStyles.caption,
                color = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3,
                maxLines = 1
            )
        }
    }
}

/** The origin icon of a chip in effect (the mockup's `.ac.on .og`). */
private const val ACTIVE_DETAIL_ALPHA = 0.7f
