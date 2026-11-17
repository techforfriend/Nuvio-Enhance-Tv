package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Spec 97 X4 Access headers panel (approved sandbox `s097-x4-x5-network` v5): stage 2 no sites
 * (the note, "No sites yet", the phone QR, Done focused), stage 4 the saved sites (one row each:
 * host and header names, never a value; OK asks to remove; the QR to add another), stage 8 the
 * list can't be read (D17: amber line, no QR, Done focused beside "Remove saved sites", D25), and
 * in its place a confirm (stages 5 and 9: Cancel focused, red Remove). [phoneUrl] null = no QR
 * (no network, or the page isn't running). Back closes the panel, or a confirm.
 */
@Composable
internal fun AccessHeadersSidePanel(
    sites: AccessSitesView,
    confirm: AccessConfirm?,
    phoneUrl: String?,
    onAskRemoveSite: (AccessSiteSummary) -> Unit,
    onAskRemoveAll: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (confirm != null) {
        AccessHeadersConfirmPanel(confirm = confirm, onRemove = onConfirmRemove, onCancel = onCancelConfirm)
        return
    }
    val marquee = NuvioTheme.marquee
    val firstFocus = remember { FocusRequester() }
    val list = (sites as? AccessSitesView.Sites)?.sites
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = firstFocus,
        fillHeight = false,
        alignment = Alignment.TopEnd
    ) {
        val title = stringResource(R.string.access_headers_title)
        PanelEyebrow(
            text = if (list.isNullOrEmpty()) title
            else "$title · ${pluralStringResource(R.plurals.access_headers_sites, list.size, list.size)}"
        )
        when {
            // Loading (opened before the first read lands): the eyebrow and Done until it does.
            list == null -> if (sites == AccessSitesView.Unreadable) {
                AccessHeadersWarnLine(stringResource(R.string.access_headers_unread_title))
                SettingsPanelCaption(stringResource(R.string.access_headers_unread_note))
            }
            list.isEmpty() -> {
                SettingsPanelCaption(stringResource(R.string.access_headers_note))
                Text(
                    text = stringResource(R.string.access_headers_empty),
                    style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium),
                    color = marquee.onImage3,
                    modifier = Modifier.padding(horizontal = marquee.settings.panelTextInset)
                )
            }
            else -> {
                Column {
                    list.forEachIndexed { index, site ->
                        PlayerPanelRow(
                            title = site.label,
                            selected = false,
                            onClick = { onAskRemoveSite(site) },
                            subtitle = site.headerNames.joinToString(" · "),
                            titleEnd = stringResource(R.string.access_headers_remove),
                            focusRequester = firstFocus.takeIf { index == 0 }
                        )
                    }
                }
                SettingsPanelCaption(
                    text = stringResource(R.string.access_headers_saved_note),
                    modifier = Modifier.padding(top = marquee.settings.messageIconGap)
                )
            }
        }
        if (list != null && phoneUrl != null) {
            SettingsPhoneQrBlock(
                url = phoneUrl,
                divider = stringResource(
                    if (list.isEmpty()) R.string.access_headers_phone_divider else R.string.access_headers_phone_divider_another
                ),
                title = stringResource(R.string.access_headers_phone_title)
            )
        }
        MarqueePanelActions {
            MarqueePrimaryButton(
                label = stringResource(R.string.access_headers_done),
                icon = null,
                onClick = onDismiss,
                focusRequester = firstFocus.takeIf { list.isNullOrEmpty() },
                compact = true
            )
            if (sites == AccessSitesView.Unreadable) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.access_headers_remove_all),
                    onClick = onAskRemoveAll,
                    height = marquee.primaryButton.compactHeight,
                    horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall
                )
            }
        }
    }
}

/** `.s97warn`: the warning icon, then [text] in amber. */
@Composable
private fun AccessHeadersWarnLine(text: String) {
    val tokens = NuvioTheme.marquee.settings
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.messageIconGap),
        modifier = Modifier.padding(horizontal = tokens.panelTextInset, vertical = tokens.messageIconGap)
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = NuvioPrimitives.warning,
            modifier = Modifier.size(tokens.messageIconSize)
        )
        Text(
            text = text,
            style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioPrimitives.warningOnTint
        )
    }
}

/** Stages 5 and 9: the compact confirm, Cancel focused (the safe button first), Remove in red. */
@Composable
private fun AccessHeadersConfirmPanel(confirm: AccessConfirm, onRemove: () -> Unit, onCancel: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val cancelFocus = remember { FocusRequester() }
    MarqueeSidePanelDialog(
        onDismissRequest = onCancel,
        width = marquee.settings.pickerWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = cancelFocus,
        fillHeight = false,
        alignment = Alignment.TopEnd
    ) {
        when (confirm) {
            is AccessConfirm.Site -> {
                PanelEyebrow(text = stringResource(R.string.access_headers_remove_site_title, confirm.site.label))
                val count = confirm.site.headerNames.size
                SettingsPanelCaption(pluralStringResource(R.plurals.access_headers_remove_site_body, count, count))
            }
            AccessConfirm.All -> {
                PanelEyebrow(text = stringResource(R.string.access_headers_remove_all_title))
                SettingsPanelCaption(stringResource(R.string.access_headers_remove_all_body))
            }
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancelFocus),
                height = marquee.primaryButton.compactHeight,
                horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.access_headers_remove),
                onClick = onRemove,
                height = marquee.primaryButton.compactHeight,
                horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
                restingLabelColor = NuvioPrimitives.errorOnImage
            )
        }
    }
}
