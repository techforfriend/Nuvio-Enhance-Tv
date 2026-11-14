package com.nuvio.tv.ui.screens.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.LocalLookSets
import com.nuvio.tv.domain.model.LookAvatar
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.activeAvatar
import com.nuvio.tv.domain.model.activeBackground
import com.nuvio.tv.domain.model.normalizedLookField
import com.nuvio.tv.domain.model.syncedAvatarImageUrl
import com.nuvio.tv.domain.model.syncedLookKey
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.AvatarTile
import com.nuvio.tv.ui.components.LocalLookLibrary
import com.nuvio.tv.ui.components.ProfileBackgroundPick
import com.nuvio.tv.ui.components.toArt

/** The editor's effective avatar pick (Spec 44 Design "Editor draft"). */
internal sealed interface EditorAvatarPick {
    /** The profile's synced avatar as opened (a catalog id or a website URL). */
    data object Synced : EditorAvatarPick
    data class Server(val id: String) : EditorAvatarPick
    /** A TV-only pick: a bundled creature or a My photo. */
    data class Local(val pick: ProfileLookPick) : EditorAvatarPick
    data object None : EditorAvatarPick
}

/**
 * The profile editor's state (u10 stages 7, 8): a new profile ([original] null) or an edit of
 * [original]. Snapshot state, so the editor recomposes on every change; the rules are plain code.
 *
 * - Name: at most [NAME_MAX] characters (a longer value is ignored, as built); [canSave] needs a
 *   non-blank name.
 * - Avatar (Spec 44): one effective pick ([avatarPick]). A server avatar picked again clears it and
 *   restores the starting colour (the original's, or [DEFAULT_COLOR] on create); another server
 *   avatar takes its colour when it has one, except the opened synced one, which is [EditorAvatarPick.Synced]
 *   and restores the starting colour. A TV-only pick ([localAvatar]) keeps the synced avatar fields as
 *   opened and restores the starting colour; picking it again returns to Synced (none on create).
 * - Preview: the focused tile while one is focused, else the selection.
 * - Background (edit only): one effective pick ([backgroundPick]): standard, a catalog item
 *   (toggles), the profile's own custom URL (toggles) or a TV-only background ([localBackground]:
 *   bundled or a My photo). A TV-only pick keeps the synced background fields as opened; picking it
 *   again returns to the synced background.
 * - Live TV (Spec 34 D17): the profile's switch, [originalLiveTvEnabled] at open (on for a new
 *   profile); saved only when [liveTvChanged].
 * - Rating limit (Spec 64 K2): the profile's cap, [originalRatingCap] at open (none for a new
 *   profile; null = no limit); [ratingCapChanged] counts toward [hasChanges] (the save itself
 *   writes it wherever it is editable, [ratingCapEditFor]).
 */
