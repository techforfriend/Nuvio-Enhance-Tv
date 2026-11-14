package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.nuvio.tv.core.player.findCurrentStreamIndex

/**
 * Window NOTE N6 (2026-10-09): the in-player Sources panel opens on the playing source; the Stream
 * screen on the first result. Spec 51 P6: where focus goes when a re-derive drops the focused row,
 * and the F-5 restore by stream key.
 */
class SourcePanelInitialFocusTest {

    private val streams = listOf(
        stream(addon = "A", url = "https://a/1"),
        stream(addon = "B", url = "https://b/2"),
        stream(addon = "B", url = "https://b/3", infoHash = "ABCDEF", fileIdx = 4)
    )

    @Test
    fun `opens on the playing source matched by addon and url`() {
        val current = findCurrentStreamIndex(streams, null, null, "B", "https://b/2")
        assertEquals(1, sourcePanelInitialFocusIndex(current, focusMarkedRow = true))
    }

    @Test
    fun `info hash and file index win over url`() {
        val current = findCurrentStreamIndex(streams, "abcdef", 4, "B", "https://a/1")
        assertEquals(2, sourcePanelInitialFocusIndex(current, focusMarkedRow = true))
    }

    @Test
    fun `url alone matches when the addon name differs`() {
        val current = findCurrentStreamIndex(streams, null, null, "Other", "https://b/2")
        assertEquals(1, sourcePanelInitialFocusIndex(current, focusMarkedRow = true))
    }

    @Test
    fun `falls back to the first row when the playing source is not listed`() {
        val current = findCurrentStreamIndex(streams, null, null, "C", "https://c/9")
        assertEquals(-1, current)
        assertEquals(0, sourcePanelInitialFocusIndex(current, focusMarkedRow = true))
    }

    @Test
    fun `the Stream screen focuses the first result even with a marked row`() {
        assertEquals(0, sourcePanelInitialFocusIndex(markedIndex = 2, focusMarkedRow = false))
        assertEquals(0, sourcePanelInitialFocusIndex(markedIndex = -1, focusMarkedRow = false))
    }

    @Test
    fun `an empty list has no playing source`() {
        assertEquals(-1, findCurrentStreamIndex(emptyList(), null, null, "A", "https://a/1"))
    }

    @Test
    fun `a re-derive that drops the focused row under the same selection focuses the view's first row`() {
        assertEquals(
            "k0",
            rowFocusAfterRederive("gone", StreamListSelection.All, StreamListSelection.All, listOf("k0", "k1", "k2"))
        )
    }

    @Test
    fun `a re-derive that keeps the focused row leaves focus alone`() {
        assertNull(rowFocusAfterRederive("k1", StreamListSelection.All, StreamListSelection.All, listOf("k0", "k1")))
    }

    @Test
    fun `Left or Right on a row changes the selection, so focus stays with the chip`() {
        assertNull(
            rowFocusAfterRederive("k1", StreamListSelection.All, StreamListSelection.Hidden, listOf("h0", "h1"))
        )
    }

    @Test
    fun `no focused row, or no row left, requests nothing`() {
        assertNull(rowFocusAfterRederive(null, StreamListSelection.All, StreamListSelection.All, listOf("k0")))
        assertNull(rowFocusAfterRederive("k1", StreamListSelection.All, StreamListSelection.All, emptyList()))
        assertNull(rowFocusAfterRederive("k1", null, StreamListSelection.All, listOf("k0")))
    }

    @Test
    fun `a rescue cut short by new rows hands the new first row the focus`() {
        // Focus already left with the dropped row, so nothing reads as focused any more.
        assertEquals(
            "n0",
            rowFocusAfterRederive(null, StreamListSelection.All, StreamListSelection.All, listOf("n0"), rescuePending = true)
        )
    }

    @Test
    fun `a pending rescue yields to the user's own move`() {
        // A pick in a sheet changed the selection meanwhile.
        assertNull(
            rowFocusAfterRederive(null, StreamListSelection.All, StreamListSelection.Hidden, listOf("h0"), rescuePending = true)
        )
        // The user focused a row that the new rows keep.
        assertNull(
            rowFocusAfterRederive("k1", StreamListSelection.All, StreamListSelection.All, listOf("k0", "k1"), rescuePending = true)
        )
    }

    @Test
    fun `F-5 restores the played stream by key after the list reorders`() {
        val played = streams[2]
        assertEquals(0, restoreRowIndex(listOf(played, streams[0]), played.limitKey()))
    }

    @Test
    fun `F-5 tells twin copies of one torrent file apart`() {
        val rd = stream(addon = "A", url = "https://rd/x", infoHash = "abc")
        val pm = stream(addon = "A", url = "https://pm/x", infoHash = "abc")
        assertEquals(1, restoreRowIndex(listOf(rd, pm), pm.limitKey()))
    }

    @Test
    fun `F-5 finds nothing when the played stream is now hidden or no key was kept`() {
        assertNull(restoreRowIndex(streams.take(2), streams[2].limitKey()))
        assertNull(restoreRowIndex(streams, null))
    }

    private fun stream(addon: String, url: String, infoHash: String? = null, fileIdx: Int? = null) = Stream(
        name = addon,
        title = url,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = null,
        addonName = addon,
        addonLogo = null
    )
}
