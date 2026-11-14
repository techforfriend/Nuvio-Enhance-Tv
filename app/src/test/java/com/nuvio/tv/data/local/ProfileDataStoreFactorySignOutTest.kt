package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.tmdb.TMDB_API_KEY_STORE_NAME
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sign-out sweep (Spec 33 D1, decision Q1): the device-wide player store survives, the profile
 * player files (which hold the tokenised last_playback_diagnostics_json) are deleted.
 */
class ProfileDataStoreFactorySignOutTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `sign-out keeps the device player store and deletes profile player, look and cap files`() = runTest {
        val filesDir = tmpFolder.newFolder("files")
        val dataStoreDir = File(filesDir, "datastore").apply { mkdirs() }
        fun file(name: String) = File(dataStoreDir, name).apply { writeText("x") }
        val device = file("$DEVICE_PLAYER_SETTINGS_STORE_NAME.preferences_pb")
        val deviceBak = file("$DEVICE_PLAYER_SETTINGS_STORE_NAME.preferences_pb.bak")
        // Spec 52: the user's TMDB key is device-wide and survives sign-out.
        val tmdbKey = file("$TMDB_API_KEY_STORE_NAME.preferences_pb")
        val profile1 = file("player_settings.preferences_pb")
        val profile2 = file("player_settings_p2.preferences_pb")
        val profile1Bak = file("player_settings.preferences_pb.bak")
        // Spec 44: every profile's look entry, profile 1's included.
        val looks = listOf(
            file("${ProfileLookStore.FEATURE}.preferences_pb"),
            file("${ProfileLookStore.FEATURE}_p3.preferences_pb"),
            file("${ProfileLookStore.FEATURE}_p3.preferences_pb.bak"),
            // Spec 64 D2: the rating cap is profile data, swept like the look entry.
            file("${ProfileRatingCapStore.FEATURE}.preferences_pb"),
            file("${ProfileRatingCapStore.FEATURE}_p3.preferences_pb"),
            file("${ProfileRatingCapStore.FEATURE}_p3.preferences_pb.bak")
        )
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir

        ProfileDataStoreFactory(context).clearProfileScopedData()

        assertTrue(device.exists())
        assertTrue(deviceBak.exists())
        assertTrue(tmdbKey.exists())
        assertFalse(profile1.exists())
        assertFalse(profile2.exists())
        assertFalse(profile1Bak.exists())
        looks.forEach { assertFalse(it.name, it.exists()) }
    }
}
