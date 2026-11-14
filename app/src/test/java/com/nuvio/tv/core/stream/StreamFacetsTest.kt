package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P1 (D12, D17): facet values from release names, the filter, its counts, and the list under it. */
class StreamFacetsTest {

    private fun stream(file: String, addon: String = "AIOStreams") = Stream(
        name = "[RD+] $addon", title = null, description = file, url = "https://host/${file.hashCode()}.mkv",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = addon, addonLogo = null
    )

    private val dvRemux = stream("Tears.of.Steel.2012.Hybrid.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.7.1.Atmos-BiTOR")
    private val hdrRemux = stream("Tears.of.Steel.2012.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-HD.MA.5.1-EPSiLON")
    private val dvWeb = stream("Tears of Steel 2012 2160p WEB-DL DDP5.1 Atmos DV HEVC-NTb", addon = "Torrentio")
    private val av1Web = stream("Tears.of.Steel.2012.2160p.WEB-DL.DDP5.1.HDR10.AV1-Rapta")
    private val hdWeb = stream("Tears.of.Steel.2012.1080p.WEB-DL.DD5.1.H.264-GalaxyRG")
    private val bare = stream("Tears of Steel")
    private val all = listOf(dvRemux, hdrRemux, dvWeb, av1Web, hdWeb, bare)

    private fun on(vararg values: Pair<FacetGroup, String>) =
        values.fold(FacetFilter.NONE) { filter, (group, key) -> filter.toggled(FacetValue(group, key)) }

    @Test
    fun `facet values come from the parsed release facts`() {
        val dv = streamFacetValues(dvRemux)
        assertEquals(setOf("P2160"), dv[FacetGroup.RESOLUTION])
        assertEquals(setOf(FacetKeys.DV, FacetKeys.HDR), dv[FacetGroup.HDR])
        assertTrue("ATMOS" in dv.getValue(FacetGroup.AUDIO))
        assertTrue("TRUEHD" in dv.getValue(FacetGroup.AUDIO))
        assertEquals(setOf("BLURAY_REMUX"), dv[FacetGroup.SOURCE])
        assertEquals(setOf("AV1"), streamFacetValues(av1Web)[FacetGroup.CODEC])
        assertEquals(setOf(FacetKeys.SDR), streamFacetValues(hdWeb)[FacetGroup.HDR])
    }

    @Test
    fun `a name that says nothing reads unknown in every group but HDR (D17)`() {
        val v = streamFacetValues(bare)
        assertEquals(setOf(FacetKeys.UNKNOWN), v[FacetGroup.RESOLUTION])
        assertEquals(setOf(FacetKeys.UNKNOWN), v[FacetGroup.SOURCE])
        assertEquals(setOf(FacetKeys.UNKNOWN), v[FacetGroup.CODEC])
        assertEquals(setOf(FacetKeys.SDR), v[FacetGroup.HDR])
    }

    @Test
    fun `OR within a group, AND across groups`() {
        val f = on(FacetGroup.RESOLUTION to "P2160", FacetGroup.HDR to FacetKeys.DV, FacetGroup.HDR to FacetKeys.HDR)
        val kept = all.filter { f.matches(streamFacetValues(it)) }
        assertEquals(listOf(dvRemux, hdrRemux, dvWeb, av1Web), kept)
        val remuxDv = on(FacetGroup.HDR to FacetKeys.DV, FacetGroup.SOURCE to "BLURAY_REMUX")
        assertEquals(listOf(dvRemux), all.filter { remuxDv.matches(streamFacetValues(it)) })
    }

    @Test
    fun `an unknown row is hidden while its group has a value on, and listed when Unknown is on (D17)`() {
        val res = on(FacetGroup.RESOLUTION to "P1080")
        assertFalse(res.matches(streamFacetValues(bare)))
        assertTrue(res.toggled(FacetValue(FacetGroup.RESOLUTION, FacetKeys.UNKNOWN)).matches(streamFacetValues(bare)))
        // a group with nothing on never hides it
        assertTrue(on(FacetGroup.HDR to FacetKeys.SDR).matches(streamFacetValues(bare)))
    }

    @Test
    fun `toggling twice turns a value off and drops an emptied group`() {
        val v = FacetValue(FacetGroup.CODEC, "HEVC")
        val f = FacetFilter.NONE.toggled(v)
        assertEquals(1, f.count)
        assertEquals(FacetFilter.NONE, f.toggled(v))
        assertTrue(f.toggled(v).isEmpty)
    }

