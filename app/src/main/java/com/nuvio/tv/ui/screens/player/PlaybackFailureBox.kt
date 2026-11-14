package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaybackFailure
import com.nuvio.tv.core.player.StepLine
import com.nuvio.tv.core.player.StepResult
import com.nuvio.tv.core.player.TriedSource
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

// Spec 75 mockup v2 (sandbox spec-75-failure-reasons, `.s75a` / `.s75det`), dp on the 960x540 canvas.
private val STEP_LINE_WIDTH = 660.dp
private val STEP_GAP = 12.dp
private val STEP_BAR_HEIGHT = 3.dp
private val STEP_INNER_GAP = 5.dp
private val STEP_LINE_TOP = 4.dp
private val DETAILS_MAX_WIDTH = 500.dp
private val DETAILS_RULE = 2.dp
private val DETAILS_PADDING_START = 12.dp
// The loading screen's previous-source line (`.hand .s75a`, `.hand .prev`): narrower, labels 10 sp.
private val PREVIOUS_STEP_LINE_WIDTH = 520.dp
private val PREVIOUS_STEP_GAP = 8.dp
private val PREVIOUS_STEP_LABEL_SIZE = 10.sp
private val PREVIOUS_LABEL_TOP = 6.dp
private val PREVIOUS_LABEL_GAP = 4.dp
private val ALSO_MAX_WIDTH = 440.dp
private const val STEP_TODO_ALPHA = 0.16f
private const val DETAILS_RULE_ALPHA = 0.18f

/**
 * The playback error box (Spec 75 S4, mockup stages 1-5): eyebrow "Playback error", the cause in
 * plain words, what to do, the step line, then the button row. Spec 86 R3 (sandbox
 * spec-86-failure-box-actions v1): the cause's fix buttons ([options], [PlaybackFailureActions])
 * lead the row and the first takes focus; Go Back and Details / Hide details (the technical lines
 * under the step line) always end it. Every button glass, only the focused one white. Back still
 * leaves (handleBackPress). [tried] = the sources the failover left before this one (S5, stage 6):
 * the eyebrow counts every source tried and a line under the body counts the earlier ones by reason.
 */
