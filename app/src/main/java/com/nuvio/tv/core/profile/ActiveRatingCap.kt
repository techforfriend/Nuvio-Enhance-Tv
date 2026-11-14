package com.nuvio.tv.core.profile

import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileRatingCapStore
import com.nuvio.tv.domain.model.ContentRatingCap
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The active profile's rating limit with the profile's name, for a screen that names it ([profileName] null if unreadable). */
data class ActiveRatingLimit(val cap: ContentRatingCap, val profileName: String?)

/**
 * The active profile's maximum age rating (Spec 64), read once per call: surfaces that list
 * titles read it when they load, and a profile switch rebuilds them (the picker clears the
 * main screens' ViewModels), so nothing here observes changes.
 */
@Singleton
class ActiveRatingCap @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val capStore: ProfileRatingCapStore
) {
    /**
     * The cap, or null when the active profile is uncapped. The id comes from the DataStore
     * itself, not ProfileManager's stateIn mirror: the picker opens Home as soon as its write
     * completes, before the mirror has caught up, so the mirror can still hold the previous
     * profile. A failed read fails closed to the strictest cap (D1).
     */
    suspend fun current(): ContentRatingCap? {
        val profileId = activeProfileIdOrNull() ?: return ContentRatingCap.STRICTEST
        return capStore.capFor(profileId).first()
    }

    /** [current] with the active profile's name (Detail's block copy, K2b); null when uncapped. */
    suspend fun currentLimit(): ActiveRatingLimit? {
        val profileId = activeProfileIdOrNull() ?: return ActiveRatingLimit(ContentRatingCap.STRICTEST, null)
        val cap = capStore.capFor(profileId).first() ?: return null
        val name = try {
            profileDataStore.profilesList.first().firstOrNull { it.id == profileId }?.name
        } catch (e: IOException) {
            null
        }
        return ActiveRatingLimit(cap, name)
    }

    private suspend fun activeProfileIdOrNull(): Int? = try {
        profileDataStore.activeProfileId.first()
    } catch (e: IOException) {
        null
    }
}
