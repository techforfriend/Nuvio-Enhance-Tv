package com.nuvio.tv.ui.screens.tip

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tip.TipLadder
import com.nuvio.tv.core.tip.TipLinkRepository
import com.nuvio.tv.core.tip.isSponsorsLink
import com.nuvio.tv.core.tip.tipLadder
import com.nuvio.tv.core.tip.tvCountry
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.components.marquee.rememberQrCode
import com.nuvio.tv.ui.screens.profile.ProfileWordmark
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The tip link for screens without the Activity's repository at hand (About, T3). */
@HiltViewModel
class TipViewModel @Inject constructor(repository: TipLinkRepository) : ViewModel() {
    /** The QR's link, null = no Tip (TipLinkRepository.link). */
    val link: StateFlow<String?> = repository.link
}

/**
 * One entry point's tip card (T3: Welcome, What's new, About). [open] captures the link, so a
 * refresh landing while the card is up neither swaps its QR nor closes it; closing puts focus back
 * on [focus], the entry's Tip button or row (D6), else (a paused link removed the entry meanwhile)
 * on the caller's fallback. Show it with [TipEntryDialog].
 */
@Stable
internal class TipEntryState {
    val focus = FocusRequester()

    /** The link the open card shows; null = closed. */
    var openLink by mutableStateOf<String?>(null)
        private set
    var refocus by mutableStateOf(false)
        private set

    fun open(link: String) {
        openLink = link
    }

    fun close() {
        openLink = null
        refocus = true
    }

    fun refocused() {
        refocus = false
    }
}

/** `.btn` + `.s81h` (stages b-welcome, c-whatsnew): Tip with its heart, on the Welcome and What's new cards. */
@Composable
internal fun TipButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.tip
    MarqueeGlassTextButton(
        label = stringResource(R.string.tip_button),
        onClick = onClick,
        modifier = modifier,
        icon = Icons.Filled.Favorite,
        iconTint = tokens.heart,
        focusedIconTint = tokens.heartOnFocus
    )
}

/**
 * The card of [state] while open, in its own window above the caller's; then focus back on the
 * entry, or on [fallback] (the caller's first button or row) when the entry is gone.
 */
@Composable
internal fun TipEntryDialog(state: TipEntryState, fallback: FocusRequester?) {
    state.openLink?.let { link -> TipDialog(link, onDismiss = state::close) }
    LaunchedEffect(state.refocus) {
        // Cleared after the request: clearing the key first would restart (cancel) this effect.
        if (state.refocus) {
            if (!state.focus.requestFocusAfterFrames()) fallback?.requestFocusAfterFrames()
            state.refocused()
        }
    }
}

/**
 * The tip card (Spec 81, sandbox spec-81-donate v3, stages d-tip-us / d-tip-in / e0-trigger) over
 * whatever opened it, in its own dialog window: Done and Back close it, so the card that opened it
 * (Welcome, What's new, About) is back with its Tip button focused (D6). I sent a tip swaps in the
 * thank-you (D7/D8), whose Done and Back close it too. [link] is the QR's, already validated
 * (TipLinkRepository); [country] picks the ladder (D2).
 */
@Composable
internal fun TipDialog(
    link: String,
    onDismiss: () -> Unit,
    country: String = tvCountry()
) {
    var thanks by rememberSaveable { mutableStateOf(false) }
    val cardDone = remember { FocusRequester() }
    val thanksDone = remember { FocusRequester() }
    // The dialog focuses Done on open, and again when the thank-you replaces the card.
    MarqueeVeilDialog(
        onDismissRequest = onDismiss,
        veil = NuvioTheme.marquee.tip.veil,
        initialFocus = if (thanks) thanksDone else cardDone
    ) {
        ProfileWordmark()
        if (thanks) TipThanks(doneFocus = thanksDone, onDone = onDismiss)
        else TipCardContent(link, tipLadder(country), doneFocus = cardDone, onSent = { thanks = true }, onDone = onDismiss)
    }
}

