package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.FacetGroup
import com.nuvio.tv.core.stream.FacetKeys
import com.nuvio.tv.core.stream.FacetValue
import com.nuvio.tv.core.stream.HiddenStream
import com.nuvio.tv.core.stream.ResolutionVerdict
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.core.stream.StreamLimitVerdict
import com.nuvio.tv.core.stream.StreamListSelection.Addon
import com.nuvio.tv.core.stream.StreamListSelection.All
import com.nuvio.tv.core.stream.StreamListSelection.Hidden
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.core.stream.StreamPin
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sources panel's row of circles, its sheets' rows and its eyebrow (Spec 51 P6, Spec 65 P1). */
class SourcesPanelRowTest {

    private val addons = listOf("AIOStreams", "Torrentio", "Comet")

    private fun filter(vararg values: Pair<FacetGroup, String>) =
        values.fold(FacetFilter.NONE) { filter, (group, key) -> filter.toggled(FacetValue(group, key)) }

    @Test
    fun `the sources circle spins while anything is asked, then marks a failed addon, else nothing`() {
        val answered = listOf(SourceChipItem("AIOStreams", SourceChipStatus.SUCCESS))
        val asking = answered + SourceChipItem("Comet", SourceChipStatus.LOADING)
        val failed = answered + SourceChipItem("Comet", SourceChipStatus.ERROR)
        assertEquals(SourcesCircleMark.ASKING, sourcesCircleMark(isLoading = true, sourceChips = emptyList()))
        assertEquals(SourcesCircleMark.ASKING, sourcesCircleMark(isLoading = false, sourceChips = asking))
        // Still asking another addon: the spinner wins over the failed mark.
        assertEquals(SourcesCircleMark.ASKING, sourcesCircleMark(isLoading = false, sourceChips = asking + failed))
        assertEquals(SourcesCircleMark.FAILED, sourcesCircleMark(isLoading = false, sourceChips = failed))
        assertEquals(SourcesCircleMark.NONE, sourcesCircleMark(isLoading = false, sourceChips = answered))
    }

    @Test
    fun `the sources sheet lists All then each addon with its count, the shown view selected`() {
        val view = StreamListView(
            selection = Addon("Torrentio"),
            allCount = 5,
            addonCounts = mapOf("AIOStreams" to 3, "Torrentio" to 2)
        )
        val chips = listOf(
            SourceChipItem("AIOStreams", SourceChipStatus.SUCCESS),
            SourceChipItem("Torrentio", SourceChipStatus.SUCCESS)
        )
        val rows = sourcesSheetRows(addons.take(2), addons.take(2), chips, view)
        assertEquals(listOf(All, Addon("AIOStreams"), Addon("Torrentio")), rows.map { it.selection })
        assertEquals(listOf(5, 3, 2), rows.map { it.count })
        assertEquals(listOf(false, false, true), rows.map { it.selected })
        assertTrue(rows.all { it.selectable })
    }

    @Test
    fun `an addon still asked or without streams is listed but cannot be picked`() {
        val chips = listOf(
            SourceChipItem("AIOStreams", SourceChipStatus.SUCCESS),
            SourceChipItem("Torrentio", SourceChipStatus.LOADING),
            SourceChipItem("Comet", SourceChipStatus.ERROR)
        )
        // Torrentio is asked again over the streams it gave before (a resumed load): no count yet.
        val view = StreamListView(allCount = 5, addonCounts = mapOf("AIOStreams" to 3, "Torrentio" to 2))
        val rows = sourcesSheetRows(addons, listOf("AIOStreams", "Torrentio"), chips, view)
        assertEquals(listOf(true, true, false, false), rows.map { it.selectable })
        assertEquals(listOf(5, 3, null, null), rows.map { it.count })
        // A SUCCESS chip is not enough either: the addon must be among those with streams.
        val bare = sourcesSheetRows(listOf("Comet"), emptyList(), listOf(SourceChipItem("Comet", SourceChipStatus.SUCCESS)), view)
        assertFalse(bare.last().selectable)
        assertEquals(
            listOf(SourceChipStatus.SUCCESS, SourceChipStatus.SUCCESS, SourceChipStatus.LOADING, SourceChipStatus.ERROR),
            rows.map { it.status }
        )
    }

    @Test
    fun `no sheet row is selected while the hidden view shows`() {
        val rows = sourcesSheetRows(addons.take(1), addons.take(1), emptyList(), StreamListView(selection = Hidden))
        assertFalse(rows.any { it.selected })
    }

    @Test
    fun `the hidden sheet has one entry per limit, a stream over both counted in each`() {
        val bitrate = verdict()
        val resolution = ResolutionVerdict(cap = ReleaseResolution.P1080, fromTv = false)
        val view = StreamListView(
            hiddenVerdicts = mapOf(
                "a" to HiddenStream(bitrate = bitrate),
                "b" to HiddenStream(bitrate = bitrate, resolution = resolution),
                "c" to HiddenStream(resolution = resolution)
            )
        )
        assertEquals(
            listOf(HiddenSheetEntry(HiddenReason.BITRATE, 2), HiddenSheetEntry(HiddenReason.RESOLUTION, 2)),
            hiddenSheetEntries(view)
        )
        val onlyBitrate = StreamListView(hiddenVerdicts = mapOf("a" to HiddenStream(bitrate = bitrate)))
        assertEquals(listOf(HiddenSheetEntry(HiddenReason.BITRATE, 1)), hiddenSheetEntries(onlyBitrate))
        assertEquals(emptyList<HiddenSheetEntry>(), hiddenSheetEntries(StreamListView()))
    }

