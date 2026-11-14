package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.ReleaseAudioTag
import com.nuvio.tv.domain.model.ReleaseQuality
import com.nuvio.tv.domain.model.ReleaseResolution
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved filters and the Sources row's pins (Spec 65 P2, D12-D14). */
class SavedStreamFiltersTest {

    private fun filter(vararg values: Pair<FacetGroup, String>) =
        values.fold(FacetFilter.NONE) { filter, (group, key) -> filter.toggled(FacetValue(group, key)) }

    private val uhd = FacetGroup.RESOLUTION to ReleaseResolution.P2160.name
    private val atmos = FacetGroup.AUDIO to ReleaseAudioTag.ATMOS.name
    private val remux = FacetGroup.SOURCE to ReleaseQuality.BLURAY_REMUX.name
    private val dv = FacetGroup.HDR to FacetKeys.DV

    private val dvRemux = filter(uhd, dv, remux)
    private val saved = SavedStreamFilters.NONE
        .withSaved("a", "4K DV remux", dvRemux)
        .withSaved("b", "Atmos", filter(atmos))

    @Test
    fun `a filter matches the saved one holding exactly its values, in any order`() {
        assertEquals("a", saved.matching(filter(remux, dv, uhd))?.id)
        assertEquals("b", saved.matching(filter(atmos))?.id)
        // One value more, or one less, is another filter.
        assertNull(saved.matching(filter(uhd, dv, remux, atmos)))
        assertNull(saved.matching(filter(uhd, dv)))
        assertNull(saved.matching(FacetFilter.NONE))
    }

    @Test
    fun `a group left with no value on does not make a filter differ from its saved one`() {
        val withEmptyGroup = FacetFilter(filter(atmos).on + (FacetGroup.CODEC to emptySet()))
        assertEquals("b", saved.matching(withEmptyGroup)?.id)
        assertFalse(saved.canSave(withEmptyGroup))
        val stored = SavedStreamFilters.NONE.withSaved("x", "Atmos", withEmptyGroup).filters.single()
        assertEquals(setOf(FacetGroup.AUDIO), stored.values.keys)
    }

    @Test
    fun `only values that are on and not saved yet can be saved`() {
        assertTrue(saved.canSave(filter(uhd, atmos)))
        assertFalse(saved.canSave(dvRemux))
        assertFalse(saved.canSave(FacetFilter.NONE))
        assertSame(saved, saved.withSaved("c", "again", dvRemux))
        assertSame(saved, saved.withSaved("c", "nothing", FacetFilter.NONE))
    }

    @Test
    fun `a saved filter is listed last and gives its values back as a filter`() {
        val next = saved.withSaved("c", "4K · Atmos", filter(uhd, atmos))
        assertEquals(listOf("a", "b", "c"), next.filters.map { it.id })
        assertEquals(filter(uhd, atmos), next.filters.last().filter)
    }

    @Test
    fun `the list of saved filters stops at its cap`() {
        val full = (1..SavedStreamFilters.MAX_FILTERS).fold(SavedStreamFilters.NONE) { all, n ->
            all.withSaved("id$n", "f$n", filter(FacetGroup.CODEC to "codec$n"))
        }
        assertEquals(SavedStreamFilters.MAX_FILTERS, full.filters.size)
        assertFalse(full.canSave(dvRemux))
        assertSame(full, full.withSaved("one-more", "4K DV remux", dvRemux))
    }

    @Test
    fun `a pin toggles on at the end and off again, keeping the others' order`() {
        val pinned = saved
            .withPinToggled(StreamPin.Filter("b"))
            .withPinToggled(StreamPin.Addon("AIOStreams"))
            .withPinToggled(StreamPin.Filter("a"))
        assertEquals(
            listOf(StreamPin.Filter("b"), StreamPin.Addon("AIOStreams"), StreamPin.Filter("a")),
            pinned.pins
        )
        assertTrue(pinned.isPinned(StreamPin.Addon("AIOStreams")))
        val unpinned = pinned.withPinToggled(StreamPin.Addon("AIOStreams"))
        assertEquals(listOf(StreamPin.Filter("b"), StreamPin.Filter("a")), unpinned.pins)
        assertFalse(unpinned.isPinned(StreamPin.Addon("AIOStreams")))
    }

    @Test
    fun `a saved filter that is not there cannot be pinned`() {
        assertSame(saved, saved.withPinToggled(StreamPin.Filter("gone")))
    }

    @Test
    fun `pins stop at their cap, and a pinned one can still be unpinned there`() {
        val full = (1..SavedStreamFilters.MAX_PINS).fold(saved) { all, n -> all.withPinToggled(StreamPin.Addon("addon$n")) }
        assertEquals(SavedStreamFilters.MAX_PINS, full.pins.size)
        assertSame(full, full.withPinToggled(StreamPin.Filter("a")))
        assertEquals(SavedStreamFilters.MAX_PINS - 1, full.withPinToggled(StreamPin.Addon("addon1")).pins.size)
    }

