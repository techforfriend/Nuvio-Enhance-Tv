package com.nuvio.tv.core.profile

import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileRatingCapStore
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 64 K1b: which profile's cap the catalog surfaces read. */
class ActiveRatingCapTest {
    private val profileDataStore = mockk<ProfileDataStore>()
    private val capStore = mockk<ProfileRatingCapStore> {
        every { capFor(1) } returns flowOf(null)
        every { capFor(2) } returns flowOf(ContentRatingCap.OLDER_KIDS)
    }
    private val activeCap = ActiveRatingCap(profileDataStore, capStore)

    @Test
    fun `reads the stored active profile's cap`() = runTest {
        every { profileDataStore.activeProfileId } returns flowOf(2)
        assertEquals(ContentRatingCap.OLDER_KIDS, activeCap.current())
    }

    @Test
    fun `uncapped active profile reads null`() = runTest {
        every { profileDataStore.activeProfileId } returns flowOf(1)
        assertNull(activeCap.current())
    }

    @Test
    fun `an unreadable active id fails closed to the strictest cap`() = runTest {
        every { profileDataStore.activeProfileId } returns flow { throw IOException("disk") }
        assertEquals(ContentRatingCap.STRICTEST, activeCap.current())
        verify(exactly = 0) { capStore.capFor(any()) }
    }

    @Test
    fun `limit names the active profile`() = runTest {
        every { profileDataStore.activeProfileId } returns flowOf(2)
        every { profileDataStore.profilesList } returns flowOf(listOf(profile(1, "Arshad"), profile(2, "Zayn")))
        assertEquals(ActiveRatingLimit(ContentRatingCap.OLDER_KIDS, "Zayn"), activeCap.currentLimit())
    }

    @Test
    fun `uncapped active profile has no limit and reads no profile list`() = runTest {
        every { profileDataStore.activeProfileId } returns flowOf(1)
        assertNull(activeCap.currentLimit())
        verify(exactly = 0) { profileDataStore.profilesList }
    }

    @Test
    fun `an unreadable profile list keeps the limit without a name`() = runTest {
        every { profileDataStore.activeProfileId } returns flowOf(2)
        every { profileDataStore.profilesList } returns flow { throw IOException("disk") }
        assertEquals(ActiveRatingLimit(ContentRatingCap.OLDER_KIDS, null), activeCap.currentLimit())
    }

    @Test
    fun `an unreadable active id limits to the strictest cap without a name`() = runTest {
        every { profileDataStore.activeProfileId } returns flow { throw IOException("disk") }
        assertEquals(ActiveRatingLimit(ContentRatingCap.STRICTEST, null), activeCap.currentLimit())
    }

    private fun profile(id: Int, name: String) = UserProfile(id = id, name = name, avatarColorHex = "#1E88E5")
}