    @Test
    fun `a value counts the rows it would list under the other groups' choices`() {
        val f = on(FacetGroup.RESOLUTION to "P2160", FacetGroup.HDR to FacetKeys.DV)
        val opts = facetOptions(all, f, ::streamFacetValues)
        fun count(group: FacetGroup, key: String) = opts.getValue(group).first { it.value.key == key }.count
        // Resolution counts ignore Resolution's own choice: DV rows at 2160p = 2 (dvRemux, dvWeb), at 1080p = 0
        assertEquals(2, count(FacetGroup.RESOLUTION, "P2160"))
        assertEquals(0, count(FacetGroup.RESOLUTION, "P1080"))
        // HDR counts are taken under 2160p: DV 2, HDR 3 (dvRemux, hdrRemux, av1Web)
        assertEquals(2, count(FacetGroup.HDR, FacetKeys.DV))
        assertEquals(3, count(FacetGroup.HDR, FacetKeys.HDR))
        assertTrue(opts.getValue(FacetGroup.HDR).first { it.value.key == FacetKeys.DV }.on)
    }

    @Test
    fun `sheet order puts Atmos first in audio and Unknown last`() {
        val opts = facetOptions(all, FacetFilter.NONE, ::streamFacetValues)
        assertEquals("ATMOS", opts.getValue(FacetGroup.AUDIO).first().value.key)
        assertEquals(FacetKeys.UNKNOWN, opts.getValue(FacetGroup.RESOLUTION).last().value.key)
        assertEquals(listOf("P2160", "P1080", FacetKeys.UNKNOWN), opts.getValue(FacetGroup.RESOLUTION).map { it.value.key })
    }

    @Test
    fun `a group offering one value is left out unless it is on`() {
        val twoHd = listOf(hdWeb, stream("Tears.of.Steel.2012.1080p.BluRay.DTS.x264-DON"))
        val opts = facetOptions(twoHd, FacetFilter.NONE, ::streamFacetValues)
        assertFalse(FacetGroup.RESOLUTION in opts)
        assertTrue(FacetGroup.SOURCE in opts)
        val kept = facetOptions(twoHd, on(FacetGroup.RESOLUTION to "P2160"), ::streamFacetValues)
        // an on value no row has stays offered (count 0) so it can be switched off
        assertEquals(listOf("P2160" to 0, "P1080" to 2), kept.getValue(FacetGroup.RESOLUTION).map { it.value.key to it.count })
    }

    @Test
    fun `the list under a filter narrows All, addon views and their counts, never the Hidden view`() {
        val hidden = mapOf(hdrRemux.limitKey() to HiddenStream(resolution = null, bitrate = null))
        val f = on(FacetGroup.HDR to FacetKeys.DV)
        val view = deriveStreamList(all, StreamListSelection.All, hidden, listOf("AIOStreams", "Torrentio"), filter = f)
        assertEquals(listOf(dvRemux, dvWeb), view.streams)
        assertEquals(2, view.allCount)
        assertEquals(mapOf("AIOStreams" to 1, "Torrentio" to 1), view.addonCounts)
        assertEquals(1, view.hiddenCount)
        assertEquals(3, view.filteredOut) // av1Web, hdWeb, bare
        // The filters sheet offers values over the shown rows only, never a hidden one.
        assertEquals(all - hdrRemux, view.facetBase)
        val hiddenView = deriveStreamList(all, StreamListSelection.Hidden, hidden, listOf("AIOStreams"), filter = f)
        assertEquals(listOf(hdrRemux), hiddenView.streams)
        assertEquals(0, hiddenView.filteredOut)
    }

    @Test
    fun `an HLG-only row is HDR, not SDR`() {
        val hlg = streamFacetValues(stream("Tears.of.Steel.2012.2160p.WEB-DL.HLG.DDP5.1.HEVC-NTb"))
        assertEquals(setOf(FacetKeys.HDR), hlg[FacetGroup.HDR])
    }

    @Test
    fun `an addon view under a filter lists, counts and offers that addon's rows only`() {
        val f = on(FacetGroup.RESOLUTION to "P2160")
        val view = deriveStreamList(
            all, StreamListSelection.Addon("AIOStreams"), emptyMap(), listOf("AIOStreams", "Torrentio"),
            filter = f
        )
        assertEquals(listOf(dvRemux, hdrRemux, av1Web), view.streams)
        assertEquals(2, view.filteredOut) // hdWeb, bare
        assertEquals(mapOf("AIOStreams" to 3, "Torrentio" to 1), view.addonCounts)
        // the sheet counts over AIOStreams' rows: dvWeb (Torrentio) is not a DV row here
        val facets = facetOptions(view.facetBase, view.filter, ::streamFacetValues)
        assertEquals(1, facets.getValue(FacetGroup.HDR).first { it.value.key == FacetKeys.DV }.count)
    }

