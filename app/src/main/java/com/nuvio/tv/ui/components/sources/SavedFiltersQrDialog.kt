package com.nuvio.tv.ui.components.sources

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.components.marquee.rememberQrCode
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The saved filters' phone dialog (Spec 65 S9; sandbox `spec-65-stream-filters` stage 8): a centred
 * card with the QR of [url], what the page does, and [hostLabel] (the caller passes the TV's
 * address, never the token-bearing path). With [error] set (no network, no free port) the card
 * says that instead and shows no QR. Nothing in it takes focus: Back calls [onDismiss]. The screen
 * stays on while the QR shows (the viewer is on the phone).
 */
@Composable
internal fun SavedFiltersQrDialog(
    url: String?,
    hostLabel: String?,
    error: String?,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val qrTokens = marquee.settings
    val shape = RoundedCornerShape(tokens.sheetRadius)
    MarqueeVeilDialog(onDismissRequest = onDismiss, veil = tokens.phoneVeil) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .width(tokens.phoneCardWidth)
                .clip(shape)
                .background(tokens.phoneCardFill)
                .border(1.dp, tokens.phoneCardEdge, shape)
                .padding(tokens.phoneCardPadding)
                // Only while a page runs: its lifetime bounds this; a message alone must not hold the screen.
                .then(if (url != null) Modifier.keepScreenOn() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(tokens.phoneCardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (url != null) {
                val qr = rememberQrCode(url)
                Box(
                    modifier = Modifier
                        .size(qrTokens.qrSize)
                        .clip(RoundedCornerShape(qrTokens.keyQrRadius))
                        .background(NuvioPrimitives.white),
                    contentAlignment = Alignment.Center
                ) {
                    qr?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.cd_qr_code),
                            modifier = Modifier.size(qrTokens.qrSize * qrTokens.keyQrFill)
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.stream_sheet_filters_phone),
                    style = NuvioTheme.textStyles.welcomeDevice,
                    color = NuvioPrimitives.white
                )
                Text(
                    text = error ?: stringResource(R.string.stream_filters_phone_body),
                    style = NuvioTheme.textStyles.bodyDetail,
                    color = marquee.onImage2
                )
                if (hostLabel != null) {
                    Text(
                        text = hostLabel,
                        style = NuvioTheme.textStyles.welcomeRowLabel,
                        color = NuvioPrimitives.white
                    )
                }
            }
        }
    }
}
