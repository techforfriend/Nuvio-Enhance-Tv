package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

// The notes' inset and the lock icon (sandbox stage 2 `.l8p .note` / `.warn`).
private val NOTE_PADDING_HORIZONTAL = 6.dp
private val NOTE_GAP = 8.dp
private val LOCK_ICON_SIZE = 15.dp

/**
 * The Rating limit panel (Spec 64 K2, sandbox `spec-64-k2-rating-cap` stage 2): the profile's head
 * ("Set on this TV only"), No limit then the D7 levels with the ratings each adds, the [current]
 * one ticked and focused on open; OK calls [onPick] (the caller closes the panel). Under them what
 * any limit does, and, when [openProfiles] is not empty, the profiles [profileName] could still
 * switch to (no PIN, no limit; D10).
 */
@Composable
internal fun RatingLimitPanel(
    profileName: String,
    avatarColorHex: String,
    avatarArt: AvatarArt?,
    current: ContentRatingCap?,
    openProfiles: List<String>,
    onPick: (ContentRatingCap?) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    val options: List<ContentRatingCap?> = remember { listOf(null) + RatingLimitLevels }
    val requesters = remember { options.associateWith { FocusRequester() } }
    val initialFocus = requesters.getValue(ratingLimitInitialFocus(current))
    val name = profileName.trim().ifEmpty { stringResource(R.string.profile_live_tv_unnamed) }
    MarqueeSidePanel(
        onDismiss = onDismiss,
        initialFocus = initialFocus,
        width = tokens.optionsPanelWidth
    ) {
        PanelEyebrow(text = stringResource(R.string.profile_rating_limit_label))
        ProfilePanelHead(title = name, subtitle = stringResource(R.string.rating_limit_this_tv)) {
            ProfileAvatarCircle(name = profileName, colorHex = avatarColorHex, size = tokens.optionsAvatarSize, art = avatarArt)
        }
        PanelRule()
        options.forEach { cap ->
            PlayerPanelRow(
                title = stringResource(cap?.labelRes() ?: R.string.rating_limit_none),
                subtitle = when {
                    cap == null -> stringResource(R.string.rating_limit_none_sub)
                    // The first level lists its ratings bare; each later one adds to it.
                    cap != RatingLimitLevels.first() -> stringResource(R.string.rating_limit_adds)
                    else -> null
                },
                subtitleTags = cap?.chipRatings().orEmpty(),
                selected = cap == current,
                focusRequester = requesters.getValue(cap),
                onClick = { onPick(cap) }
            )
        }
        PanelRule()
        Text(
            text = stringResource(R.string.rating_limit_note),
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage2,
            modifier = Modifier.padding(horizontal = NOTE_PADDING_HORIZONTAL)
        )
        if (openProfiles.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = NOTE_PADDING_HORIZONTAL, end = NOTE_PADDING_HORIZONTAL, top = NOTE_GAP),
                horizontalArrangement = Arrangement.spacedBy(NOTE_GAP),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = NuvioPrimitives.warning,
                    modifier = Modifier.size(LOCK_ICON_SIZE)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.rating_limit_open_profiles,
                        openProfiles.size,
                        openProfiles.joinToString(),
                        name
                    ),
                    style = NuvioTheme.textStyles.caption,
                    color = NuvioTheme.marquee.onImage2
                )
            }
        }
    }
}
