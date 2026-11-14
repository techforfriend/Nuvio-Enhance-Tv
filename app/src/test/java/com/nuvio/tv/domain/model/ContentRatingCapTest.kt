package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 64 K1a: the D4 ladder and the fail-closed isRatingAllowed (D1). */
class ContentRatingCapTest {

    @Test
    fun `every D4 rating sits on its rank`() {
        val ranks = mapOf(
            "TV-Y" to 1, "TV-G" to 1, "G" to 1,
            "TV-Y7" to 2, "TV-Y7-FV" to 2, "PG" to 2, "TV-PG" to 2,
            "PG-13" to 3, "TV-14" to 3,
            "R" to 4, "TV-MA" to 4,
            "NC-17" to 5
        )
        ranks.forEach { (rating, rank) ->
            assertEquals(rating, rank, ContentRatingCap.tierOf(rating)!!.ordinal + 1)
        }
        assertEquals(ranks.keys, ContentRatingCap.entries.flatMap { it.ratings }.toSet())
    }

    @Test
    fun `ratings are matched trimmed and case-insensitively`() {
        assertEquals(ContentRatingCap.TEENS, ContentRatingCap.tierOf(" pg-13 "))
        assertEquals(ContentRatingCap.OLDER_KIDS, ContentRatingCap.tierOf("tv-y7-fv"))
    }

    @Test
    fun `unknown, unrated and non-US codes have no tier`() {
        listOf(null, "", "  ", "NR", "Not Rated", "Unrated", "12", "15", "K-12", "PG13", "M18", "TV-Y7 FV", "U")
            .forEach { assertNull(it.toString(), ContentRatingCap.tierOf(it)) }
    }

    @Test
    fun `a cap allows its own tier and below, not above`() {
        val cap = ContentRatingCap.OLDER_KIDS
        assertTrue(isRatingAllowed("G", cap))
        assertTrue(isRatingAllowed("TV-Y", cap))
        assertTrue(isRatingAllowed("PG", cap))
        assertTrue(isRatingAllowed("TV-PG", cap))
        assertFalse(isRatingAllowed("PG-13", cap))
        assertFalse(isRatingAllowed("TV-14", cap))
        assertFalse(isRatingAllowed("R", cap))
        assertFalse(isRatingAllowed("NC-17", cap))
        assertTrue(isRatingAllowed("NC-17", ContentRatingCap.ADULTS_ONLY))
    }

    @Test
    fun `under any cap an unknown rating is hidden`() {
        ContentRatingCap.entries.forEach { cap ->
            assertFalse(isRatingAllowed(null, cap))
            assertFalse(isRatingAllowed("NR", cap))
            assertFalse(isRatingAllowed("15", cap))
        }
    }

    @Test
    fun `no cap allows everything, unknown included`() {
        assertTrue(isRatingAllowed(null, null))
        assertTrue(isRatingAllowed("NR", null))
        assertTrue(isRatingAllowed("NC-17", null))
    }

    @Test
    fun `storage keys round-trip and junk decodes to null`() {
        ContentRatingCap.entries.forEach { assertEquals(it, ContentRatingCap.fromStorageKey(it.storageKey)) }
        assertNull(ContentRatingCap.fromStorageKey(null))
        assertNull(ContentRatingCap.fromStorageKey("ALL_AGES"))
        assertNull(ContentRatingCap.fromStorageKey("toddlers"))
    }
}