    @Test
    fun `a view with only hidden rows still lists them under a filter, and offers no facets`() {
        val hidden = mapOf(dvWeb.limitKey() to HiddenStream(resolution = null, bitrate = null))
        val view = deriveStreamList(
            all, StreamListSelection.Addon("Torrentio"), hidden, listOf("AIOStreams", "Torrentio"),
            filter = on(FacetGroup.CODEC to "XVID")
        )
        assertEquals(listOf(dvWeb), view.streams)
        assertTrue(view.listsHidden)
        assertTrue(view.facetBase.isEmpty())
        assertEquals(0, view.filteredOut)
        assertEquals(1, view.addonCounts["Torrentio"])
    }

    @Test
    fun `a filter that empties a view lists nothing rather than its hidden rows`() {
        val hidden = mapOf(hdrRemux.limitKey() to HiddenStream(resolution = null, bitrate = null))
        val view = deriveStreamList(all, StreamListSelection.All, hidden, emptyList(), filter = on(FacetGroup.CODEC to "XVID"))
        assertTrue(view.streams.isEmpty())
        assertFalse(view.listsHidden)
        assertEquals(0, view.allCount)
    }

    @Test
    fun `a derive reads no row without a filter, and each row once under one`() {
        var reads = 0
        val counting: (Stream) -> StreamFacetValues = { reads++; streamFacetValues(it) }
        val plain = deriveStreamList(all, StreamListSelection.All, emptyMap(), emptyList(), facetsOf = counting)
        assertEquals(all, plain.facetBase)
        assertEquals(0, reads)
        val filtered = deriveStreamList(
            all, StreamListSelection.All, emptyMap(), emptyList(),
            filter = on(FacetGroup.HDR to FacetKeys.DV), facetsOf = counting
        )
        // The sheet's base is the view before the filter, whatever is on.
        assertEquals(all, filtered.facetBase)
        assertEquals(all.size, reads)
    }

    @Test
    fun `a value can leave the sheet when it is switched off`() {
        // No row to offer values over: only what is on is listed, so it can be switched off.
        val xvid = on(FacetGroup.CODEC to "XVID")
        assertEquals(listOf("XVID"), facetOptions(emptyList(), xvid, ::streamFacetValues).getValue(FacetGroup.CODEC).map { it.value.key })
        assertTrue(facetOptions(emptyList(), FacetFilter.NONE, ::streamFacetValues).isEmpty())
        // Every row 4K: the group shows only while the value no row has is on.
        val uhd = listOf(dvRemux, hdrRemux)
        assertEquals(2, facetOptions(uhd, on(FacetGroup.RESOLUTION to "P1080"), ::streamFacetValues).getValue(FacetGroup.RESOLUTION).size)
        assertFalse(FacetGroup.RESOLUTION in facetOptions(uhd, FacetFilter.NONE, ::streamFacetValues))
    }

    @Test
    fun `values are named as the sheet shows them, an unknown one by the caller`() {
        fun label(group: FacetGroup, key: String) = facetValueLabel(FacetValue(group, key))
        assertEquals("4K", label(FacetGroup.RESOLUTION, "P2160"))
        assertEquals("1080p", label(FacetGroup.RESOLUTION, "P1080"))
        assertEquals("Dolby Vision", label(FacetGroup.HDR, FacetKeys.DV))
        assertEquals("HDR", label(FacetGroup.HDR, FacetKeys.HDR))
        assertEquals("DTS-HD MA", label(FacetGroup.AUDIO, "DTS_HD_MA"))
        assertEquals("Remux", label(FacetGroup.SOURCE, "BLURAY_REMUX"))
        assertEquals("WEB-DL", label(FacetGroup.SOURCE, "WEB_DL"))
        assertEquals("HEVC", label(FacetGroup.CODEC, "HEVC"))
        assertEquals(null, label(FacetGroup.CODEC, FacetKeys.UNKNOWN))
        assertEquals(null, label(FacetGroup.CODEC, "NOPE"))
        assertEquals(null, label(FacetGroup.HDR, "NOPE"))
        // Every value a real list offers has a name of its own.
        facetOptions(all, FacetFilter.NONE, ::streamFacetValues).values.flatten()
            .filter { it.value.key != FacetKeys.UNKNOWN }
            .forEach { assertTrue("${it.value}", facetValueLabel(it.value) != null) }
    }

    @Test
    fun `the memo reads a row as the parser does, and a rewritten row again`() {
        assertEquals(streamFacetValues(dvRemux), StreamFacetMemo.valuesOf(dvRemux))
        assertEquals(streamFacetValues(dvRemux), StreamFacetMemo.valuesOf(dvRemux))
        // Same URL (the list's limit key), new release text: a badge pass rewrote the row.
        val rewritten = dvRemux.copy(description = "Tears.of.Steel.2012.1080p.WEB-DL.DD5.1.H.264-GalaxyRG")
        assertEquals(setOf("P1080"), StreamFacetMemo.valuesOf(rewritten)[FacetGroup.RESOLUTION])
        assertEquals(setOf("P2160"), StreamFacetMemo.valuesOf(dvRemux)[FacetGroup.RESOLUTION])
    }
}
