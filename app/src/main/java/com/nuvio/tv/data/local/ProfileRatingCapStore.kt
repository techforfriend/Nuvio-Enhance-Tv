package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.model.ContentRatingCap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each profile's maximum age rating (Spec 64 D2: device-local, keyed by profile id, never in the
 * sync payload): its own file through [ProfileDataStoreFactory] (`profile_rating_cap`, `_p<id>`
 * past profile 1), so profile deletion and sign-out sweep it with the other profile files.
 *
 * Reads fail closed (D1): a stored value this build does not know, or a file that cannot be read,
 * reads as the strictest cap, never as no cap; after an IOException the flow emits that once and
 * completes. Known gap: a corrupt file is handled by the factory's corruption handler, which
 * restores the `.bak` shadow copy (rewritten after every edit) or else resets to empty, which
 * reads as uncapped. Writes report failure instead of throwing.
 */
@Singleton
class ProfileRatingCapStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    /** Profile [profileId]'s cap; null when the profile is uncapped. */
    fun capFor(profileId: Int): Flow<ContentRatingCap?> =
        factory.get(profileId, FEATURE).data
            .map(::decode)
            .catch { e -> if (e is IOException) emit(ContentRatingCap.STRICTEST) else throw e }
            .distinctUntilChanged()

    /** Sets profile [profileId]'s cap ([cap] null removes it); false when the file cannot be written. */
    suspend fun write(profileId: Int, cap: ContentRatingCap?): Boolean = try {
        factory.get(profileId, FEATURE).edit { prefs ->
            if (cap == null) prefs.remove(CAP) else prefs[CAP] = cap.storageKey
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Rating cap not saved: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "ProfileRatingCapStore"
        const val FEATURE = "profile_rating_cap"

        private val CAP = stringPreferencesKey("cap")

        /** No key is no cap; a present key this build cannot decode is the strictest cap. */
        internal fun decode(prefs: Preferences): ContentRatingCap? {
            val raw = prefs[CAP] ?: return null
            return ContentRatingCap.fromStorageKey(raw) ?: ContentRatingCap.STRICTEST
        }
    }
}
