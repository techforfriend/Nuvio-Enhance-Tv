package com.nuvio.tv.core.auth

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.tls.AddonCertPinKeeper
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val ACCOUNT_RESET_TAG = "AccountDataReset"

@Singleton
class AccountLocalDataResetService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val profileDataStore: ProfileDataStore,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val myPhotosStore: MyPhotosStore,
    private val addonCertPinKeeper: AddonCertPinKeeper
) {
    // NonCancellable: the wipe is bounded local I/O and must finish once sign-out started,
    // or the previous account's shadow copies and CW cache stay on disk.
    suspend fun clearAfterSignOut() = withContext(NonCancellable + Dispatchers.IO) {
        bestEffort("Failed to clear profile-scoped data") { profileDataStoreFactory.clearProfileScopedData() }
        bestEffort("Failed to clear profile metadata") { profileDataStore.clearAll() }
        bestEffort("Failed to clear profile lock states") { profileLockStateDataStore.clearAll() }
        bestEffort("Failed to clear account files") { clearAccountFiles() }
        // Spec 44 D8: family photos do not carry over to the next account (the store drops its list too).
        bestEffort("Failed to clear My photos") { myPhotosStore.deleteAll() }
        // Spec 68 X7: trusted addon certificates go with the account's addons (pins are device-local, D8).
        bestEffort("Failed to clear trusted addon certificates") { addonCertPinKeeper.clear() }
    }

    /** Each step runs even if an earlier one failed; cancellation is rethrown (KT-CONC-03). */
    private inline fun bestEffort(failureMessage: String, step: () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ACCOUNT_RESET_TAG, failureMessage, e)
        }
    }

    private fun clearAccountFiles() {
        val files = context.filesDir.listFiles() ?: return
        files.forEach { file ->
            if (isAccountFile(file)) {
                file.deleteRecursively()
            }
        }
    }

    // Leftovers of cut features (plugin_code*, cs_extensions) are swept by
    // LegacyFeatureDataCleanup at app start, not here.
    private fun isAccountFile(file: File): Boolean =
        file.name == ContinueWatchingEnrichmentCache.CACHE_DIR_NAME
}
