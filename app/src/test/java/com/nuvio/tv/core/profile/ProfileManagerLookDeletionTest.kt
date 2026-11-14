package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileLookStore
import com.nuvio.tv.data.local.ProfileRatingCapStore
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Spec 44a / 64: deleting a profile deletes its look entry and rating cap (the per-profile sweep). */
class ProfileManagerLookDeletionTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `deleting a profile deletes its look entry and rating cap and leaves the others`() = runTest {
        val filesDir = tmpFolder.newFolder("files")
        val dataStoreDir = File(filesDir, "datastore").apply { mkdirs() }
        fun file(name: String) = File(dataStoreDir, name).apply { writeText("x") }
        val deleted = listOf(
            file("${ProfileLookStore.FEATURE}_p3.preferences_pb"),
            file("${ProfileLookStore.FEATURE}_p3.preferences_pb.bak"),
            file("${ProfileRatingCapStore.FEATURE}_p3.preferences_pb"),
            file("${ProfileRatingCapStore.FEATURE}_p3.preferences_pb.bak")
        )
        val kept = listOf(
            file("${ProfileLookStore.FEATURE}.preferences_pb"),
            file("${ProfileLookStore.FEATURE}_p2.preferences_pb"),
            file("${ProfileRatingCapStore.FEATURE}_p2.preferences_pb")
        )
        val profiles = listOf(
            UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5"),
            UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B"),
            UserProfile(id = 3, name = "Kids", avatarColorHex = "#43A047")
        )
        val store = mockk<ProfileDataStore>(relaxed = true)
        every { store.profilesList } returns flowOf(profiles)
        every { store.activeProfileId } returns flowOf(1)
        every { store.hasEverSelectedProfile } returns flowOf(true)
        every { store.rememberLastProfileEnabled } returns flowOf(false)
        val factory = mockk<ProfileDataStoreFactory>(relaxed = true)
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.getString(any(), *anyVararg()) } returns "Profile 1"
        val manager = ProfileManager(store, factory, context)
        manager.profiles.first { list -> list.any { it.id == 3 } }

        assertTrue(manager.deleteProfile(3))

        coVerify { factory.clearProfile(3) }
        deleted.forEach { assertFalse(it.name, it.exists()) }
        kept.forEach { assertTrue(it.name, it.exists()) }
    }
}
