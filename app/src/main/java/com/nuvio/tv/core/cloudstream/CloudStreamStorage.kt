package com.nuvio.tv.core.cloudstream

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * On-disk state for CloudStream on TV: the persisted registry (repositories + plugins)
 * in SharedPreferences, and downloaded .cs3 packages under filesDir.
 *
 * Ported from Enhanced-2's `CloudStreamPlatformStorage.android.kt`. Differences:
 * no per-profile scoping (the TV app keeps one CloudStream registry for all profiles;
 * the profile parameter is kept so per-profile state can be added without changing
 * callers), and `android.util.Log` instead of kermit.
 *
 * Names are deliberate: `LegacyFeatureDataCleanup` deletes `cs_extensions`,
 * `plugin_code*`, `plugin_settings*` and `cs3_plugin_preferences.xml` on every start —
 * none of the names below collide with that sweep.
 */
object CloudStreamStorage {
    private const val TAG = "CloudStreamStorage"
    private const val PREFERENCES_NAME = "nuvio_cloudstream"
    private const val STATE_KEY = "state"
    private const val HOME_PROVIDER_ID_KEY = "home_provider_id"
    private const val PACKAGES_DIR_NAME = "cloudstream/packages"

    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context?) {
        val androidContext = context ?: return
        appContext = androidContext.applicationContext
        preferences = androidContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        CloudStreamDexRuntime.initialize(androidContext)
    }

    private fun getContext(): Context {
        appContext?.let { return it }
        val fallback = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getMethod("currentApplication")
            method.invoke(null) as? Context
        }.getOrNull()
        if (fallback != null) {
            initialize(fallback)
            return fallback
        }
        error("CloudStream storage is not initialized")
    }

    private fun getPreferences(): SharedPreferences? {
        if (preferences == null) {
            runCatching { getContext() }
        }
        return preferences
    }

    fun loadState(profileId: Int = 1): String? =
        getPreferences()?.getString("${STATE_KEY}_$profileId", null)

    fun saveState(profileId: Int = 1, payload: String) {
        getPreferences()?.edit()?.putString("${STATE_KEY}_$profileId", payload)?.apply()
    }

    /** Home provider selector: the plugin id the Home CS3 section shows. Null = none chosen. */
    fun loadHomeProviderId(): String? =
        getPreferences()?.getString(HOME_PROVIDER_ID_KEY, null)

    fun saveHomeProviderId(pluginId: String?) {
        val editor = getPreferences()?.edit() ?: return
        if (pluginId == null) editor.remove(HOME_PROVIDER_ID_KEY) else editor.putString(HOME_PROVIDER_ID_KEY, pluginId)
        editor.apply()
    }

    fun savePackageAtomically(storageKey: String, bytes: ByteArray) {
        val directory = packagesDirectory()
        val destination = File(directory, "$storageKey.cs3")
        val temporary = File.createTempFile(storageKey, ".tmp", directory)
        val backup = File(directory, "$storageKey.backup")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (backup.exists()) {
                backup.setWritable(true)
                backup.delete()
            }
            if (destination.exists()) {
                destination.setWritable(true)
                if (!destination.renameTo(backup)) {
                    error("Could not prepare existing CloudStream package for update")
                }
            }
            if (!temporary.renameTo(destination)) {
                if (backup.exists()) {
                    backup.setWritable(true)
                    backup.renameTo(destination)
                }
                error("Could not commit CloudStream package")
            }
            if (backup.exists()) {
                backup.setWritable(true)
                backup.delete()
            }
        } finally {
            temporary.setWritable(true)
            temporary.delete()
        }
    }

    fun packageExists(storageKey: String): Boolean {
        val scoped = File(packagesDirectory(), "$storageKey.cs3")
        if (scoped.isFile) return true
        val legacy = legacyPackage(storageKey)
        return legacy.isFile && runCatching { legacy.copyTo(scoped, overwrite = false) }.getOrNull()?.isFile == true
    }

    fun migratePackage(oldStorageKey: String, newStorageKey: String): Boolean {
        val directory = packagesDirectory()
        val source = File(directory, "$oldStorageKey.cs3")
        val destination = File(directory, "$newStorageKey.cs3")
        if (destination.isFile) return true
        source.setWritable(true)
        if (source.isFile && source.renameTo(destination)) return true
        val legacy = legacyPackage(oldStorageKey)
        return legacy.isFile && runCatching { legacy.copyTo(destination, overwrite = false) }.getOrNull()?.isFile == true
    }

    fun packagePath(storageKey: String): String? {
        if (!packageExists(storageKey)) return null
        return File(packagesDirectory(), "$storageKey.cs3").absolutePath
    }

    fun deletePackage(storageKey: String) {
        val packageFile = File(packagesDirectory(), "$storageKey.cs3")
        val backupFile = File(packagesDirectory(), "$storageKey.backup")
        if (packageFile.exists()) {
            packageFile.setWritable(true)
            packageFile.delete()
        }
        if (backupFile.exists()) {
            backupFile.setWritable(true)
            backupFile.delete()
        }
    }

    fun clearPackages() {
        runCatching { packagesRootDirectory().deleteRecursively() }
            .onFailure { Log.w(TAG, "Could not clear CloudStream packages: ${it.message}") }
    }

    fun clearAllState() {
        getPreferences()?.edit()?.clear()?.apply()
    }

    private fun packagesDirectory(): File {
        return File(packagesRootDirectory(), "profile-1").apply { mkdirs() }
    }

    private fun legacyPackage(storageKey: String): File {
        return File(packagesRootDirectory(), "$storageKey.cs3")
    }

    private fun packagesRootDirectory(): File {
        val context = getContext()
        return File(context.filesDir, PACKAGES_DIR_NAME).apply { mkdirs() }
    }
}
