package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.DpadCombo
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.PinLockouts
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.DpadUnlockState
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.ProfileLookStore
import com.nuvio.tv.data.local.ProfileRatingCapStore
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ProfileLookChoice
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.data.LiveTvUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerifyOrder
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val profileSyncService = mockk<ProfileSyncService>(relaxed = true)
    private val avatarRepository = mockk<AvatarRepository>(relaxed = true)
    private val profileBackgroundRepository = mockk<ProfileBackgroundRepository>(relaxed = true)
    private val profileLockStateDataStore = mockk<ProfileLockStateDataStore>(relaxed = true)
    private val liveTvUserStore = mockk<LiveTvUserStore>(relaxed = true)
    private val profileLookStore = mockk<ProfileLookStore>(relaxed = true)
    private val myPhotosStore = mockk<MyPhotosStore>(relaxed = true)
    private val ratingCapStore = mockk<ProfileRatingCapStore>(relaxed = true)

    private val sequence = listOf(
        DpadDirection.UP, DpadDirection.DOWN, DpadDirection.LEFT,
        DpadDirection.RIGHT, DpadDirection.UP, DpadDirection.DOWN
    )

    private fun viewModelWith(
        state: DpadUnlockState,
        profiles: List<UserProfile> = emptyList(),
        profileList: MutableStateFlow<List<UserProfile>> = MutableStateFlow(profiles)
    ): ProfileSelectionViewModel {
        every { profileLockStateDataStore.dpadUnlockState } returns flowOf(state)
        every { profileLockStateDataStore.pinEnabled } returns flowOf(emptyMap())
        every { profileManager.activeProfileId } returns MutableStateFlow(0)
        every { profileManager.profiles } returns profileList
        return ProfileSelectionViewModel(
            profileManager = profileManager,
            profileSyncService = profileSyncService,
            avatarRepository = avatarRepository,
            profileBackgroundRepository = profileBackgroundRepository,
            profileLockStateDataStore = profileLockStateDataStore,
            pinLockouts = PinLockouts(),
            liveTvUserStore = liveTvUserStore,
            profileLookStore = profileLookStore,
            myPhotosStore = myPhotosStore,
            ratingCapStore = ratingCapStore,
            photoUploadStarter = mockk(relaxed = true)
        )
    }

    /** Drives verifyDpadCombo to completion; null means the callback never fired. */
    private fun TestScope.verdictFor(state: DpadUnlockState, sequence: List<DpadDirection>): Boolean? {
        var matched: Boolean? = null
        viewModelWith(state).verifyDpadCombo(sequence) { matched = it }
        advanceUntilIdle()
        return matched
    }

    private fun storedCombo(): DpadUnlockState = DpadUnlockState(
        comboHash = DpadCombo.hash(sequence, SALT),
        salt = SALT,
        comboLength = sequence.size
    )

    @Test
    fun `the saved sequence of the stored length matches`() = runTest {
        assertEquals(true, verdictFor(storedCombo(), sequence))
    }

    @Test
    fun `a wrong sequence of the same length fails`() = runTest {
        val wrong = sequence.dropLast(1) + DpadDirection.LEFT
        assertEquals(false, verdictFor(storedCombo(), wrong))
    }

    @Test
    fun `a sequence shorter than the stored length never matches`() = runTest {
        assertEquals(false, verdictFor(storedCombo(), sequence.dropLast(1)))
    }

    @Test
    fun `an unset combination never matches`() = runTest {
        assertEquals(false, verdictFor(DpadUnlockState(enabled = true), sequence))
    }

    @Test
    fun `verify hashes with the stored salt`() = runTest {
        // The same presses under any salt other than the stored one must not
        // unlock — this pins that verifyDpadCombo feeds the STORED salt (and
        // the argument order) into DpadCombo.verify.
        val hashUnderOtherSalt = DpadCombo.hash(sequence, DpadCombo.generateSalt())
        assertEquals(false, verdictFor(storedCombo().copy(comboHash = hashUnderOtherSalt), sequence))
    }

    @Test
    fun `create reports the local save and pushes only after one`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returnsMany listOf(created(2), null)
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        viewModel.createProfile("Amira", "#1E88E5", null) { results += it }
        advanceUntilIdle()
        viewModel.createProfile("Guest", "#1E88E5", null) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, false), results)
        coVerify(exactly = 1) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `update reports the local save, false once the profile is gone, and pushes only after one`() = runTest {
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returnsMany listOf(true, false)
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        viewModel.updateProfile(sara) { results += it }
        advanceUntilIdle()
        viewModel.updateProfile(sara) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, false), results)
        coVerify(exactly = 1) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `create always writes the live tv switch for the new id, an edit only when passed after a save (D17)`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        coEvery { liveTvUserStore.setEnabled(any(), any()) } returns true
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returnsMany listOf(true, true, false)
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        viewModel.createProfile("Kids", "#1E88E5", null, liveTvEnabled = true) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { liveTvUserStore.setEnabled(4, true) }
        viewModel.createProfile("Kids", "#1E88E5", null, liveTvEnabled = false) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { liveTvUserStore.setEnabled(4, false) }
        viewModel.updateProfile(sara) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 0) { liveTvUserStore.setEnabled(2, any()) }
        viewModel.updateProfile(sara, liveTvEnabled = false) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { liveTvUserStore.setEnabled(2, false) }
        // The profile is gone: no switch write, the save fails.
        viewModel.updateProfile(sara, liveTvEnabled = true) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 0) { liveTvUserStore.setEnabled(2, true) }
        assertEquals(listOf(true, true, true, true, false), results)
    }

    @Test
    fun `a failed live tv write keeps the profile saved, reports it apart and still pushes`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returns true
        coEvery { liveTvUserStore.setEnabled(any(), any()) } returns false
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        var notSaved = 0
        viewModel.createProfile("Kids", "#1E88E5", null, false, { notSaved++ }) { results += it }
        advanceUntilIdle()
        viewModel.updateProfile(sara, false, { notSaved++ }) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, true), results)
        assertEquals(2, notSaved)
        coVerify(exactly = 2) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `the live tv map holds every profile's switch and follows a change`() = runTest {
        val kidsSwitch = MutableStateFlow(false)
        every { liveTvUserStore.enabledFor(1) } returns flowOf(true)
        every { liveTvUserStore.enabledFor(2) } returns kidsSwitch
        val viewModel = viewModelWith(
            DpadUnlockState(),
            profiles = listOf(
                UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#43A047")
            )
        )
        advanceUntilIdle()
        assertEquals(mapOf(1 to true, 2 to false), viewModel.liveTvEnabledById.value)
        kidsSwitch.value = true
        advanceUntilIdle()
        assertEquals(mapOf(1 to true, 2 to true), viewModel.liveTvEnabledById.value)
    }

    private fun created(id: Int) = UserProfile(id = id, name = "Saved $id", avatarColorHex = "#1E88E5")

    @Test
    fun `create writes the look entry for the new id before the result, keyed on the saved profile (Spec 44)`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returnsMany listOf(created(4), created(5), null)
        val events = mutableListOf<String>()
        coEvery { profileLookStore.write(any(), any()) } coAnswers { events += "write ${firstArg<Int>()}"; true }
        val viewModel = viewModelWith(DpadUnlockState())
        viewModel.createProfile("Kids", "#1E88E5", null) { events += "result $it" }
        advanceUntilIdle()
        coVerify(exactly = 1) { profileLookStore.write(4, null) }
        val fox = ProfileLookPick.Bundled("fox")
        viewModel.createProfile("Kids", "#1E88E5", null, localAvatar = fox) { events += "result $it" }
        advanceUntilIdle()
        // The key is the profile as saved (ProfileManager may rename it), not the typed name.
        coVerify(exactly = 1) {
            profileLookStore.write(5, ProfileLookEntry(avatar = ProfileLookChoice(fox, "Saved 5", null)))
        }
        // At the cap nothing is written.
        viewModel.createProfile("Kids", "#1E88E5", null, localAvatar = fox) { events += "result $it" }
        advanceUntilIdle()
        assertEquals(listOf("write 4", "result true", "write 5", "result true", "result false"), events)
    }

    @Test
    fun `update writes the look entry before the result and the push, never for a vanished profile`() = runTest {
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B", avatarId = "kira")
        coEvery { profileManager.updateProfile(sara) } returnsMany listOf(true, true, false)
        val events = mutableListOf<String>()
        coEvery { profileLookStore.write(any(), any()) } coAnswers { events += "write"; true }
        coEvery { profileSyncService.pushToRemote() } coAnswers { events += "push"; Result.success(Unit) }
        val viewModel = viewModelWith(DpadUnlockState())
        val photo = ProfileLookPick.Photo("p1")
        viewModel.updateProfile(sara, localAvatar = photo) { events += "result $it" }
        advanceUntilIdle()
        assertEquals(listOf("write", "result true", "push"), events)
        coVerify { profileLookStore.write(2, ProfileLookEntry(avatar = ProfileLookChoice(photo, "Sara", "kira"))) }
        viewModel.updateProfile(sara) {}
        advanceUntilIdle()
        coVerify(exactly = 1) { profileLookStore.write(2, null) }
        viewModel.updateProfile(sara, localAvatar = photo) {}
        advanceUntilIdle()
        coVerify(exactly = 2) { profileLookStore.write(any(), any()) }
    }

    @Test
    fun `a failed look write keeps the profile saved and is reported apart`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returns true
        coEvery { profileLookStore.write(any(), any()) } returns false
        coEvery { ratingCapStore.write(any(), any()) } returns true
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        var notSaved = 0
        var capNotSaved = 0
        val onCapNotSaved: () -> Unit = { capNotSaved++ }
        viewModel.createProfile("Kids", "#1E88E5", null, onLookNotSaved = { notSaved++ }, onRatingCapNotSaved = onCapNotSaved) {
            results += it
        }
        advanceUntilIdle()
        viewModel.updateProfile(
            sara, ratingCap = RatingCapEdit(null), onLookNotSaved = { notSaved++ }, onRatingCapNotSaved = onCapNotSaved
        ) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, true), results)
        assertEquals(2, notSaved)
        assertEquals(0, capNotSaved)
        coVerify(exactly = 2) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `the look map is null until loaded, then holds only profiles with an entry`() = runTest {
        val kidsLook = ProfileLookEntry(avatar = ProfileLookChoice(ProfileLookPick.Bundled("fox"), "Kids", null))
        val kidsFlow = MutableStateFlow<ProfileLookEntry?>(kidsLook)
        every { profileLookStore.entryFor(1) } returns flowOf(null)
        every { profileLookStore.entryFor(2) } returns kidsFlow
        val viewModel = viewModelWith(
            DpadUnlockState(),
            profiles = listOf(
                UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#43A047")
            )
        )
        assertEquals(null, viewModel.lookEntriesById.value)
        advanceUntilIdle()
        assertEquals(mapOf(2 to kidsLook), viewModel.lookEntriesById.value)
        kidsFlow.value = null
        advanceUntilIdle()
        assertEquals(emptyMap<Int, ProfileLookEntry>(), viewModel.lookEntriesById.value)
    }

    @Test
    fun `the cap map is null until loaded, holds a null for no limit, and follows a change (Spec 64)`() = runTest {
        val zaynCap = MutableStateFlow<ContentRatingCap?>(ContentRatingCap.OLDER_KIDS)
        every { ratingCapStore.capFor(1) } returns flowOf(null)
        every { ratingCapStore.capFor(2) } returns zaynCap
        val viewModel = viewModelWith(
            DpadUnlockState(),
            profiles = listOf(
                UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Zayn", avatarColorHex = "#E8743B")
            )
        )
        assertEquals(null, viewModel.ratingCapById.value)
        advanceUntilIdle()
        assertEquals(mapOf(1 to null, 2 to ContentRatingCap.OLDER_KIDS), viewModel.ratingCapById.value)
        zaynCap.value = null
        advanceUntilIdle()
        assertEquals(mapOf<Int, ContentRatingCap?>(1 to null, 2 to null), viewModel.ratingCapById.value)
    }

    @Test
    fun `a newly listed profile has no cap entry until its own cap loads, keeping Add and Edit closed`() = runTest {
        every { ratingCapStore.capFor(1) } returns flowOf(null)
        every { ratingCapStore.capFor(2) } returns flowOf(ContentRatingCap.OLDER_KIDS)
        val noorCap = MutableSharedFlow<ContentRatingCap?>()
        every { ratingCapStore.capFor(3) } returns noorCap
        val arshad = UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5")
        val zayn = UserProfile(id = 2, name = "Zayn", avatarColorHex = "#E8743B")
        val noor = UserProfile(id = 3, name = "Noor", avatarColorHex = "#43A047")
        val list = MutableStateFlow(listOf(arshad, zayn))
        val viewModel = viewModelWith(DpadUnlockState(), profileList = list)
        advanceUntilIdle()
        list.value = listOf(arshad, zayn, noor)
        advanceUntilIdle()
        val pending = viewModel.ratingCapById.value
        assertFalse(ratingCapLoaded(3, pending))
        assertFalse(showAddProfile(true, managedByPrimary = false, profiles = list.value, caps = pending))
        noorCap.emit(null)
        advanceUntilIdle()
        assertTrue(ratingCapLoaded(3, viewModel.ratingCapById.value))
    }

    @Test
    fun `create always writes the cap for the new id before the result, and none when the create fails`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returnsMany listOf(created(4), created(5), null)
        val events = mutableListOf<String>()
        coEvery { ratingCapStore.write(any(), any()) } coAnswers { events += "cap ${firstArg<Int>()} ${secondArg<ContentRatingCap?>()}"; true }
        val viewModel = viewModelWith(DpadUnlockState())
        // No limit is written too: it clears a reused id's stale cap.
        viewModel.createProfile("Kids", "#1E88E5", null) { events += "result $it" }
        advanceUntilIdle()
        viewModel.createProfile("Zayn", "#1E88E5", null, ratingCap = ContentRatingCap.OLDER_KIDS) { events += "result $it" }
        advanceUntilIdle()
        viewModel.createProfile("Guest", "#1E88E5", null, ratingCap = ContentRatingCap.TEENS) { events += "result $it" }
        advanceUntilIdle()
        assertEquals(
            listOf("cap 4 null", "result true", "cap 5 OLDER_KIDS", "result true", "result false"),
            events
        )
    }

    @Test
    fun `update writes the cap only when an edit is passed, after a save and before the result`() = runTest {
        val zayn = UserProfile(id = 3, name = "Zayn", avatarColorHex = "#E8743B")
        coEvery { profileManager.updateProfile(zayn) } returnsMany listOf(true, true, true, false)
        val events = mutableListOf<String>()
        coEvery { ratingCapStore.write(any(), any()) } coAnswers { events += "cap ${secondArg<ContentRatingCap?>()}"; true }
        val viewModel = viewModelWith(DpadUnlockState())
        viewModel.updateProfile(zayn) { events += "result $it" }
        advanceUntilIdle()
        viewModel.updateProfile(zayn, ratingCap = RatingCapEdit(ContentRatingCap.ALL_AGES)) { events += "result $it" }
        advanceUntilIdle()
        // Removing the limit is a write of null, not a skipped write.
        viewModel.updateProfile(zayn, ratingCap = RatingCapEdit(null)) { events += "result $it" }
        advanceUntilIdle()
        // The profile is gone: no cap write.
        viewModel.updateProfile(zayn, ratingCap = RatingCapEdit(ContentRatingCap.TEENS)) { events += "result $it" }
        advanceUntilIdle()
        assertEquals(
            listOf("result true", "cap ALL_AGES", "result true", "cap null", "result true", "result false"),
            events
        )
        coVerify(exactly = 2) { ratingCapStore.write(3, any()) }
    }

    @Test
    fun `a failed cap write keeps the profile saved, reports it apart and still pushes`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        val zayn = UserProfile(id = 3, name = "Zayn", avatarColorHex = "#E8743B")
        coEvery { profileManager.updateProfile(zayn) } returns true
        coEvery { ratingCapStore.write(any(), any()) } returns false
        coEvery { profileLookStore.write(any(), any()) } returns true
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        var notSaved = 0
        viewModel.createProfile("Kids", "#1E88E5", null, onRatingCapNotSaved = { notSaved++ }) { results += it }
        advanceUntilIdle()
        viewModel.updateProfile(zayn, ratingCap = RatingCapEdit(ContentRatingCap.ALL_AGES), onRatingCapNotSaved = { notSaved++ }) {
            results += it
        }
        advanceUntilIdle()
        assertEquals(listOf(true, true), results)
        assertEquals(2, notSaved)
        coVerify(exactly = 2) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `deleting keeps going after the screen's scope is cancelled, so the remote delete and push run (D14)`() = runTest {
        val viewModel = viewModelWith(DpadUnlockState())
        // Deleting the active profile returns the app to Who's watching, which clears this VM.
        coEvery { profileManager.deleteProfile(3) } coAnswers { viewModel.viewModelScope.cancel(); true }
        // Suspends like the real RPC: without NonCancellable the cancelled coroutine stops here.
        coEvery { profileSyncService.deleteProfileData(3) } coAnswers { delay(1); Result.success(Unit) }
        viewModel.deleteProfile(3)
        advanceUntilIdle()
        coVerifyOrder {
            profileSyncService.deleteProfileData(3)
            profileSyncService.pushToRemote()
        }
    }

    @Test
    fun `creating keeps going after the screen's scope is cancelled, so the cap still lands (D14)`() = runTest {
        val viewModel = viewModelWith(DpadUnlockState())
        // The first non-primary makes MainActivity show Who's watching, which clears this VM.
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } coAnswers {
            viewModel.viewModelScope.cancel()
            created(2)
        }
        // Suspends like the real DataStore writes: without NonCancellable the cancelled coroutine stops at the first.
        coEvery { profileLookStore.write(any(), any()) } coAnswers { delay(1); true }
        coEvery { ratingCapStore.write(any(), any()) } coAnswers { delay(1); true }
        viewModel.createProfile("Zayn", "#E8743B", null, ratingCap = ContentRatingCap.OLDER_KIDS)
        advanceUntilIdle()
        coVerify { ratingCapStore.write(2, ContentRatingCap.OLDER_KIDS) }
        coVerify { profileSyncService.pushToRemote() }
    }

    private companion object {
        const val SALT = "00112233445566778899aabbccddeeff"
    }
}
