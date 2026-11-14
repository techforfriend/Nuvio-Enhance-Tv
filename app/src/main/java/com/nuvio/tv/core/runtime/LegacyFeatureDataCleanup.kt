package com.nuvio.tv.core.runtime

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "LegacyFeatureCleanup"

// Single home for the on-disk names of cut features (X15). Exact matches only.
private const val CS_EXTENSIONS_DIR = "cs_extensions"
private val PLUGIN_CODE_DIR_REGEX = Regex("""^plugin_code(_p\d+)?$""")
private val PLUGIN_SETTINGS_STORE_REGEX = Regex("""^plugin_settings(_p\d+)?\.preferences_pb(\.bak)?$""")
private const val CS3_PLUGIN_PREFS_FILE = "cs3_plugin_preferences.xml"

// Spec 68 X7: the @Api client's cache from the trust-all era (entries fetched unvalidated); the
// client now caches under ADDON_HTTP_CACHE_DIR. Exact name: http_cache_validated must survive.
private const val PRE_X7_HTTP_CACHE_DIR = "http_cache"

// Stale secrets of cut services, removed key-by-key; the stores themselves stay
// (they are profile-settings blob passthroughs). Every key here must be excluded by
// ProfileSettingsSyncService.shouldExcludePreferenceFromProfileSettingsSync so the
// removal never changes the synced blob, its signature, or triggers a push (pinned by
// LegacyFeatureDataCleanupTest).
internal val LEGACY_SECRET_KEYS: Map<String, Set<String>> = mapOf(
    "debrid_settings" to setOf("torbox_api_key", "premiumize_api_key", "real_debrid_api_key"),
    "mdblist_settings" to setOf("mdblist_api_key")
)
private val LEGACY_SECRET_STORE_REGEX =
    Regex("""^(debrid_settings|mdblist_settings)(?:_p(\d+))?\.preferences_pb$""")

/** A profile-scoped store holding stale secrets, addressed the way ProfileDataStoreFactory.get is. */
internal data class LegacySecretStore(
    val profileId: Int,
    val feature: String,
    val keys: Set<String>
)

/**
 * Maps a datastore file name to the store whose stale secrets should be removed, or null.
 * Only names ProfileDataStoreFactory itself produces qualify (profile 1 has no suffix), so
 * `_p1`, zero-padded ids, `.bak` shadows and look-alike names are ignored.
 */
internal fun legacySecretStoreFor(dataStoreFileName: String): LegacySecretStore? {
    val match = LEGACY_SECRET_STORE_REGEX.matchEntire(dataStoreFileName) ?: return null
    val feature = match.groupValues[1]
    val suffix = match.groupValues[2]
    val profileId = if (suffix.isEmpty()) 1 else suffix.toIntOrNull() ?: return null
    val canonicalName = if (profileId == 1) feature else "${feature}_p$profileId"
    if ("$canonicalName.preferences_pb" != dataStoreFileName) return null
    return LegacySecretStore(profileId, feature, LEGACY_SECRET_KEYS.getValue(feature))
}

/** The stale secrets present in [prefs]: only names in [secretKeys]; empty when none is present. */
internal fun legacySecretKeysToRemove(prefs: Preferences, secretKeys: Set<String>): List<Preferences.Key<*>> =
    prefs.asMap().keys.filter { it.name in secretKeys }

/**
 * Deletes [storeFile]'s `.bak` shadow copy when the store is now 0 bytes (an emptied store),
 * the one case ProfileDataStoreFactory.writeShadowCopy skips, so the shadow would otherwise
 * keep the removed keys. A non-empty store keeps its shadow: the edit's async shadow write
 * overwrites it with the post-edit bytes. Returns whether the shadow was deleted.
 */
internal fun deleteShadowCopyOfEmptiedStore(storeFile: File): Boolean {
    if (storeFile.length() > 0L) return false
    val shadow = File(storeFile.parentFile, "${storeFile.name}.bak")
    if (!shadow.exists()) return false
    if (!shadow.delete()) {
        Log.w(TAG, "Could not delete ${shadow.name}")
        return false
    }
    return true
}