@Composable
internal fun PlaybackFailureBox(
    failure: PlaybackFailure,
    options: FailureBoxOptions,
    onAction: (FailureBoxAction) -> Unit,
    onGoBack: () -> Unit,
    backdropUrl: String?,
    modifier: Modifier = Modifier,
    tried: List<TriedSource> = emptyList()
) {
    val context = LocalContext.current
    val copy = remember(failure, tried, context) {
        PlaybackFailureCopy.of(failure, contextFailureStrings(context), tried)
    }
    var showDetails by remember(failure) { mutableStateOf(false) }
    val actions = options.actionsFor(failure.cause)
    val focusRequester = remember { FocusRequester() }
    var rowHasFocus by remember(failure) { mutableStateOf(false) }
    // The first button takes focus while the row holds none: on show, and when a focused button goes away.
    // Next source can appear later (the list lands after the box): it never takes the highlight from a
    // button that holds it (Arshad, escalation 353963a0).
    LaunchedEffect(failure, actions) { if (!rowHasFocus) focusRequester.requestFocusAfterFrames() }
    MarqueeMessage(
        eyebrow = copy.eyebrow,
        title = copy.title,
        body = AnnotatedString(copy.body),
        backdropUrl = backdropUrl,
        modifier = modifier
    ) {
        copy.also?.let { also ->
            Text(
                text = also,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage2,
                modifier = Modifier.widthIn(max = ALSO_MAX_WIDTH)
            )
        }
        failure.steps?.let { FailureStepLine(it, Modifier.padding(top = STEP_LINE_TOP)) }
        if (showDetails) FailureDetails(copy.details)
        val tokens = NuvioTheme.marquee.message
        // Up to five buttons outgrow the 520 dp message block: start-aligned past it, as the step line.
        Row(
            modifier = Modifier
                .padding(top = tokens.actionTop)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onFocusChanged { rowHasFocus = it.hasFocus },
            horizontalArrangement = Arrangement.spacedBy(tokens.actionGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEachIndexed { index, action ->
                // Keyed, so a button inserted before the focused one does not take over its slot (and focus).
                key(action) {
                    MarqueeGlassTextButton(
                        label = stringResource(action.labelRes(options)),
                        onClick = { onAction(action) },
                        modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }
            MarqueeGlassTextButton(
                label = stringResource(R.string.player_go_back),
                onClick = onGoBack,
                modifier = if (actions.isEmpty()) Modifier.focusRequester(focusRequester) else Modifier
            )
            MarqueeGlassTextButton(
                label = stringResource(if (showDetails) R.string.failure_hide_details else R.string.failure_details),
                onClick = { showDetails = !showDetails }
            )
        }
    }
}

private fun FailureBoxAction.labelRes(options: FailureBoxOptions): Int = when (this) {
    FailureBoxAction.RETRY -> R.string.action_retry
    FailureBoxAction.SWITCH_ENGINE -> if (options.engineTarget == InternalPlayerEngine.EXOPLAYER) {
        R.string.failure_action_play_with_exoplayer
    } else {
        R.string.failure_action_play_with_mpv
    }
    FailureBoxAction.NEXT_SOURCE -> R.string.failure_action_next_source
    FailureBoxAction.EXTERNAL_PLAYER -> R.string.player_tray_external_player
}

/**
 * Spec 75 S5 (mockup stage 8): the failed source's step line on the loading screen, under a
 * "Previous source" label; the error box's line, narrower, with smaller labels.
 */
@Composable
internal fun PreviousSourceStepLine(line: StepLine) {
    Column(
        modifier = Modifier.padding(top = PREVIOUS_LABEL_TOP),
        verticalArrangement = Arrangement.spacedBy(PREVIOUS_LABEL_GAP)
    ) {
        Text(
            text = stringResource(R.string.failure_previous_source).uppercase(),
            style = NuvioTheme.textStyles.eyebrow.copy(fontSize = PREVIOUS_STEP_LABEL_SIZE),
            color = NuvioTheme.marquee.onImage3,
            maxLines = 1
        )
        FailureStepLine(line, compact = true)
    }
}

/**
 * D2: six segments in one row, a bar each with its label and time under it; [compact] is the
 * loading screen's previous-source size.
 */
@Composable
internal fun FailureStepLine(line: StepLine, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val marquee = NuvioTheme.marquee
    val baseLabel = NuvioTheme.textStyles.eyebrow.copy(letterSpacing = 0.sp)
    val labelStyle = if (compact) baseLabel.copy(fontSize = PREVIOUS_STEP_LABEL_SIZE) else baseLabel
    val timeStyle = baseLabel.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    Row(
        // Wider than the message block (520 dp), as drawn: start-aligned, not centred on the overflow.
        modifier = modifier
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width(if (compact) PREVIOUS_STEP_LINE_WIDTH else STEP_LINE_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(if (compact) PREVIOUS_STEP_GAP else STEP_GAP)
    ) {
        line.steps.forEach { result ->
            val (bar, labelColor, timeColor) = when (result.status) {
                StepResult.Status.PASSED -> Triple(NuvioPrimitives.successOnImage, marquee.onImage2, marquee.onImage3)
                StepResult.Status.FAILED -> Triple(NuvioPrimitives.errorOnImage, NuvioPrimitives.errorOnImage, NuvioPrimitives.errorOnImage)
                StepResult.Status.NOT_REACHED -> Triple(NuvioPrimitives.white.copy(alpha = STEP_TODO_ALPHA), marquee.onImage3, marquee.onImage3)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(STEP_INNER_GAP)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(STEP_BAR_HEIGHT)
                        .background(bar, RoundedCornerShape(STEP_BAR_HEIGHT))
                )
                Text(
                    text = stringResource(PlaybackFailureCopy.stepLabel(result.step)),
                    style = if (result.status == StepResult.Status.NOT_REACHED) {
                        labelStyle.copy(fontWeight = FontWeight.Medium)
                    } else {
                        labelStyle
                    },
                    color = labelColor,
                    maxLines = 1,
                    softWrap = false
                )
                // A blank line keeps the segments level when a step has no time (D4: unmeasured).
                val time = result.durationMs?.let { ms ->
                    PlaybackFailureCopy.duration(ms, contextFailureStrings(context))
                }
                Text(text = time ?: " ", style = timeStyle, color = timeColor, maxLines = 1)
            }
        }
    }
}

/** The technical lines, a thin rule on their left; bold = the fact, muted = its note. */
@Composable
private fun FailureDetails(lines: List<List<DetailSpan>>) {
    val marquee = NuvioTheme.marquee
    val style = NuvioTheme.textStyles.eyebrow.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    )
    val rule = NuvioPrimitives.white.copy(alpha = DETAILS_RULE_ALPHA)
    Column(
        modifier = Modifier
            .widthIn(max = DETAILS_MAX_WIDTH)
            .drawBehind {
                val x = DETAILS_RULE.toPx() / 2f
                drawLine(rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = DETAILS_RULE.toPx())
            }
            .padding(start = DETAILS_PADDING_START, top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        lines.forEach { spans ->
            Text(
                text = buildAnnotatedString {
                    spans.forEachIndexed { index, span ->
                        if (index > 0) append(' ')
                        when (span.tone) {
                            DetailSpan.Tone.PLAIN -> append(span.text)
                            DetailSpan.Tone.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                            DetailSpan.Tone.MUTED -> withStyle(SpanStyle(color = marquee.onImage3)) { append(span.text) }
                        }
                    }
                },
                style = style,
                color = marquee.onImage2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
