@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.applySubtitleLook
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * `.l11pv` (stage 21): a Tears of Steel still with one cue drawn by the player's own SubtitleView
 * and look ([applySubtitleLook]: colours, edge, weight, offset), the size scaled to the strip.
 * Still: (CC) Blender Foundation, mango.blender.org, CC BY 3.0 (credited in docs/design.md).
 * [hdr]: draw [style]'s HDR look ([SubtitleStyleSettings.forOutput], as the player does) and tag it
 * "Preview · HDR" (Spec 62 D12).
 */
@Composable
internal fun SubtitlePreview(style: SubtitleStyleSettings, modifier: Modifier = Modifier, hdr: Boolean = false) {
    val tokens = NuvioTheme.marquee.settings
    val line = stringResource(R.string.sub_preview_line)
    val textScale = tokens.previewTextScale
    Box(
        modifier = modifier
            .padding(bottom = tokens.previewBottom)
            .fillMaxWidth()
            .height(tokens.previewHeight)
            .clip(RoundedCornerShape(tokens.previewRadius))
    ) {
        Image(
            painter = painterResource(R.drawable.settings_subtitle_preview),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        AndroidView(
            factory = { context -> SubtitleView(context) },
            update = { view ->
                view.applySubtitleLook(style.forOutput(hdrOutput = hdr), ownSsaStyle = false, textScale = textScale)
                view.setCues(listOf(Cue.Builder().setText(line).build()))
            },
            // A picture only: D-pad moves between the chips and the rows straight past it.
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false }
        )
        Text(
            text = stringResource(if (hdr) R.string.sub_preview_tag_hdr else R.string.sub_preview_tag).uppercase(),
            style = NuvioTheme.textStyles.eyebrow,
            color = tokens.previewTagText,
            modifier = Modifier
                .padding(start = tokens.previewTagInsetStart, top = tokens.previewTagInsetTop)
                .background(tokens.previewTagFill, RoundedCornerShape(tokens.previewTagRadius))
                .padding(horizontal = tokens.previewTagPaddingHorizontal, vertical = tokens.previewTagPaddingVertical)
        )
    }
}
