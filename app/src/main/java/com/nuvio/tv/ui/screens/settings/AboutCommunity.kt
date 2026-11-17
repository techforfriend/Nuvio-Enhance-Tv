package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.screens.profile.ProfileWordmark
import com.nuvio.tv.ui.screens.tip.TipIntroColumn
import com.nuvio.tv.ui.screens.tip.TipQrCard
import com.nuvio.tv.ui.screens.tip.TipQrCardTop
import com.nuvio.tv.ui.theme.NuvioTheme

/** Spec 124: the Nuvio KE subreddit, the About row's QR (D2). */
internal const val COMMUNITY_URL = "https://www.reddit.com/r/nuvioke/"

/** `.l8r` (sandbox spec-124-community-row v1, stage a1-about): r/nuvioke, its line and a chevron; no icon (D6). */
@Composable
internal fun AboutCommunityRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    MarqueeFormRow(
        label = stringResource(R.string.community_row_title),
        helper = stringResource(R.string.community_row_subtitle),
        onClick = onOpen,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight,
        modifier = modifier
    ) { _ ->
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/**
 * Stage b1-reddit: the tip card's layout (Spec 81) with the subreddit's pitch on the left and the
 * QR of [link] on the right, its link written out and Done, focused on open. Done and Back call
 * [onDismiss].
 */
@Composable
internal fun CommunityDialog(link: String, onDismiss: () -> Unit) {
    val tokens = NuvioTheme.marquee.tip
    val done = remember { FocusRequester() }
    MarqueeVeilDialog(onDismissRequest = onDismiss, veil = tokens.veil, initialFocus = done) {
        ProfileWordmark()
        Box(modifier = Modifier.fillMaxSize()) {
            TipIntroColumn(
                eyebrow = stringResource(R.string.community_section),
                title = stringResource(R.string.community_title),
                pitch = stringResource(R.string.community_pitch)
            )
            TipQrCard(gap = tokens.communityCardGap) {
                TipQrCardTop(
                    eyebrow = stringResource(R.string.community_scan_eyebrow),
                    line = stringResource(R.string.community_scan_line),
                    link = link,
                    qrDescription = stringResource(R.string.cd_community_qr),
                    qrMargin = tokens.communityQrMargin
                )
                MarqueeGlassTextButton(
                    label = stringResource(R.string.tip_done),
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = tokens.communityDoneTop).focusRequester(done)
                )
            }
        }
    }
}
