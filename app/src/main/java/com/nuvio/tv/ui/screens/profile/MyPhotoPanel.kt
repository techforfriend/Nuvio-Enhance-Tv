package com.nuvio.tv.ui.screens.profile

import android.text.format.DateFormat
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PhotoBackgroundImage
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

// A background photo's head thumbnail: 16:9 at the avatar head's height.
private val BACKGROUND_THUMB_WIDTH = 78.dp
private val BACKGROUND_THUMB_RADIUS = 6.dp

/** A photo's added date for the picker and panel, short and in the device locale ("30 Oct"). */
internal fun formatPhotoDate(addedAt: Instant, locale: Locale = Locale.getDefault()): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "dMMM")
    return java.text.SimpleDateFormat(pattern, locale).format(Date(addedAt.toEpochMilliseconds()))
}

/**
 * The hold OK / MENU panel on a My photo (sandbox u44 stage 7): the photo, when it was added and
 * which profiles use it ([usedBy]), then Use for [profileName] (same as OK) and Remove (red).
 */
@Composable
internal fun MyPhotoPanel(
    photo: MyPhoto,
    profileName: String,
    usedBy: List<String>,
    onUse: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    val firstRow = remember(photo.id) { FocusRequester() }
    val users = usedBy.joinToString()
    MarqueeSidePanel(
        onDismiss = onDismiss,
        initialFocus = firstRow,
        openedByHold = true,
        width = tokens.optionsPanelWidth
    ) {
        PanelEyebrow(text = stringResource(R.string.my_photo_panel_title))
        ProfilePanelHead(
            title = stringResource(R.string.my_photo_added, formatPhotoDate(photo.addedAt)),
            subtitle = if (usedBy.isEmpty()) {
                stringResource(R.string.my_photo_unused)
            } else {
                stringResource(R.string.my_photo_used_by, users)
            }
        ) {
            when (photo.kind) {
                MyPhotoKind.Avatar -> ProfileAvatarCircle(
                    name = profileName,
                    colorHex = "",
                    size = tokens.optionsAvatarSize,
                    art = AvatarArt.Photo(photo)
                )
                MyPhotoKind.Background -> PhotoBackgroundImage(
                    photo = photo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(BACKGROUND_THUMB_WIDTH, tokens.optionsAvatarSize)
                        .clip(RoundedCornerShape(BACKGROUND_THUMB_RADIUS))
                )
            }
        }
        PanelRule()
        PlayerPanelRow(
            title = if (profileName.isBlank()) {
                stringResource(R.string.my_photo_use_for_unnamed)
            } else {
                stringResource(R.string.my_photo_use_for, profileName.trim())
            },
            subtitle = stringResource(R.string.my_photo_use_sub),
            selected = false,
            trailingIcon = Icons.Filled.Check,
            focusRequester = firstRow,
            onClick = onUse
        )
        PlayerPanelRow(
            title = stringResource(R.string.my_photo_remove),
            subtitle = when {
                usedBy.isEmpty() -> stringResource(R.string.my_photo_remove_unused_sub)
                photo.kind == MyPhotoKind.Avatar -> stringResource(R.string.my_photo_remove_avatar_sub, users)
                else -> stringResource(R.string.my_photo_remove_background_sub, users)
            },
            selected = false,
            trailingIcon = Icons.Filled.Delete,
            destructive = true,
            onClick = onRemove
        )
    }
}