/** `.l11ql.s81l` + `.l11qc.s81c`: the pitch and ladder on the left, the QR card on the right; Done focused. */
@Composable
private fun TipCardContent(
    link: String,
    ladder: TipLadder,
    doneFocus: FocusRequester,
    onSent: () -> Unit,
    onDone: () -> Unit
) {
    val tokens = NuvioTheme.marquee.tip
    val settings = NuvioTheme.marquee.settings
    Box(modifier = Modifier.fillMaxSize()) {
        TipIntro(
            ladder = ladder,
            modifier = Modifier
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = tokens.introTop)
                .width(tokens.introWidth)
        )
        val shape = RoundedCornerShape(settings.signInCardRadius)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = NuvioTheme.layout.tvSafeHorizontal, top = tokens.cardTop, bottom = tokens.cardBottom)
                .width(settings.signInCardWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(settings.signInCardFill, shape)
                .border(NuvioTheme.spacing.hairline, settings.signInCardEdge, shape)
                .padding(
                    start = settings.signInCardPaddingHorizontal,
                    end = settings.signInCardPaddingHorizontal,
                    top = settings.signInCardPaddingTop,
                    bottom = settings.signInCardPaddingBottom
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap)
        ) {
            PanelEyebrowText(text = stringResource(R.string.tip_scan_eyebrow))
            Text(
                text = stringResource(R.string.tip_scan_line),
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage2,
                textAlign = TextAlign.Center
            )
            TipQrTile(link)
            Text(
                text = link.removePrefix("https://"),
                style = NuvioTheme.textStyles.welcomeRowLabel,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    tint = NuvioTheme.marquee.onImage3,
                    modifier = Modifier.size(NuvioTheme.marquee.welcome.rowIconSize)
                )
                Text(
                    text = stringResource(R.string.tip_message_line),
                    style = NuvioTheme.textStyles.welcomeRowValue,
                    color = NuvioTheme.marquee.onImage2,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.xs)
                )
            }
            Row(
                modifier = Modifier.padding(top = settings.signInCardGap),
                horizontalArrangement = Arrangement.spacedBy(tokens.chipGap)
            ) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.tip_sent),
                    onClick = onSent,
                    icon = Icons.Filled.Favorite,
                    iconTint = tokens.heart,
                    focusedIconTint = tokens.heartOnFocus
                )
                MarqueeGlassTextButton(
                    label = stringResource(R.string.tip_done),
                    onClick = onDone,
                    modifier = Modifier.focusRequester(doneFocus)
                )
            }
            // The terms at the card's foot, like the sign-in card's (`.l11terms{margin-top:auto}`);
            // GitHub Sponsors' own line on that backup link (D11).
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = stringResource(if (isSponsorsLink(link)) R.string.tip_terms_sponsors else R.string.tip_terms),
                    style = NuvioTheme.textStyles.profileLine,
                    color = NuvioTheme.marquee.onImage3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = tokens.termsWidth)
                )
            }
        }
    }
}

/** The white tile and the QR of [link], encoded off the main thread; empty while it encodes. */
@Composable
private fun TipQrTile(link: String) {
    val tokens = NuvioTheme.marquee.tip
    val tile = RoundedCornerShape(tokens.qrRadius)
    Box(
        modifier = Modifier
            .size(tokens.qrTile)
            .clip(tile)
            .background(NuvioPrimitives.white, tile)
            .padding(tokens.qrInset),
        contentAlignment = Alignment.Center
    ) {
        rememberQrCode(link)?.let { qr ->
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.cd_tip_qr),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/** `.l11ql.s81l`: eyebrow, title, pitch, the suggested ladder (not focusable) and the pay line. */
@Composable
private fun TipIntro(ladder: TipLadder, modifier: Modifier) {
    val tokens = NuvioTheme.marquee.tip
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(tokens.introGap)) {
        PanelEyebrowText(text = stringResource(R.string.tip_eyebrow))
        Text(
            text = stringResource(R.string.tip_title),
            style = NuvioTheme.textStyles.signInTitle.copy(fontSize = tokens.titleSize, lineHeight = tokens.titleLineHeight),
            color = NuvioPrimitives.white
        )
        Text(
            text = stringResource(R.string.tip_pitch),
            style = NuvioTheme.textStyles.signInIntro,
            color = NuvioTheme.marquee.onImage2
        )
        Column(verticalArrangement = Arrangement.spacedBy(tokens.ladderGap)) {
            Text(
                text = stringResource(R.string.tip_suggested),
                style = NuvioTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.marquee.onImage3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.chipGap), verticalAlignment = Alignment.CenterVertically) {
                ladder.amounts.forEachIndexed { index, amount -> LadderChip(amount, middle = index == 1) }
                Text(
                    text = stringResource(R.string.tip_any_amount),
                    style = NuvioTheme.textStyles.meta,
                    color = NuvioTheme.marquee.onImage2,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.xs)
                )
            }
        }
        Text(
            text = payLine(ladder),
            style = NuvioTheme.textStyles.meta,
            color = NuvioTheme.marquee.onImage2
        )
    }
}

/** `.s81amt .c`: an amount pill, the middle (typical) one lightly filled. */
@Composable
private fun LadderChip(amount: String, middle: Boolean) {
    val tokens = NuvioTheme.marquee.tip
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .height(tokens.chipHeight)
            .background(if (middle) tokens.chipMidFill else tokens.chipFill, shape)
            .border(NuvioTheme.spacing.hairline, tokens.chipEdge, shape)
            .padding(horizontal = tokens.chipPaddingHorizontal),
        contentAlignment = Alignment.Center
    ) {
        Text(text = amount, style = NuvioTheme.textStyles.settingsResultLabel.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = NuvioPrimitives.white)
    }
}

/** The pay line with its methods in bold white (`.s81pay b`): UPI first for India (D2). */
@Composable
private fun payLine(ladder: TipLadder): AnnotatedString {
    val method = stringResource(if (ladder.upiFirst) R.string.tip_pay_upi_method else R.string.tip_pay_methods)
    val template = stringResource(if (ladder.upiFirst) R.string.tip_pay_upi_line else R.string.tip_pay_line)
    val at = template.indexOf(METHOD_PLACEHOLDER)
    return buildAnnotatedString {
        // A translation that drops the placeholder still reads, without the bold methods.
        if (at < 0) {
            append(template)
            return@buildAnnotatedString
        }
        append(template.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = NuvioPrimitives.white)) { append(method) }
        append(template.substring(at + METHOD_PLACEHOLDER.length))
    }
}

private const val METHOD_PLACEHOLDER = "%1\$s"