internal class ProfileEditorDraft private constructor(
    val original: UserProfile?,
    val originalLiveTvEnabled: Boolean,
    val originalRatingCap: ContentRatingCap?,
    private val openedLocalAvatar: ProfileLookPick?,
    private val openedLocalBackground: ProfileLookPick?
) {

    val isCreate: Boolean get() = original == null

    private val startColor: String get() = original?.avatarColorHex ?: DEFAULT_COLOR

    var name by mutableStateOf(original?.name.orEmpty())
        private set
    var colorHex by mutableStateOf(startColor)
        private set
    /** The synced avatar id to save (the opened one while a TV-only pick is selected). */
    var avatarId by mutableStateOf(original?.avatarId)
        private set
    /** The TV-only avatar pick, written to the look entry on save (Spec 44). */
    var localAvatar by mutableStateOf(openedLocalAvatar)
        private set
    var backgroundId by mutableStateOf(original?.profileBackgroundId)
        private set
    var backgroundUrl by mutableStateOf(customBackgroundUrl)
        private set
    /** The TV-only background, written to the look entry on save (Spec 44). */
    var localBackground by mutableStateOf(openedLocalBackground)
        private set

    var liveTvEnabled by mutableStateOf(originalLiveTvEnabled)
        private set

    val liveTvChanged: Boolean get() = liveTvEnabled != originalLiveTvEnabled

    var ratingCap by mutableStateOf(originalRatingCap)
        private set

    val ratingCapChanged: Boolean get() = ratingCap != originalRatingCap

    /** The tile the grid's focus is on, or null (focus elsewhere). */
    var focusedTile by mutableStateOf<AvatarTile?>(null)
        private set

    /** The profile's own custom background (set on the website), if any. */
    val customBackgroundUrl: String? get() = original?.profileBackgroundUrl?.takeIf { it.isNotBlank() }

    val canSave: Boolean get() = name.isNotBlank()

    private val avatarChanged: Boolean get() = avatarId != original?.avatarId

    val avatarPick: EditorAvatarPick
        get() {
            localAvatar?.let { return EditorAvatarPick.Local(it) }
            val id = avatarId
            return when {
                original != null && !avatarChanged && syncedLookKey(original).avatar != null -> EditorAvatarPick.Synced
                id != null -> EditorAvatarPick.Server(id)
                else -> EditorAvatarPick.None
            }
        }

    /** The grid tile drawn as selected: the TV-only pick, else the server avatar id. */
    val selectedTileKey: String?
        get() = when (val local = localAvatar) {
            is ProfileLookPick.Bundled -> AvatarTile.creatureKey(local.slug)
            is ProfileLookPick.Photo -> AvatarTile.photoKey(local.id)
            null -> avatarId?.let(AvatarTile::serverKey)
        }

    /**
     * Anything to lose on Back: on create a typed name or a picked avatar; on edit any field changed
     * (a stored blank background URL counts as none, as the draft starts it); a TV-only pick
     * changed; the Live TV switch; the rating limit.
     */
    val hasChanges: Boolean
        get() = liveTvChanged ||
            ratingCapChanged ||
            localAvatar != openedLocalAvatar ||
            localBackground != openedLocalBackground ||
            if (original == null) {
                name.isNotEmpty() || avatarId != null
            } else {
                toProfile() != original.copy(profileBackgroundUrl = customBackgroundUrl)
            }

    fun updateName(value: String) {
        if (value.length <= NAME_MAX) name = value
    }

    fun toggleTile(tile: AvatarTile) {
        when (tile) {
            is AvatarTile.Server -> toggleAvatar(tile.item)
            is AvatarTile.Creature -> toggleLocalAvatar(ProfileLookPick.Bundled(tile.creature.slug))
            is AvatarTile.Photo -> toggleLocalAvatar(ProfileLookPick.Photo(tile.photo.id))
        }
    }

    /** The hold panel's "Use for" (stage 7): selects [pick], never toggles it off. */
    fun useLocalAvatar(pick: ProfileLookPick) {
        if (localAvatar != pick) toggleLocalAvatar(pick)
    }

    /** As [useLocalAvatar], for a background. */
    fun useLocalBackground(pick: ProfileLookPick) {
        if (localBackground != pick) toggleLocalBackground(pick)
    }

    fun toggleAvatar(avatar: AvatarCatalogItem) {
        val fromLocal = localAvatar != null
        localAvatar = null
        if (!fromLocal && avatarId == avatar.id) {
            avatarId = null
            colorHex = startColor
            return
        }
        avatarId = avatar.id
        if (original != null && !avatarChanged) {
            // The opened synced avatar again: Synced keeps the opened colour.
            colorHex = startColor
        } else {
            avatar.bgColor?.let { colorHex = it }
        }
    }

    /** Picks (or, picked again, drops back to Synced) a TV-only avatar; the synced fields stay as opened. */
    fun toggleLocalAvatar(pick: ProfileLookPick) {
        localAvatar = if (localAvatar == pick) null else pick
        restoreSyncedAvatar()
    }

    /** A My photo was removed (D5): a draft pick of it falls back to the synced avatar / background. */
    fun onPhotoRemoved(id: String) {
        val photo = ProfileLookPick.Photo(id)
        if (localAvatar == photo) {
            localAvatar = null
            restoreSyncedAvatar()
        }
        if (localBackground == photo) {
            localBackground = null
            restoreSyncedBackground()
        }
    }

    /** The synced avatar fields and colour as opened (under, or after, a TV-only avatar). */
    private fun restoreSyncedAvatar() {
        avatarId = original?.avatarId
        colorHex = startColor
    }

    /** The synced background fields as opened (under, or after, a TV-only background). */
    private fun restoreSyncedBackground() {
        backgroundId = original?.profileBackgroundId
        backgroundUrl = customBackgroundUrl
    }

    fun updateLiveTv(enabled: Boolean) {
        liveTvEnabled = enabled
    }

    fun updateRatingCap(cap: ContentRatingCap?) {
        ratingCap = cap
    }

    fun focusTile(tile: AvatarTile?) {
        focusedTile = tile
    }

    /** Preview colour: the focused server avatar's, else the draft's (a creature brings its own disc). */
    val previewColorHex: String get() = (focusedTile as? AvatarTile.Server)?.item?.bgColor ?: colorHex

    /** Preview art: the focused tile's, else [selectedArt]. */
    fun previewArt(
        catalog: List<AvatarCatalogItem>,
        library: LocalLookLibrary = LocalLookLibrary.Bundled,
        catalogImageUrl: (String) -> String?
    ): AvatarArt? = focusedTile?.art ?: selectedArt(catalog, library, catalogImageUrl)

    /**
     * The selection's art: the TV-only pick; else the selected catalog avatar; else, while the
     * avatar is unchanged, the original's synced image ([catalogImageUrl] resolves a catalog id the
     * catalog list lacks); else none (the initial on the colour).
     */
    fun selectedArt(
        catalog: List<AvatarCatalogItem>,
        library: LocalLookLibrary = LocalLookLibrary.Bundled,
        catalogImageUrl: (String) -> String?
    ): AvatarArt? {
        localAvatar?.let { return LookAvatar.Local(it).toArt(library) }
        catalog.firstOrNull { it.id == avatarId }?.let { return AvatarArt.Image(it.imageUrl) }
        if (original == null || avatarChanged) return null
        return syncedAvatarImageUrl(original, catalogImageUrl)?.let(AvatarArt::Image)
    }

    /**
     * The background tile drawn as selected (and the toggles' "already selected" test): the TV-only
     * pick, else the custom URL, else the catalog id (a stored blank id is none), else Normal; the
     * URL-over-id order is the resolver's (`resolveProfileBackgroundSelection`).
     */
    val backgroundPick: ProfileBackgroundPick
        get() {
            localBackground?.let { return ProfileBackgroundPick.Local(it) }
            val id = backgroundId.normalizedLookField()
            return when {
                backgroundUrl != null -> ProfileBackgroundPick.Custom
                id != null -> ProfileBackgroundPick.Catalog(id)
                else -> ProfileBackgroundPick.Standard
            }
        }

    /** The Background tab's focused tile, or null (focus elsewhere). */
    var focusedBackground by mutableStateOf<ProfileBackgroundPick?>(null)
        private set

    fun focusBackground(pick: ProfileBackgroundPick?) {
        focusedBackground = pick
    }

    /**
     * What the editor field shows on the Background tab (sandbox u44 stage 6): the focused tile, else
     * the selection; null = Normal (the colour field).
     */
    val previewBackground: ProfileBackgroundSelection?
        get() = when (val pick = focusedBackground ?: backgroundPick) {
            ProfileBackgroundPick.Standard -> null
            is ProfileBackgroundPick.Catalog -> ProfileBackgroundSelection.Catalog(pick.id)
            ProfileBackgroundPick.Custom -> customBackgroundUrl?.let(ProfileBackgroundSelection::Custom)
            is ProfileBackgroundPick.Local -> ProfileBackgroundSelection.Local(pick.pick)
        }

    /** Picks (or, picked again, drops back to the synced background) a TV-only background. */
    fun toggleLocalBackground(pick: ProfileLookPick) {
        localBackground = if (localBackground == pick) null else pick
        restoreSyncedBackground()
    }

    fun selectStandardBackground() {
        localBackground = null
        backgroundId = null
        backgroundUrl = null
    }

    /** Selects catalog [id], or (the selected tile pressed again) Normal. */
    fun toggleCatalogBackground(id: String) {
        val selected = backgroundPick == ProfileBackgroundPick.Catalog(id)
        localBackground = null
        backgroundId = if (selected) null else id
        backgroundUrl = null
    }

    /** Selects the custom URL, or (the selected tile pressed again) Normal; without one, Normal. */
    fun toggleCustomBackground() {
        val selected = backgroundPick == ProfileBackgroundPick.Custom
        localBackground = null
        backgroundId = null
        backgroundUrl = if (selected) null else customBackgroundUrl
    }

    /**
     * The edited profile (edit only; create passes [name] / [colorHex] / [avatarId]). A changed avatar
     * drops the custom avatarUrl; a TV-only pick keeps the opened avatar and background fields.
     */
    fun toProfile(): UserProfile {
        val profile = checkNotNull(original) { "toProfile() is for edits" }
        return profile.copy(
            name = name,
            avatarColorHex = colorHex,
            avatarId = avatarId,
            avatarUrl = if (avatarChanged) null else profile.avatarUrl,
            profileBackgroundId = backgroundId,
            profileBackgroundUrl = backgroundUrl
        )
    }

    companion object {
        const val NAME_MAX = 20
        const val DEFAULT_COLOR = "#1E88E5"

        fun create(): ProfileEditorDraft = ProfileEditorDraft(null, true, null, null, null)

        /** An edit of [profile]; its look entry's picks open selected only while they apply ([sets]). */
        fun edit(
            profile: UserProfile,
            liveTvEnabled: Boolean,
            // No default: an edit opened on the wrong cap would show it, and a save would write it.
            ratingCap: ContentRatingCap?,
            look: ProfileLookEntry?,
            // No default: without the current My photos a photo pick would open as Synced and the save would clear it.
            sets: LocalLookSets
        ): ProfileEditorDraft = ProfileEditorDraft(
            profile, liveTvEnabled, ratingCap, look.activeAvatar(profile, sets), look.activeBackground(profile, sets)
        )
    }
}
