package com.nuvio.tv.core.runtime

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.sync.shouldExcludePreferenceFromProfileSettingsSync
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** X15: exact-name sweep of cut-feature leftovers, stale-secret store parsing and key choice. */
class LegacyFeatureDataCleanupTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun File.write(relative: String, bytes: Int): File =
        File(this, relative).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun `deletes exact targets only, counts bytes freed, and is idempotent`() {
        val filesDir = tmpFolder.newFolder("files")
        val dataStoreDir = File(filesDir, "datastore").apply { mkdirs() }
        val sharedPrefsDir = tmpFolder.newFolder("shared_prefs")

        val targets = listOf(
            filesDir.write("cs_extensions/a.cs3", 100),
            filesDir.write("cs_extensions/nested/b.cs3", 50),
            filesDir.write("plugin_code/scraper.js", 30),
            filesDir.write("plugin_code_p2/scraper.js", 20),
            dataStoreDir.write("plugin_settings.preferences_pb", 7),
            dataStoreDir.write("plugin_settings.preferences_pb.bak", 7),
            dataStoreDir.write("plugin_settings_p3.preferences_pb", 5),
            sharedPrefsDir.write("cs3_plugin_preferences.xml", 11)
        )
        val survivors = listOf(
            filesDir.write("plugin_codex/keep.js", 1),
            filesDir.write("plugin_code_px/keep.js", 1),
            filesDir.write("cs_extensions_keep/keep", 1),
            filesDir.write("http_cache/journal", 1),
            filesDir.write("cw_enrichment/data.json", 1),
            dataStoreDir.write("plugin_settings_backup.preferences_pb", 1),
            dataStoreDir.write("plugin_settings_p3.preferences_pb.tmp", 1),
            dataStoreDir.write("debrid_settings.preferences_pb", 1),
            dataStoreDir.write("layout_settings_p2.preferences_pb", 1),
            sharedPrefsDir.write("cs3_plugin_preferences_old.xml", 1),
            sharedPrefsDir.write("app_locale.xml", 1)
        )

        val freed = deleteLegacyFeatureFiles(filesDir, dataStoreDir, sharedPrefsDir)

        assertEquals(230L, freed)
        targets.forEach { assertFalse("${it.path} should be deleted", it.exists()) }
        listOf("cs_extensions", "plugin_code", "plugin_code_p2").forEach {
            assertFalse("$it dir should be deleted", File(filesDir, it).exists())
        }
        survivors.forEach { assertTrue("${it.path} should survive", it.exists()) }

        assertEquals(0L, deleteLegacyFeatureFiles(filesDir, dataStoreDir, sharedPrefsDir))
    }

    @Test
    fun `deletes the pre-X7 api cache by exact name and keeps the others`() {
        val cacheDir = tmpFolder.newFolder("cache")
        cacheDir.write("http_cache/journal", 10)
        cacheDir.write("http_cache/abc.0", 30)
        val kept = listOf(
            cacheDir.write("http_cache_validated/journal", 1),
            cacheDir.write("http_cache_addon/journal", 1),
            cacheDir.write("image_cache/x", 1)
        )

        assertEquals(40L, deletePreX7HttpCache(cacheDir))
        assertFalse(File(cacheDir, "http_cache").exists())
        kept.forEach { assertTrue("${it.path} should survive", it.exists()) }
        assertEquals(0L, deletePreX7HttpCache(cacheDir))
    }

    @Test
    fun `missing directories free nothing`() {
        val root = tmpFolder.root
        assertEquals(
            0L,
            deleteLegacyFeatureFiles(File(root, "nope"), File(root, "nope/datastore"), File(root, "nope2"))
        )
    }

    @Test
    fun `secret stores parse to the profile id and feature the factory would use`() {
        assertEquals(
            LegacySecretStore(1, "debrid_settings", setOf("torbox_api_key", "premiumize_api_key", "real_debrid_api_key")),
            legacySecretStoreFor("debrid_settings.preferences_pb")
        )
        assertEquals(
            LegacySecretStore(4, "debrid_settings", setOf("torbox_api_key", "premiumize_api_key", "real_debrid_api_key")),
            legacySecretStoreFor("debrid_settings_p4.preferences_pb")
        )
        assertEquals(
            LegacySecretStore(1, "mdblist_settings", setOf("mdblist_api_key")),
            legacySecretStoreFor("mdblist_settings.preferences_pb")
        )
        assertEquals(
            LegacySecretStore(2, "mdblist_settings", setOf("mdblist_api_key")),
            legacySecretStoreFor("mdblist_settings_p2.preferences_pb")
        )
    }

    @Test
    fun `secret store parsing rejects anything but exact factory names`() {
        listOf(
            "debrid_settings.preferences_pb.bak",
            "debrid_settings_p1.preferences_pb",
            "debrid_settings_p03.preferences_pb",
            "debrid_settings_px.preferences_pb",
            "debrid_settings_backup.preferences_pb",
            "xdebrid_settings.preferences_pb",
            "mdblist_settings.preferences_pb.tmp",
            "mdblist_settings",
            "trakt_settings.preferences_pb",
            "tmdb_settings_p2.preferences_pb"
        ).forEach { assertNull(it, legacySecretStoreFor(it)) }
    }

    @Test
    fun `only the listed secret keys are chosen for removal`() {
        val keys = LEGACY_SECRET_KEYS.getValue("debrid_settings")
        val prefs = preferencesOf(
            stringPreferencesKey("torbox_api_key") to "a",
            stringPreferencesKey("real_debrid_api_key") to "b",
            booleanPreferencesKey("debrid_enabled") to true,
            stringPreferencesKey("torbox_api_key_hint") to "c"
        )

        assertEquals(
            setOf("torbox_api_key", "real_debrid_api_key"),
            legacySecretKeysToRemove(prefs, keys).mapTo(mutableSetOf()) { it.name }
        )
    }

    @Test
    fun `nothing is chosen when no secret key is present`() {
        val keys = LEGACY_SECRET_KEYS.getValue("mdblist_settings")

        assertTrue(legacySecretKeysToRemove(emptyPreferences(), keys).isEmpty())
        assertTrue(
            legacySecretKeysToRemove(
                preferencesOf(booleanPreferencesKey("mdblist_enabled") to true),
                keys
            ).isEmpty()
        )
    }

    @Test
    fun `shadow copy is deleted only when the edited store is now empty`() {
        val dataStoreDir = tmpFolder.newFolder("datastore")
        val emptied = dataStoreDir.write("debrid_settings.preferences_pb", 0)
        val emptiedShadow = dataStoreDir.write("debrid_settings.preferences_pb.bak", 40)
        val nonEmpty = dataStoreDir.write("mdblist_settings.preferences_pb", 12)
        val nonEmptyShadow = dataStoreDir.write("mdblist_settings.preferences_pb.bak", 40)

        // Emptied store: writeShadowCopy skips 0-byte files, so the stale shadow must go.
        assertTrue(deleteShadowCopyOfEmptiedStore(emptied))
        assertFalse(emptiedShadow.exists())
        // Non-empty store: the edit's async shadow write refreshes it; keep it.
        assertFalse(deleteShadowCopyOfEmptiedStore(nonEmpty))
        assertTrue(nonEmptyShadow.exists())
        // No shadow to delete.
        assertFalse(deleteShadowCopyOfEmptiedStore(emptied))
    }

    @Test
    fun `every removed secret key is excluded from profile settings sync`() {
        // Removing a synced key would change the settings signature and trigger a push.
        LEGACY_SECRET_KEYS.forEach { (feature, keys) ->
            keys.forEach { key ->
                assertTrue("$feature/$key", shouldExcludePreferenceFromProfileSettingsSync(feature, key))
            }
        }
    }
}
