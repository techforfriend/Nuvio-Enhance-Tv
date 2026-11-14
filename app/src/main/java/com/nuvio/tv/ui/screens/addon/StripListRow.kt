package com.nuvio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import com.nuvio.tv.ui.theme.NuvioTheme

/** A dimmed row at rest (mockup: a disabled addon, a catalog hidden on Home). */
private const val DIMMED_ROW_ALPHA = 0.5f

/**
 * `.l8s` list row whose OK toggles an action strip under it (stages 11, 15; Q8 / Q9): lit with
 * the focus ring while focus is in the row or its [actions]; [dimmedAtRest] fades it to .5 when
 * focus is elsewhere. Back in an open strip closes it and returns to the row (the route's Back
 * comes after); the strip also closes when focus leaves. A null [actions] makes OK a no-op
 * (view-only rows). [position] is the row's index: when it changes while focus is inside (a move),
 * the row is brought into view, so focus follows the moved row. Callers key the row by id so a
 * moved row keeps its composition and its focused action. [up]: where Up from the row goes when
 * spatial search would miss it (a right-aligned button above a full-width row); Default = search.
 * Down from the row enters an open strip explicitly: spatial search skipped the indented strip for
 * the full-width row below (Shield 2026-11-10, Live TV Sources: Remove was out of D-pad reach).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StripListRow(
    focusRequester: FocusRequester,
    position: Int,
    dimmedAtRest: Boolean,
    actions: (@Composable RowScope.() -> Unit)?,
    onFocused: () -> Unit = {},
    up: FocusRequester = FocusRequester.Default,
    /** OK on the row runs this instead of opening the strip (the strip stays closed). */
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.(focusWithin: Boolean) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    var focusWithin by remember { mutableStateOf(false) }
    var stripOpen by remember { mutableStateOf(false) }
    // While onClick is set the strip is closed, so it does not come back open when onClick goes.
    val overridden = onClick != null
    LaunchedEffect(overridden) { if (overridden) stripOpen = false }
    val shape = RoundedCornerShape(tokens.rowRadius)
    val bringIntoView = remember { BringIntoViewRequester() }
    val strip = remember { FocusRequester() }
    // The strip composes a frame after OK opens it: Down before then must not name a detached requester.
    var stripPlaced by remember { mutableStateOf(false) }
    BackHandler(enabled = focusWithin && stripOpen) {
        stripOpen = false
        focusRequester.requestFocus()
    }
    LaunchedEffect(position) {
        if (focusWithin) {
            withFrameNanos { } // the moved row's new bounds are laid out first
            bringIntoView.bringIntoView()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged {
                focusWithin = it.hasFocus
                if (it.hasFocus) onFocused() else stripOpen = false
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    this.up = up
                    if (stripOpen && stripPlaced) down = strip
                }
                .focusRequester(focusRequester)
                .clip(shape)
                .background(if (focusWithin) marquee.glassFill else tokens.rowFill)
                .then(if (focusWithin) Modifier.border(marquee.ringWidth, marquee.ring, shape) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        when {
                            onClick != null -> onClick()
                            actions != null -> stripOpen = !stripOpen
                        }
                    }
                )
                .padding(horizontal = tokens.rowPaddingHorizontal, vertical = tokens.rowPaddingVertical)
                .alpha(if (dimmedAtRest && !focusWithin) DIMMED_ROW_ALPHA else 1f),
            horizontalArrangement = Arrangement.spacedBy(tokens.rowInnerGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content(focusWithin)
        }
        if (focusWithin && stripOpen && actions != null && onClick == null) {
            DisposableEffect(Unit) { onDispose { stripPlaced = false } }
            Row(
                modifier = Modifier
                    .padding(start = tokens.rowPaddingHorizontal)
                    .onPlaced { stripPlaced = true }
                    .focusRequester(strip)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(tokens.actionGap),
                content = actions
            )
        }
    }
}