    @Test
    fun `the eyebrow names a filter by its values in the sheet's order`() {
        // Switched on out of order: the label follows the groups, then each group's own order.
        val f = filter(
            FacetGroup.AUDIO to "ATMOS",
            FacetGroup.SOURCE to "BLURAY_REMUX",
            FacetGroup.HDR to FacetKeys.DV,
            FacetGroup.RESOLUTION to "P1080",
            FacetGroup.RESOLUTION to "P2160"
        )
        assertEquals("4K · 1080p · Dolby Vision · Atmos · Remux", facetFilterLabel(f, "Unknown"))
        assertEquals("Unknown", facetFilterLabel(filter(FacetGroup.CODEC to FacetKeys.UNKNOWN), "Unknown"))
        assertNull(facetFilterLabel(FacetFilter.NONE, "Unknown"))
    }

    @Test
    fun `the eyebrow counts shown of total while streams are hidden, all of them when none is`() {
        assertEquals(19 to 23, eyebrowShownOfTotal(StreamListView(hiddenCount = 4, total = 23)))
        // A view listing only hidden streams still reads as nothing shown.
        assertEquals(0 to 23, eyebrowShownOfTotal(StreamListView(hiddenCount = 23, allCount = 23, total = 23)))
        assertNull(eyebrowShownOfTotal(StreamListView(allCount = 23, total = 23)))
    }

    @Test
    fun `under a filter the eyebrow counts All's filtered rows of every stream`() {
        val f = filter(FacetGroup.RESOLUTION to "P2160")
        assertEquals(4 to 16, eyebrowShownOfTotal(StreamListView(allCount = 4, hiddenCount = 2, total = 16, filter = f)))
        assertEquals(0 to 14, eyebrowShownOfTotal(StreamListView(allCount = 0, total = 14, filter = f)))
        // Every stream hidden: allCount counts the hidden rows (the chip-count rule), none is shown.
        assertEquals(0 to 5, eyebrowShownOfTotal(StreamListView(allCount = 5, hiddenCount = 5, total = 5, filter = f)))
    }

    @Test
    fun `a hidden row's bitrate rounds up, so it never reads as the figure it is over`() {
        assertEquals(134, overLimitMbpsLabel(verdict(figure = 133, estimate = 133.2)))
        assertEquals(109, overLimitMbpsLabel(verdict(figure = 70, estimate = 109.0)))
    }

    private fun verdict(
        figure: Int = 60,
        estimate: Double = figure + 10.0
    ) = StreamLimitVerdict(estimateMbps = estimate, figureMbps = figure, source = null)

    private val savedPins = SavedStreamFilters.NONE
        .withSaved("uhd", "4K", filter(FacetGroup.RESOLUTION to ReleaseResolution.P2160.name))
        .withSaved("atmos", "Atmos", filter(FacetGroup.AUDIO to "ATMOS"))
        .withPinToggled(StreamPin.Addon("Torrentio"))
        .withPinToggled(StreamPin.Filter("atmos"))
        .withPinToggled(StreamPin.Addon("Comet"))

    @Test
    fun `pinned chips follow the pin order, an addon with its count, one without streams left out`() {
        val view = StreamListView(allCount = 5, addonCounts = mapOf("AIOStreams" to 3, "Torrentio" to 2))
        val chips = pinnedChips(savedPins, view, availableAddons = listOf("AIOStreams", "Torrentio"))
        assertEquals(listOf(StreamPin.Addon("Torrentio"), StreamPin.Filter("atmos")), chips.map { it.pin })
        assertEquals(2, (chips[0] as PinnedChip.Addon).count)
        assertEquals("Atmos", (chips[1] as PinnedChip.Filter).saved.name)
        assertTrue(chips.none { it.active })
        // A saved filter that is not pinned (4K) has no chip.
        assertTrue(chips.none { it is PinnedChip.Filter && it.saved.id == "uhd" })
    }

    @Test
    fun `a pinned filter is active while exactly its values are on, a pinned addon while its view shows`() {
        val atmosOn = StreamListView(selection = Addon("Torrentio"), filter = filter(FacetGroup.AUDIO to "ATMOS"))
        assertEquals(listOf(true, true), pinnedChips(savedPins, atmosOn, addons).take(2).map { it.active })
        val more = StreamListView(
            selection = Addon("Comet"),
            filter = filter(FacetGroup.AUDIO to "ATMOS", FacetGroup.RESOLUTION to ReleaseResolution.P2160.name)
        )
        assertEquals(listOf(false, false, true), pinnedChips(savedPins, more, addons).map { it.active })
    }

    @Test
    fun `OK on a pinned chip puts it in effect, and on the one in effect undoes it`() {
        val atmos = savedPins.filters.first { it.id == "atmos" }
        assertEquals(atmos.filter, PinnedChip.Filter(atmos, active = false).filterOnClick)
        assertEquals(FacetFilter.NONE, PinnedChip.Filter(atmos, active = true).filterOnClick)
        assertEquals(Addon("Comet"), PinnedChip.Addon("Comet", count = 1, active = false).selectionOnClick)
        assertEquals(All, PinnedChip.Addon("Comet", count = 1, active = true).selectionOnClick)
    }

    @Test
    fun `a pin whose saved filter is gone has no chip`() {
        val orphan = SavedStreamFilters(pins = listOf(StreamPin.Filter("gone"), StreamPin.Addon("Comet")))
        assertEquals(listOf(StreamPin.Addon("Comet")), pinnedChips(orphan, StreamListView(), addons).map { it.pin })
    }

    @Test
    fun `no pins, no chips`() {
        assertTrue(pinnedChips(SavedStreamFilters.NONE, StreamListView(), addons).isEmpty())
    }
}
