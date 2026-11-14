package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.domain.model.UserProfile

/** Spec 43: the most picker items (profiles + Add) drawn at full size; six use the compact avatar and gap. */
private const val PROFILE_ROW_FULL_SIZE_MAX = 5

/** Whether a picker row of [itemCount] items (profiles + Add profile) uses the compact sizes (stage six). */
internal fun profileRowIsCompact(itemCount: Int): Boolean = itemCount > PROFILE_ROW_FULL_SIZE_MAX

/** One row of the profile options panel (u10 stage 6), in panel order. */
internal enum class ProfileOption { EDIT, SET_PIN, CHANGE_PIN, REMOVE_PIN, DELETE }

/** Edit, then Set PIN or Change + Remove PIN, then Delete when [canDelete] ([profileDeletable]). */
internal fun profileOptionRows(canDelete: Boolean, hasPin: Boolean): List<ProfileOption> = buildList {
    add(ProfileOption.EDIT)
    if (hasPin) {
        add(ProfileOption.CHANGE_PIN)
        add(ProfileOption.REMOVE_PIN)
    } else {
        add(ProfileOption.SET_PIN)
    }
    if (canDelete) add(ProfileOption.DELETE)
}

/**
 * U12: the primary profile's name when [profile] uses the primary's addons ("Shares Arshad's
 * addons" on its card and options head), else null. The primary never shares with itself.
 */
internal fun sharesAddonsOf(profile: UserProfile, profiles: List<UserProfile>): String? =
    if (profile.usesPrimaryAddons && !profile.isPrimary) profiles.firstOrNull { it.isPrimary }?.name else null

/** Q4: on the startup picker a PIN-locked profile asks for its PIN before the options open; Settings › Profiles does not. */
internal fun optionsNeedPin(isManagementMode: Boolean, hasPin: Boolean): Boolean = !isManagementMode && hasPin

/**
 * Whether the editor offers the Live TV switch (Spec 34 D17 Q3): only in Manage profiles with the
 * primary active. The startup picker's editor is open to whoever holds the remote; an active id not
 * loaded yet ([storedActiveProfileId] null) fails closed.
 */
internal fun liveTvEditable(isManagementMode: Boolean, storedActiveProfileId: Int?): Boolean =
    isManagementMode && storedActiveProfileId == 1

/** Where an options row leads. */
internal sealed interface ProfileOptionTarget {
    data object Edit : ProfileOptionTarget
    data class Pin(val state: ProfilePinOverlayState) : ProfileOptionTarget
    data object ConfirmDelete : ProfileOptionTarget
}

/**
 * Routes [option] for [profile]. [verifiedPin] is the PIN typed to open the panel (startup picker,
 * Q4): Change PIN then skips its own ask; Remove PIN and Delete keep theirs (as built).
 */
internal fun profileOptionTarget(
    option: ProfileOption,
    profile: UserProfile,
    hasPin: Boolean,
    verifiedPin: String?
): ProfileOptionTarget = when (option) {
    ProfileOption.EDIT -> ProfileOptionTarget.Edit
    ProfileOption.SET_PIN -> ProfileOptionTarget.Pin(ProfilePinOverlayState.Set(profile))
    ProfileOption.CHANGE_PIN -> ProfileOptionTarget.Pin(
        if (verifiedPin != null) {
            ProfilePinOverlayState.Set(profile, currentPin = verifiedPin)
        } else {
            ProfilePinOverlayState.VerifyCurrentForChange(profile)
        }
    )
    ProfileOption.REMOVE_PIN -> ProfileOptionTarget.Pin(ProfilePinOverlayState.VerifyCurrentForRemove(profile))
    ProfileOption.DELETE -> if (hasPin) {
        ProfileOptionTarget.Pin(ProfilePinOverlayState.VerifyCurrentForDelete(profile))
    } else {
        ProfileOptionTarget.ConfirmDelete
    }
}
