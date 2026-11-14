package com.nuvio.tv.ui.screens.profile

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.isRatingAllowed

/** A changed rating cap to save ([cap] null removes the limit); an absent edit leaves the stored one. */
data class RatingCapEdit(val cap: ContentRatingCap?)

/** The levels the Rating limit panel offers after No limit (Spec 64 D7: D4 ranks 1-3). */
internal val RatingLimitLevels: List<ContentRatingCap> =
    listOf(ContentRatingCap.ALL_AGES, ContentRatingCap.OLDER_KIDS, ContentRatingCap.TEENS)

// The US ratings as the panel's chips show them, movie first within a tier. TV-Y7-FV is TV-Y7's
// fantasy-violence variant and is not shown on its own.
private val CHIP_ORDER = listOf("G", "TV-Y", "TV-G", "PG", "TV-PG", "TV-Y7", "PG-13", "TV-14", "R", "TV-MA", "NC-17")
private val MOVIE_LADDER = listOf("G", "PG", "PG-13", "R", "NC-17")
private val TV_LADDER = listOf("TV-G", "TV-PG", "TV-14", "TV-MA")

/** The ratings this tier adds over the one below (the panel's chips, sandbox stage 2). */
internal fun ContentRatingCap.chipRatings(): List<String> = CHIP_ORDER.filter { ContentRatingCap.tierOf(it) == this }

/** The highest movie and TV rating this cap allows ("Up to PG and TV-PG"). */
internal fun ContentRatingCap.ceiling(): Pair<String, String> =
    MOVIE_LADDER.last { isRatingAllowed(it, this) } to TV_LADDER.last { isRatingAllowed(it, this) }

@StringRes
internal fun ContentRatingCap.labelRes(): Int = when (this) {
    ContentRatingCap.ALL_AGES -> R.string.rating_limit_all_ages
    ContentRatingCap.OLDER_KIDS -> R.string.rating_limit_older_kids
    ContentRatingCap.TEENS -> R.string.rating_limit_teens
    ContentRatingCap.MATURE -> R.string.rating_limit_mature
    ContentRatingCap.ADULTS_ONLY -> R.string.rating_limit_adults_only
}

/** How the editor shows a profile's Rating limit (Spec 64 D9). */
internal enum class RatingLimitAccess {
    /** The row and its panel (Manage profiles, primary active). */
    EDIT,
    /** A read-only note (any editor outside primary-managed Manage profiles). */
    NOTE,
    /** Nothing: the primary profile is never limited. */
    NONE
}

/** D9: editable exactly where Live TV is ([liveTvEditable]); never for the primary. */
internal fun ratingLimitAccess(liveTvEditable: Boolean, isPrimary: Boolean): RatingLimitAccess = when {
    isPrimary -> RatingLimitAccess.NONE
    liveTvEditable -> RatingLimitAccess.EDIT
    else -> RatingLimitAccess.NOTE
}

/**
 * The profiles a limited profile could still switch to (D10, the panel's lock line): every other
 * profile with no PIN and no limit, the primary included. [editedId] is the profile being edited
 * (null on create). A profile whose PIN or cap has not loaded counts as open: the line warns
 * rather than stays silent.
 */
internal fun noPinUnlimitedProfiles(
    editedId: Int?,
    profiles: List<UserProfile>,
    pinEnabled: Map<Int, Boolean>,
    caps: Map<Int, ContentRatingCap?>?
): List<UserProfile> = profiles.filter { it.id != editedId && pinEnabled[it.id] != true && caps?.get(it.id) == null }

/**
 * Whether [profile] can be deleted: never the primary; unless [managedByPrimary] never a limited
 * profile, nor one whose cap has not loaded (D10: deleting the last limited profile would bring
 * Add back, and a child knows their own PIN). [managedByPrimary] is [liveTvEditable] (Manage
 * profiles with the primary active), not the screen mode: the Settings grant (Spec 6 T5) lets a
 * non-primary profile into Manage profiles too.
 */
internal fun profileDeletable(profile: UserProfile, managedByPrimary: Boolean, caps: Map<Int, ContentRatingCap?>?): Boolean =
    !profile.isPrimary && (managedByPrimary || (ratingCapLoaded(profile.id, caps) && caps?.get(profile.id) == null))

/**
 * The cap an editor save writes: with [RatingLimitAccess.EDIT] always the draft's [cap] (stored
 * then matches what was shown, even when the opened value was a read-failure fallback); else none.
 */
internal fun ratingCapEditFor(access: RatingLimitAccess, cap: ContentRatingCap?): RatingCapEdit? =
    if (access == RatingLimitAccess.EDIT) RatingCapEdit(cap) else null

/** The panel row focused on open: [current], or for a stored level the panel does not offer (D4
 *  ranks 4-5) the loosest offered one, so a stray OK tightens it rather than removing it. */
internal fun ratingLimitInitialFocus(current: ContentRatingCap?): ContentRatingCap? =
    if (current == null || current in RatingLimitLevels) current else RatingLimitLevels.last()

/** Whether [caps] holds a loaded value for profile [profileId] (null values are loaded "no limit"). */
internal fun ratingCapLoaded(profileId: Int, caps: Map<Int, ContentRatingCap?>?): Boolean = caps?.containsKey(profileId) == true

/**
 * D10: Add profile, unless [managedByPrimary] (as for [profileDeletable]), only while no profile on
 * this TV has a limit (a new profile starts unlimited). Fails closed until every listed profile's
 * cap has loaded.
 */
internal fun showAddProfile(
    canAddProfile: Boolean,
    managedByPrimary: Boolean,
    profiles: List<UserProfile>,
    caps: Map<Int, ContentRatingCap?>?
): Boolean = canAddProfile &&
    (managedByPrimary || profiles.all { ratingCapLoaded(it.id, caps) && caps?.get(it.id) == null })
