package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.source.ChannelPack
import com.nuvio.tv.livetv.source.PackChannel
import com.nuvio.tv.livetv.source.PackScanRecord
import com.nuvio.tv.livetv.source.PackScanResult

/** One row of the scan's country list (Spec 73 stage 3): [country] null = No country listed. */
data class ScanCountry(val country: String?, val channels: Int)

/**
 * What the pack's scan UI needs from the stored pack and scans (Spec 73 S3b): [thisTv] = this TV's
 * country row (from the time zone), null when the zone gives none or the pack has no channel there
 * (D12 Q7); [countries] = every other country by channel count, No country listed last;
 * [channels] / [countryCount] = the pack's totals (Whole world); [waiting] = channels Live TV does
 * not list that no scan has tried (D1: they wait for a scan of their country); [last] = the last
 * scan's record.
 */
data class PackScanFacts(
    val thisTv: ScanCountry?,
    val countries: List<ScanCountry>,
    val channels: Int,
    val countryCount: Int,
    val waiting: Int,
    val last: PackScanRecord?
) {
    /** The keys a Whole world scan covers: every country of the pack, No country listed included. */
    val world: Set<String?> get() = (listOfNotNull(thisTv) + countries).mapTo(HashSet()) { it.country }

    fun channelsIn(selected: Collection<String?>): Int =
        (listOfNotNull(thisTv) + countries).filter { it.country in selected }.sumOf { it.channels }
}

/**
 * How a scan's countries are named (Spec 73 S3b, D12 Q1): one country by its name, 2 or more by
 * their number, every one of the pack's as the whole world, No country listed alone as channels
 * with no country.
 */
sealed interface ScanName {
    data class One(val country: String) : ScanName
    data object NoCountry : ScanName
    data class Several(val count: Int) : ScanName
    data object World : ScanName
}

/** Pure derivations of the pack's scan UI (Spec 73 S3b, stages 3-9). */
object PackScanModel {

    /**
     * A scan's pace and data per channel (Spec 73 D17 Q2, from the Shield re-measure 2026-11-12 after
     * D15 Q3's per-request host slots: Canada ~610/min and 14 KB, Whole world 575/min and 32 KB).
     */
    const val CHANNELS_PER_MINUTE = 550
    const val KB_PER_CHANNEL = 32

    /** Below this many checked channels the scan's own pace is not yet a fair guess (D12 Q5). */
    const val PACE_MIN_CHECKED = 20

    /**
     * One [PackChannel] list read into [PackScanFacts]; [thisTvRegion] is the time zone's region
     * (ISO, any case). Null for an empty pack: its file is not written yet (an add stores the
     * record first) or unreadable, and the country list waits rather than opening empty (S3c).
     */
    fun facts(
        pack: List<PackChannel>,
        results: Map<String, PackScanResult>,
        last: PackScanRecord?,
        thisTvRegion: String?
    ): PackScanFacts? {
        if (pack.isEmpty()) return null
        val byCountry = pack.groupingBy { it.country }.eachCount()
        val here = ChannelPack.country(thisTvRegion)?.let { code -> byCountry[code]?.let { ScanCountry(code, it) } }
        val rest = (if (here == null) byCountry else byCountry.filterKeys { it != here.country })
            .map { (country, n) -> ScanCountry(country, n) }
            .sortedWith(compareBy<ScanCountry> { it.country == null }.thenByDescending { it.channels }.thenBy { it.country })
        val waiting = pack.count { ch ->
            ChannelPack.visibleStreams(ch, results).isEmpty() &&
                ch.streams.none { ChannelPack.scanKey(it.url) in results }
        }
        return PackScanFacts(
            thisTv = here,
            countries = rest,
            channels = pack.size,
            countryCount = byCountry.keys.count { it != null },
            waiting = waiting,
            last = last
        )
    }

    /** [countries] of a scan named per [ScanName]; [world] = the pack's every country key. */
    fun name(countries: Collection<String?>, world: Set<String?>): ScanName = when {
        countries.size > 1 && world.isNotEmpty() && countries.toSet() == world -> ScanName.World
        countries.size > 1 -> ScanName.Several(countries.size)
        countries.singleOrNull() == null -> ScanName.NoCountry
        else -> ScanName.One(countries.single()!!)
    }

    /** About how many minutes a scan of [channels] takes (at least 1). */
    fun estimateMinutes(channels: Int): Int = maxOf(1, (channels + CHANNELS_PER_MINUTE - 1) / CHANNELS_PER_MINUTE)

    /** About how many MB a scan of [channels] downloads (at least 1). */
    fun estimateMb(channels: Int): Int = maxOf(1, (channels.toLong() * KB_PER_CHANNEL / 1000).toInt())

    /** Percent of [record]'s channels checked, 0-100. */
    fun percent(record: PackScanRecord): Int =
        if (record.channels <= 0) 0 else (record.checked * 100L / record.channels).toInt().coerceIn(0, 100)

    /**
     * Minutes left at this scan's own pace so far (D12 Q5), over its unpaused time ([pausedMs]
     * spent paused: S3c): null until [PACE_MIN_CHECKED] channels are checked; 0 = less than a minute.
     */
    fun minutesLeft(record: PackScanRecord, nowMs: Long, pausedMs: Long): Int? {
        if (record.checked < PACE_MIN_CHECKED) return null
        val elapsed = (nowMs - record.startedMs - pausedMs).coerceAtLeast(0)
        val leftMs = elapsed * (record.channels - record.checked).coerceAtLeast(0) / record.checked
        return (leftMs / MINUTE_MS).toInt()
    }

    private const val MINUTE_MS = 60_000L
}
