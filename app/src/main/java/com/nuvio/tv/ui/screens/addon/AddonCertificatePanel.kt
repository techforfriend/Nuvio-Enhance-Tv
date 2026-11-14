package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.tls.AddonCertProbeResult
import com.nuvio.tv.core.network.tls.addonServerLabel
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spec 68 X7 certificate panel (approved sandbox `s068-x7-tls-confirm` v1): the U5 consent shape,
 * 560 dp over a dark veil. Stage 1/2/5 "Trust this addon server?": host, the reason sentence, what
 * Trust does, the full SHA-256 with its expiry, Trust this server + Cancel. Stage 4 "certificate
 * changed" (this host:port had another pin): both fingerprints side by side, Trust the new
 * certificate + Keep paused. The safe button (right) takes focus; Back = the safe button.
 */
@Composable
internal fun AddonCertificatePanel(
    state: AddonCertificatePanelState,
    onTrust: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    val probed = state.probed
    val label = addonServerLabel(probed.host, probed.port)
    val safeFocus = remember { FocusRequester() }
    val inset = Modifier.padding(horizontal = tokens.dialogTextInset)
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = tokens.certDim,
        width = tokens.consentWidth,
        initialFocus = safeFocus
    ) {
        PanelEyebrow(
            text = stringResource(
                if (state.changed) R.string.addon_cert_changed_eyebrow else R.string.addon_cert_trust_eyebrow
            )
        )
        Text(
            text = label,
            style = NuvioTheme.textStyles.certHost,
            color = NuvioPrimitives.white,
            modifier = inset.padding(bottom = tokens.certHostBottom)
        )
        Text(
            text = certificateBody(state, label),
            style = NuvioTheme.textStyles.panelBody,
            color = tokens.dialogBody,
            modifier = inset
        )
        Column(
            modifier = Modifier
                .padding(start = tokens.dialogTextInset, top = tokens.certFingerprintTop, end = tokens.dialogTextInset)
                .fillMaxWidth()
                .background(tokens.certFingerprintFill, RoundedCornerShape(tokens.certFingerprintRadius))
                .padding(
                    horizontal = tokens.certFingerprintPaddingHorizontal,
                    vertical = tokens.certFingerprintPaddingVertical
                )
        ) {
            val validUntil = certificateDate(probed.leaf.notAfter.time)
            if (state.changed) {
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.certColumnGap)) {
                    Fingerprint(
                        caption = stringResource(R.string.addon_cert_trusted_on, certificateDate(probed.previousTrustedAtMs ?: 0L)),
                        lines = fingerprintLines(probed.previousSha256.orEmpty(), groupsPerLine = 4),
                        color = tokens.certOldFingerprint,
                        modifier = Modifier.weight(1f)
                    )
                    Fingerprint(
                        caption = stringResource(R.string.addon_cert_now_valid_until, validUntil),
                        lines = fingerprintLines(probed.sha256, groupsPerLine = 4),
                        color = tokens.certFingerprint,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Fingerprint(
                    caption = stringResource(R.string.addon_cert_fingerprint_label, validUntil),
                    lines = fingerprintLines(probed.sha256, groupsPerLine = 8),
                    color = tokens.certFingerprint
                )
            }
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(if (state.changed) R.string.addon_cert_trust_new else R.string.addon_cert_trust),
                onClick = onTrust
            )
            MarqueeGlassTextButton(
                // Spec 68 D11 Q5: a changed certificate on Install says Cancel.
                label = stringResource(
                    if (state.changed && state.addonName != null) R.string.addon_cert_keep_paused else R.string.action_cancel
                ),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(safeFocus)
            )
        }
    }
}

@Composable
private fun Fingerprint(caption: String, lines: String, color: Color, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.stream
    Column(modifier = modifier) {
        Text(text = caption.uppercase(), style = NuvioTheme.textStyles.certCaption, color = tokens.certCaption)
        Text(text = lines, style = NuvioTheme.textStyles.certFingerprint, color = color)
    }
}

/** The body: the reason (or the change) and, after a blank line, what Trust does (or what a change means). */
@Composable
private fun certificateBody(state: AddonCertificatePanelState, label: String): String {
    val probed = state.probed
    if (state.changed) {
        val trustedOn = certificateDate(probed.previousTrustedAtMs ?: 0L)
        val change = if (state.addonName != null) {
            stringResource(R.string.addon_cert_changed_body, label, trustedOn, state.addonName)
        } else {
            stringResource(R.string.addon_cert_changed_body_install, label, trustedOn)
        }
        return change + "\n\n" + stringResource(R.string.addon_cert_changed_note)
    }
    val reason = when (probed.reason) {
        AddonCertProbeResult.Reason.SELF_SIGNED -> stringResource(R.string.addon_cert_reason_self_signed, label)
        AddonCertProbeResult.Reason.UNKNOWN_ISSUER -> stringResource(R.string.addon_cert_reason_unknown_issuer, label)
        AddonCertProbeResult.Reason.WRONG_NAME ->
            stringResource(R.string.addon_cert_reason_wrong_name, label, probed.shownName ?: label)
        AddonCertProbeResult.Reason.EXPIRED ->
            stringResource(R.string.addon_cert_reason_expired, label, certificateDate(probed.leaf.notAfter.time))
    }
    return reason + "\n\n" + stringResource(R.string.addon_cert_trust_note, label)
}

/** "14 Mar 2027" in the device locale (the mockup's date form; captions uppercase it). */
private fun certificateDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))
