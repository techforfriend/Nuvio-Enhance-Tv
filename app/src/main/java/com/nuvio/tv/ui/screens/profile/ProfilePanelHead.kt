package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The head of a profile side panel (u10 stage 6 `.l10ph`; Profile options, My photo, Rating limit):
 * [leading] (the 44 dp avatar or a photo thumbnail), then [title] over an optional [subtitle].
 */
@Composable
internal fun ProfilePanelHead(title: String, subtitle: String?, leading: @Composable () -> Unit) {
    val tokens = NuvioTheme.marquee.profiles
    Row(
        modifier = Modifier.padding(
            start = tokens.optionsHeadPaddingHorizontal,
            end = tokens.optionsHeadPaddingHorizontal,
            top = tokens.optionsHeadPaddingTop,
            bottom = tokens.optionsHeadPaddingBottom
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.optionsHeadGap)
    ) {
        leading()
        Column {
            Text(
                text = title,
                style = NuvioTheme.textStyles.panelHeadName,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = NuvioTheme.textStyles.caption,
                    color = NuvioTheme.marquee.onImage3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