/**
 * Deletes the cut plugin runtime's on-disk artifacts (exact names only) and returns the
 * bytes freed. Idempotent by existence: a second run finds nothing and frees 0. A failure
 * on one path is logged and the sweep continues.
 */
internal fun deleteLegacyFeatureFiles(filesDir: File, dataStoreDir: File, sharedPrefsDir: File): Long {
    val targets = buildList {
        add(File(filesDir, CS_EXTENSIONS_DIR))
        filesDir.listFiles()?.filterTo(this) { PLUGIN_CODE_DIR_REGEX.matches(it.name) }
        dataStoreDir.listFiles()?.filterTo(this) { PLUGIN_SETTINGS_STORE_REGEX.matches(it.name) }
        add(File(sharedPrefsDir, CS3_PLUGIN_PREFS_FILE))
    }
    return deleteAll(targets)
}

/** Deletes the pre-X7 `@Api` HTTP cache (exact name) and returns the bytes freed; 0 once it is gone. */
internal fun deletePreX7HttpCache(cacheDir: File): Long = deleteAll(listOf(File(cacheDir, PRE_X7_HTTP_CACHE_DIR)))

private fun deleteAll(targets: List<File>): Long {
    var freed = 0L
    for (target in targets) {
        if (!target.exists()) continue
        try {
            target.walkBottomUp().forEach { entry ->
                val length = if (entry.isFile) entry.length() else 0L
                if (entry.delete()) {
                    freed += length
                } else {
                    Log.w(TAG, "Could not delete ${entry.path}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed for ${target.path}: ${e.message}")
        }
    }
    return freed
}

/**
 * X15: removes leftovers of cut features (plugin runtime files, stale debrid/MDBList API
 * keys) and the pre-X7 `@Api` HTTP cache (Spec 68). Run once per process, delayed and on IO,
 * from NuvioApplication.
 */
@Singleton
class LegacyFeatureDataCleanup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profileDataStoreFactory: ProfileDataStoreFactory
) {
    /** Never throws (bar cancellation): it runs in an app-level scope with no handler. */
    suspend fun run() = withContext(Dispatchers.IO) {
        try {
            val dataStoreDir = File(context.filesDir, "datastore")
            val bytesFreed = deleteLegacyFeatureFiles(
                filesDir = context.filesDir,
                dataStoreDir = dataStoreDir,
                sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            )
            val httpCacheFreed = deletePreX7HttpCache(context.cacheDir)
            val keysRemoved = removeStaleSecrets(dataStoreDir)
            Log.i(
                TAG,
                "Legacy feature cleanup: bytesFreed=$bytesFreed httpCacheFreed=$httpCacheFreed keysRemoved=$keysRemoved"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Legacy feature cleanup failed: ${e.message}")
        }
    }

    // Edits through the factory (not the file) so the cached store instance stays
    // consistent. Writes only when a key is present. The edit's async shadow write
    // refreshes the .bak of a store that is still non-empty; an emptied store (0 bytes,
    // which writeShadowCopy skips) would keep a .bak with the keys, so that one is deleted.
    private suspend fun removeStaleSecrets(dataStoreDir: File): Int {
        var removed = 0
        dataStoreDir.listFiles()?.forEach { file ->
            val target = legacySecretStoreFor(file.name) ?: return@forEach
            try {
                val store = profileDataStoreFactory.get(target.profileId, target.feature)
                val present = legacySecretKeysToRemove(store.data.first(), target.keys)
                if (present.isNotEmpty()) {
                    store.edit { prefs -> present.forEach { key -> prefs -= key } }
                    removed += present.size
                    deleteShadowCopyOfEmptiedStore(file)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Secret cleanup failed for ${file.name}: ${e.message}")
            }
        }
        return removed
    }
}
