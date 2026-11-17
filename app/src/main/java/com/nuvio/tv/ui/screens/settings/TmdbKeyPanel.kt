package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TMDB_KEY_LENGTH
import com.nuvio.tv.core.tmdb.tmdbKeyInput
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.components.marquee.rememberQrCode
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Spec 52 T1 key panel (sandbox `spec-52-public-build-ui`, stages t1-entry-both and t1-rejected):
 * the key field (focused with the keyboard down so the QR and buttons stay in view, OK opens it:
 * 12/N-3; whitespace dropped, 32 at most), the how-to note (worded for [buildHasKey], a build with
 * its own key: 12/S63 2026-11-04), or in its place why the last check
 * failed; while [phoneUrl] is set (the phone page runs), the phone block:
 * its QR and address, the screen kept on as for Spec 44's QR card; then Cancel, or Remove key in its
 * place while a key is saved on this TV ([hasUserKey]; stage t1-saved-panel: three buttons do not
 * fit), and Check and save. IME Done moves to Check and save. While
 * [state] is CHECKING the button reads "Checking…" (the ViewModel ignores OK meanwhile); a rejected
 * key turns the field's edge red and puts focus back in it. Back or Cancel calls [onDismiss].
 */
@Composable
internal fun TmdbKeySidePanel(
    state: TmdbKeyPanelState,
    hasUserKey: Boolean,
    buildHasKey: Boolean,
    phoneUrl: String?,
    onSubmit: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    var draft by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    val checking = state == TmdbKeyPanelState.CHECKING
    LaunchedEffect(state) {
        if (state == TmdbKeyPanelState.REJECTED) fieldFocus.requestFocusAfterFrames()
    }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = fieldFocus,
        fillHeight = false,
        alignment = Alignment.TopEnd
    ) {
        PanelEyebrow(text = stringResource(R.string.tmdb_api_key_title))
        Column(verticalArrangement = Arrangement.spacedBy(marquee.collections.panelGap)) {
            MarqueeNameField(
                value = draft,
                onValueChange = { draft = it },
                maxLength = TMDB_KEY_LENGTH,
                placeholder = stringResource(R.string.tmdb_api_key_placeholder),
                focusRequester = fieldFocus,
                onDone = { checkFocus.requestFocus() },
                // No suggestions, and the keyboard never learns the key.
                keyboardType = KeyboardType.Password,
                isError = state == TmdbKeyPanelState.REJECTED,
                inputTransform = ::tmdbKeyInput,
                textStyle = NuvioTheme.textStyles.keyField,
                keyboardOnOk = true
            )
            when (state) {
                TmdbKeyPanelState.REJECTED -> SettingsPanelErrorLine(stringResource(R.string.tmdb_api_key_rejected))
                TmdbKeyPanelState.UNREACHABLE -> SettingsPanelErrorLine(stringResource(R.string.tmdb_api_key_unreachable))
                TmdbKeyPanelState.OPEN, TmdbKeyPanelState.CHECKING -> TmdbKeyNote(buildHasKey)
            }
        }
        phoneUrl?.let {
            SettingsPhoneQrBlock(
                url = it,
                divider = stringResource(R.string.tmdb_api_key_phone_divider),
                title = stringResource(R.string.tmdb_api_key_phone_title)
            )
        }
        MarqueePanelActions {
            if (!hasUserKey) {
                CollectionsHeaderButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            } else {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.tmdb_api_key_remove),
                    onClick = onRemove,
                    height = marquee.primaryButton.compactHeight,
                    horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall,
                    restingLabelColor = NuvioPrimitives.errorOnImage
                )
            }
            MarqueePrimaryButton(
                label = stringResource(if (checking) R.string.tmdb_api_key_checking else R.string.tmdb_api_key_check),
                icon = null,
                onClick = { onSubmit(draft) },
                modifier = if (checking) Modifier.alpha(tokens.busyAlpha) else Modifier,
                focusRequester = checkFocus,
                compact = true
            )
        }
    }
}

/** `.l8p .note`: where the key comes from, the path in white. */
@Composable
private fun TmdbKeyNote(buildHasKey: Boolean) {
    val path = stringResource(R.string.tmdb_api_key_note_path)
    val note = stringResource(
        if (buildHasKey) R.string.tmdb_api_key_note_built_in else R.string.tmdb_api_key_note,
        path
    )
    val start = note.indexOf(path)
    Text(
        text = buildAnnotatedString {
            append(note)
            if (start >= 0) {
                addStyle(SpanStyle(color = NuvioPrimitives.white, fontWeight = FontWeight.SemiBold), start, start + path.length)
            }
        },
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(horizontal = NuvioTheme.marquee.settings.panelTextInset)
    )
}

/**
 * `.s52or` + `.s52qrrow` (Spec 52 key panel; Spec 97 Access headers): the [divider], then the QR of
 * [url] beside [title], the same-Wi-Fi line and the address; the screen stays on while it shows.
 */
@Composable
internal fun SettingsPhoneQrBlock(url: String, divider: String, title: String) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    val qr = rememberQrCode(url)
    Column(modifier = Modifier.keepScreenOn()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = tokens.panelTextInset, top = 10.dp, end = tokens.panelTextInset, bottom = 10.dp)
        ) {
            TmdbKeyDividerRule()
            Text(
                text = divider.uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = marquee.onImage3
            )
            TmdbKeyDividerRule()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.keyQrGap),
            modifier = Modifier.padding(horizontal = tokens.panelTextInset)
        ) {
            Box(
                modifier = Modifier
                    .size(tokens.keyQrSize)
                    .clip(RoundedCornerShape(tokens.keyQrRadius))
                    .background(NuvioPrimitives.white),
                contentAlignment = Alignment.Center
            ) {
                qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier.size(tokens.keyQrSize * tokens.keyQrFill)
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    style = NuvioTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = NuvioPrimitives.white
                )
                Text(
                    text = stringResource(R.string.photo_upload_wifi),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Text(
                    text = url,
                    style = NuvioTheme.textStyles.caption.copy(
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = NuvioPrimitives.white,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** One side of the `.s52or` divider: a 1 dp white .12 line taking the free width. */
@Composable
private fun RowScope.TmdbKeyDividerRule() {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(1.dp)
            .background(NuvioPrimitives.white.copy(alpha = 0.12f))
    )
}
