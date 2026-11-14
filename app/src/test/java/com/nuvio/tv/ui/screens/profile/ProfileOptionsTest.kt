package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileOptionsTest {

    private val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")

    @Test
    fun `rows follow the profile's pin and whether it can be deleted`() {
        assertEquals(
            listOf(ProfileOption.EDIT, ProfileOption.SET_PIN, ProfileOption.DELETE),
            profileOptionRows(canDelete = true, hasPin = false)
        )
        assertEquals(
            listOf(ProfileOption.EDIT, ProfileOption.CHANGE_PIN, ProfileOption.REMOVE_PIN),
            profileOptionRows(canDelete = false, hasPin = true)
        )
        assertEquals(
            listOf(ProfileOption.EDIT, ProfileOption.CHANGE_PIN, ProfileOption.REMOVE_PIN, ProfileOption.DELETE),
            profileOptionRows(canDelete = true, hasPin = true)
        )
        assertEquals(listOf(ProfileOption.EDIT, ProfileOption.SET_PIN), profileOptionRows(canDelete = false, hasPin = false))
    }

    @Test
    fun `shares line names the primary only for a member using its addons`() {
        val primary = UserProfile(id = 1, name = "Arshad", avatarColorHex = "#E39B2D", usesPrimaryAddons = true)
        val kids = UserProfile(id = 3, name = "Kids", avatarColorHex = "#43A047", usesPrimaryAddons = true)
        val profiles = listOf(primary, sara, kids)
        assertEquals("Arshad", sharesAddonsOf(kids, profiles))
        assertEquals(null, sharesAddonsOf(sara, profiles))
        assertEquals(null, sharesAddonsOf(primary, profiles))
        assertEquals(null, sharesAddonsOf(kids, listOf(sara, kids)))
    }

    @Test
    fun `the row turns compact only at six items`() {
        assertFalse(profileRowIsCompact(1))
        assertFalse(profileRowIsCompact(5))
        assertTrue(profileRowIsCompact(6))
    }

    @Test
    fun `only the startup picker asks a locked profile's pin before options`() {
        assertTrue(optionsNeedPin(isManagementMode = false, hasPin = true))
        assertFalse(optionsNeedPin(isManagementMode = false, hasPin = false))
        assertFalse(optionsNeedPin(isManagementMode = true, hasPin = true))
    }

    @Test
    fun `only manage profiles with the primary active offers the live tv switch (Spec 34 D17)`() {
        assertTrue(liveTvEditable(isManagementMode = true, storedActiveProfileId = 1))
        assertFalse(liveTvEditable(isManagementMode = true, storedActiveProfileId = 2))
        assertFalse(liveTvEditable(isManagementMode = true, storedActiveProfileId = null))
        assertFalse(liveTvEditable(isManagementMode = false, storedActiveProfileId = 1))
        assertFalse(liveTvEditable(isManagementMode = false, storedActiveProfileId = 3))
    }

    @Test
    fun `change pin skips its ask after the options pin, remove and delete keep theirs`() {
        assertEquals(
            ProfileOptionTarget.Pin(ProfilePinOverlayState.Set(sara, currentPin = "1234")),
            profileOptionTarget(ProfileOption.CHANGE_PIN, sara, hasPin = true, verifiedPin = "1234")
        )
        assertEquals(
            ProfileOptionTarget.Pin(ProfilePinOverlayState.VerifyCurrentForChange(sara)),
            profileOptionTarget(ProfileOption.CHANGE_PIN, sara, hasPin = true, verifiedPin = null)
        )
        assertEquals(
            ProfileOptionTarget.Pin(ProfilePinOverlayState.VerifyCurrentForRemove(sara)),
            profileOptionTarget(ProfileOption.REMOVE_PIN, sara, hasPin = true, verifiedPin = "1234")
        )
        assertEquals(
            ProfileOptionTarget.Pin(ProfilePinOverlayState.VerifyCurrentForDelete(sara)),
            profileOptionTarget(ProfileOption.DELETE, sara, hasPin = true, verifiedPin = "1234")
        )
    }

    @Test
    fun `unlocked rows go straight to their screens`() {
        assertEquals(ProfileOptionTarget.Edit, profileOptionTarget(ProfileOption.EDIT, sara, hasPin = false, verifiedPin = null))
        assertEquals(ProfileOptionTarget.ConfirmDelete, profileOptionTarget(ProfileOption.DELETE, sara, hasPin = false, verifiedPin = null))
        assertEquals(
            ProfileOptionTarget.Pin(ProfilePinOverlayState.Set(sara)),
            profileOptionTarget(ProfileOption.SET_PIN, sara, hasPin = false, verifiedPin = null)
        )
    }
}
