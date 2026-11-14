package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.source.ChannelPack
import com.nuvio.tv.livetv.source.PackChannel
import com.nuvio.tv.livetv.source.PackScanRecord
import com.nuvio.tv.livetv.source.PackScanResult
import com.nuvio.tv.livetv.source.PackState
import com.nuvio.tv.livetv.source.PackStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [PackScanModel] (Spec 73 S3b): the country list, waiting count, naming, estimates, time left. */
class PackScanModelTest {

    private fun ch(key: String, country: String?, state: PackState = PackState.ALIVE) =
        PackChannel(key = key, name = key, country = country, streams = listOf(PackStream("https://x/$key", state)))

    private val pack = listOf(
        ch("a", "ca"), ch("b", "ca", PackState.BLOCKED), ch("c", "us"), ch("d", "us"), ch("e", "us"),
        ch("f", null, PackState.SUSPECT), ch("g", "uk")
    )

    @Test
    fun `this TV first, then countries by channel count, no country last, and what waits`() {
        val results = mapOf(ChannelPack.scanKey("https://x/b") to PackScanResult(false, 1))
        val facts = PackScanModel.facts(pack, results, null, "GB")!!
        assertEquals(ScanCountry("uk", 1), facts.thisTv)
        assertEquals(listOf(ScanCountry("us", 3), ScanCountry("ca", 2), ScanCountry(null, 1)), facts.countries)
        assertEquals(7, facts.channels)
        assertEquals(3, facts.countryCount)
        // b failed a scan (hidden, not waiting); f has only a suspect row and no result.
        assertEquals(1, facts.waiting)
        // D12 Q7: no THIS TV; every country listed, No country listed last.
        val noZone = PackScanModel.facts(pack, emptyMap(), null, null)!!
        assertNull(noZone.thisTv)
        assertEquals(listOf("us", "ca", "uk", null), noZone.countries.map { it.country })
        assertNull(PackScanModel.facts(pack, emptyMap(), null, "FR")!!.thisTv)
        // No pack file yet (an add stores the record first): no facts, the country list waits (S3c).
        assertNull(PackScanModel.facts(emptyList(), emptyMap(), null, "GB"))
    }

    @Test
    fun `a scan is named by its country, its count, or the whole world`() {
        val world = PackScanModel.facts(pack, emptyMap(), null, null)!!.world
        assertEquals(ScanName.One("ca"), PackScanModel.name(listOf("ca"), world))
        assertEquals(ScanName.NoCountry, PackScanModel.name(listOf(null), world))
        assertEquals(ScanName.Several(2), PackScanModel.name(listOf("ca", null), world))
        assertEquals(ScanName.World, PackScanModel.name(world, world))
    }

    @Test
    fun `estimates and time left`() {
        assertEquals(2, PackScanModel.estimateMinutes(911)) // 550 a minute (D17 Q2)
        assertEquals(38, PackScanModel.estimateMinutes(20_886))
        assertEquals(1, PackScanModel.estimateMinutes(1))
        assertEquals(668, PackScanModel.estimateMb(20_886)) // 32 KB a channel
        val record = PackScanRecord(listOf("ca"), startedMs = 0, channels = 100, checked = 50)
        assertEquals(50, PackScanModel.percent(record))
        assertEquals(2, PackScanModel.minutesLeft(record, nowMs = 150_000, pausedMs = 0))
        assertNull(PackScanModel.minutesLeft(record.copy(checked = 19), nowMs = 150_000, pausedMs = 0))
        // From PACE_MIN_CHECKED on: 20 checked in 150 s, 80 left = 600 s.
        assertEquals(10, PackScanModel.minutesLeft(record.copy(checked = 20), nowMs = 150_000, pausedMs = 0))
        assertEquals(0, PackScanModel.minutesLeft(record.copy(checked = 99), nowMs = 150_000, pausedMs = 0))
        // 90 s of the 150 paused: 20 checked in 60 s, 80 left = 240 s.
        assertEquals(4, PackScanModel.minutesLeft(record.copy(checked = 20), nowMs = 150_000, pausedMs = 90_000))
        assertEquals(0, PackScanModel.percent(record.copy(channels = 0, checked = 0)))
    }
}