    @Test
    fun `what is read from disk loses broken filters, repeated ids, orphan and repeated pins`() {
        val read = SavedStreamFilters(
            filters = listOf(
                SavedStreamFilter("a", "4K DV remux", dvRemux.on),
                SavedStreamFilter("a", "same id again", filter(atmos).on),
                SavedStreamFilter("", "no id", filter(atmos).on),
                SavedStreamFilter("c", " ", filter(atmos).on),
                SavedStreamFilter("d", "no values", mapOf(FacetGroup.AUDIO to emptySet())),
                SavedStreamFilter("e", "Atmos", filter(atmos).on + (FacetGroup.CODEC to emptySet()))
            ),
            pins = listOf(
                StreamPin.Filter("a"),
                StreamPin.Filter("d"),
                StreamPin.Addon("Torrentio"),
                StreamPin.Addon(""),
                StreamPin.Filter("a")
            )
        ).normalized()
        assertEquals(listOf("a" to "4K DV remux", "e" to "Atmos"), read.filters.map { it.id to it.name })
        assertEquals(setOf(FacetGroup.AUDIO), read.filters.last().values.keys)
        assertEquals(listOf(StreamPin.Filter("a"), StreamPin.Addon("Torrentio")), read.pins)
    }

    @Test
    fun `normalizing what is already sound returns it as it is, and cuts both lists to their caps`() {
        val pinned = saved.withPinToggled(StreamPin.Filter("a"))
        assertSame(pinned, pinned.normalized())
        val over = SavedStreamFilters(
            filters = (1..SavedStreamFilters.MAX_FILTERS + 3).map { SavedStreamFilter("id$it", "f$it", filter(atmos).on) },
            pins = (1..SavedStreamFilters.MAX_PINS + 3).map { StreamPin.Addon("addon$it") }
        ).normalized()
        assertEquals(SavedStreamFilters.MAX_FILTERS, over.filters.size)
        assertEquals(SavedStreamFilters.MAX_PINS, over.pins.size)
    }

    @Test
    fun `the filter groups keep the names saved filters are stored under`() {
        assertEquals(listOf("RESOLUTION", "HDR", "AUDIO", "SOURCE", "CODEC"), FacetGroup.entries.map { it.name })
    }

    @Test
    fun `saved filters and pins survive a round trip through JSON`() {
        val pinned = saved.withPinToggled(StreamPin.Filter("a")).withPinToggled(StreamPin.Addon("AIOStreams"))
        val json = Json.encodeToString(SavedStreamFilters.serializer(), pinned)
        assertEquals(pinned, Json.decodeFromString(SavedStreamFilters.serializer(), json))
    }

    private val three = saved.withSaved("c", "1080p remux", filter(FacetGroup.RESOLUTION to ReleaseResolution.P1080.name, remux))

    @Test
    fun `the phone's list reorders and renames, and keeps every value as saved`() {
        val edited = three.edited(
            order = listOf(SavedFilterEdit("c", "Kids"), SavedFilterEdit("a", "4K DV remux"), SavedFilterEdit("b", " Movie   night ")),
            deleted = emptySet()
        )
        assertEquals(listOf("c" to "Kids", "a" to "4K DV remux", "b" to "Movie night"), edited.filters.map { it.id to it.name })
        assertEquals(three.filters.associate { it.id to it.values }, edited.filters.associate { it.id to it.values })
    }

    @Test
    fun `a deleted filter goes with its pin, the other pins stay in order`() {
        val pinned = three
            .withPinToggled(StreamPin.Filter("b"))
            .withPinToggled(StreamPin.Addon("Torrentio"))
            .withPinToggled(StreamPin.Filter("a"))
        // The page may still list a row it deleted: the deletion wins.
        val edited = pinned.edited(order = listOf(SavedFilterEdit("b", "Atmos")), deleted = setOf("b"))
        assertEquals(listOf("a", "c"), edited.filters.map { it.id })
        assertEquals(listOf(StreamPin.Addon("Torrentio"), StreamPin.Filter("a")), edited.pins)
    }

    @Test
    fun `a filter the phone's list does not name, saved on the TV meanwhile, is kept after the listed ones`() {
        val edited = three.edited(order = listOf(SavedFilterEdit("b", "Atmos"), SavedFilterEdit("a", "Remux")), deleted = emptySet())
        assertEquals(listOf("b" to "Atmos", "a" to "Remux", "c" to "1080p remux"), edited.filters.map { it.id to it.name })
    }

    @Test
    fun `the phone's list cannot add a filter, repeat one or blank a name`() {
        val edited = three.edited(
            order = listOf(
                SavedFilterEdit("zz", "Not saved here"),
                SavedFilterEdit("b", "\u0000 \n"),
                SavedFilterEdit("b", "Second mention"),
                SavedFilterEdit("a")
            ),
            deleted = setOf("nope")
        )
        assertEquals(listOf("b" to "Atmos", "a" to "4K DV remux", "c" to "1080p remux"), edited.filters.map { it.id to it.name })
        assertSame(three, three.edited(order = three.filters.map { SavedFilterEdit(it.id, it.name) }, deleted = emptySet()))
        assertSame(three, three.edited(order = emptyList(), deleted = emptySet()))
    }

    @Test
    fun `a name is one line of words within its cap, or none`() {
        assertEquals("Movie night", savedFilterName("  Movie\tnight\r\n"))
        assertEquals("a b", savedFilterName("a\u0000\u001Bb"))
        assertNull(savedFilterName(" \n\u0007 "))
        assertEquals(SavedStreamFilters.MAX_NAME_LENGTH, savedFilterName("x".repeat(500))!!.length)
        // An emoji straddling the cap is dropped whole, and no space is left at the end.
        val straddling = "x".repeat(SavedStreamFilters.MAX_NAME_LENGTH - 1) + "\uD83C\uDFAC"
        assertEquals("x".repeat(SavedStreamFilters.MAX_NAME_LENGTH - 1), savedFilterName(straddling))
        assertEquals("x".repeat(SavedStreamFilters.MAX_NAME_LENGTH - 1), savedFilterName("x".repeat(SavedStreamFilters.MAX_NAME_LENGTH - 1) + " tail"))
    }
}
