package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingLimitTest {

    private val arshad = UserProfile(id = 1, name = "Arshad", avatarColorHex = "#E39B2D")
    private val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
    private val zayn = UserProfile(id = 3, name = "Zayn", avatarColorHex = "#E8743B")
    private val noor = UserProfile(id = 4, name = "Noor", avatarColorHex = "#43A047")
    private val profiles = listOf(arshad, sara, zayn, noor)

    @Test
    fun `the panel offers no limit then D4 ranks 1 to 3 (D7)`() {
        assertEquals(
            listOf(ContentRatingCap.ALL_AGES, ContentRatingCap.OLDER_KIDS, ContentRatingCap.TEENS),
            RatingLimitLevels
        )
    }

    @Test
    fun `each level's chips are the ratings its tier adds, as the mockup orders them`() {
        assertEquals(listOf("G", "TV-Y", "TV-G"), ContentRatingCap.ALL_AGES.chipRatings())
        assertEquals(listOf("PG", "TV-PG", "TV-Y7"), ContentRatingCap.OLDER_KIDS.chipRatings())
        assertEquals(listOf("PG-13", "TV-14"), ContentRatingCap.TEENS.chipRatings())
    }

    @Test
    fun `the chips cover every rating the filter matches except the TV-Y7 variant`() {
        val shown = ContentRatingCap.entries.flatMap { it.chipRatings() }.toSet()
        val matched = ContentRatingCap.entries.flatMap { it.ratings }.toSet()
        assertEquals(matched - "TV-Y7-FV", shown)
    }

    @Test
    fun `the ceiling is the highest movie and TV rating the cap allows`() {
        assertEquals("G" to "TV-G", ContentRatingCap.ALL_AGES.ceiling())
        assertEquals("PG" to "TV-PG", ContentRatingCap.OLDER_KIDS.ceiling())
        assertEquals("PG-13" to "TV-14", ContentRatingCap.TEENS.ceiling())
        assertEquals("R" to "TV-MA", ContentRatingCap.MATURE.ceiling())
        assertEquals("NC-17" to "TV-MA", ContentRatingCap.ADULTS_ONLY.ceiling())
    }

    @Test
    fun `the limit is editable where live tv is, a note elsewhere, never on the primary (D9)`() {
        assertEquals(RatingLimitAccess.EDIT, ratingLimitAccess(liveTvEditable = true, isPrimary = false))
        assertEquals(RatingLimitAccess.NOTE, ratingLimitAccess(liveTvEditable = false, isPrimary = false))
        assertEquals(RatingLimitAccess.NONE, ratingLimitAccess(liveTvEditable = true, isPrimary = true))
        assertEquals(RatingLimitAccess.NONE, ratingLimitAccess(liveTvEditable = false, isPrimary = true))
    }

    @Test
    fun `the lock line names other profiles with no pin and no limit, the primary included (D10)`() {
        val pins = mapOf(1 to false, 2 to false, 3 to false, 4 to true)
        val caps = mapOf(1 to null, 2 to null, 3 to ContentRatingCap.OLDER_KIDS, 4 to null)
        assertEquals(listOf(arshad, sara), noPinUnlimitedProfiles(zayn.id, profiles, pins, caps))
        // Another limited profile is no way out; the edited profile never names itself.
        assertEquals(listOf(arshad), noPinUnlimitedProfiles(sara.id, profiles, pins, caps))
        // On create every open profile counts.
        assertEquals(listOf(arshad, sara), noPinUnlimitedProfiles(null, profiles, pins, caps))
    }

    @Test
    fun `an unloaded pin or cap counts as open, so the line warns`() {
        assertEquals(listOf(arshad, sara, noor), noPinUnlimitedProfiles(zayn.id, profiles, emptyMap(), null))
    }

    @Test
    fun `add stays on the startup picker only while no profile has a limit (D10)`() {
        val none = profiles.associate { it.id to null as ContentRatingCap? }
        assertTrue(showAddProfile(canAddProfile = true, managedByPrimary = false, profiles, none))
        val limited = none + (zayn.id to ContentRatingCap.OLDER_KIDS)
        assertFalse(showAddProfile(canAddProfile = true, managedByPrimary = false, profiles, limited))
        // Manage profiles with the primary active keeps it; the six-profile cap still wins.
        assertTrue(showAddProfile(canAddProfile = true, managedByPrimary = true, profiles, limited))
        assertFalse(showAddProfile(canAddProfile = false, managedByPrimary = true, profiles, none))
    }

    @Test
    fun `add fails closed on the startup picker until every profile's cap has loaded`() {
        assertFalse(showAddProfile(canAddProfile = true, managedByPrimary = false, profiles, null))
        val partial = mapOf(1 to null, 2 to null, 3 to null)
        assertFalse(showAddProfile(canAddProfile = true, managedByPrimary = false, profiles, partial))
        assertTrue(showAddProfile(canAddProfile = true, managedByPrimary = true, profiles, null))
    }

    @Test
    fun `a limited profile cannot be deleted from the startup picker, so Add cannot come back (D10)`() {
        val caps = mapOf(1 to null, 2 to null, 3 to ContentRatingCap.OLDER_KIDS)
        assertFalse(profileDeletable(zayn, managedByPrimary = false, caps))
        assertTrue(profileDeletable(sara, managedByPrimary = false, caps))
        // Unloaded fails closed on the picker; Manage profiles keeps Delete; never the primary.
        assertFalse(profileDeletable(noor, managedByPrimary = false, caps))
        assertFalse(profileDeletable(sara, managedByPrimary = false, null))
        assertTrue(profileDeletable(zayn, managedByPrimary = true, caps))
        assertFalse(profileDeletable(arshad, managedByPrimary = true, caps))
    }

    @Test
    fun `an editable save always writes the shown cap, other editors never write one`() {
        assertEquals(RatingCapEdit(ContentRatingCap.TEENS), ratingCapEditFor(RatingLimitAccess.EDIT, ContentRatingCap.TEENS))
        assertEquals(RatingCapEdit(null), ratingCapEditFor(RatingLimitAccess.EDIT, null))
        assertEquals(null, ratingCapEditFor(RatingLimitAccess.NOTE, ContentRatingCap.TEENS))
        assertEquals(null, ratingCapEditFor(RatingLimitAccess.NONE, null))
    }

    @Test
    fun `manage profiles opened by a non-primary profile (the settings grant) keeps limits closed`() {
        val caps = mapOf(1 to null, 2 to null, 3 to ContentRatingCap.OLDER_KIDS, 4 to null)
        val grantedChild = liveTvEditable(isManagementMode = true, storedActiveProfileId = zayn.id)
        assertFalse(showAddProfile(canAddProfile = true, managedByPrimary = grantedChild, profiles, caps))
        assertFalse(profileDeletable(zayn, managedByPrimary = grantedChild, caps))
        // The active id not loaded yet fails closed too.
        assertFalse(showAddProfile(true, liveTvEditable(isManagementMode = true, storedActiveProfileId = null), profiles, caps))
        assertTrue(showAddProfile(true, liveTvEditable(isManagementMode = true, storedActiveProfileId = 1), profiles, caps))
    }

    @Test
    fun `the panel focuses the stored level, or the loosest offered one for a level it does not offer`() {
        assertEquals(null, ratingLimitInitialFocus(null))
        assertEquals(ContentRatingCap.OLDER_KIDS, ratingLimitInitialFocus(ContentRatingCap.OLDER_KIDS))
        assertEquals(ContentRatingCap.TEENS, ratingLimitInitialFocus(ContentRatingCap.MATURE))
        assertEquals(ContentRatingCap.TEENS, ratingLimitInitialFocus(ContentRatingCap.ADULTS_ONLY))
    }

    @Test
    fun `a cap is loaded once its key is present, a null value included`() {
        assertTrue(ratingCapLoaded(2, mapOf(2 to null)))
        assertFalse(ratingCapLoaded(2, mapOf(1 to null)))
        assertFalse(ratingCapLoaded(2, null))
    }
}
