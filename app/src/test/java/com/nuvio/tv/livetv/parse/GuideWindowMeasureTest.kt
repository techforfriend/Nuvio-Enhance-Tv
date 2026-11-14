package com.nuvio.tv.livetv.parse

import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.data.EpgMatcher
import com.nuvio.tv.livetv.data.EpgSource
import com.nuvio.tv.livetv.data.GuideCache
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.source.LiveSourceManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.lang.ref.Reference
import java.time.Instant
import java.time.ZoneId

/**
 * Spec 67 G1a measurement harness, skipped unless `EPG_G1_DIR` names a folder holding real guide
 * files (`*.xml`, `*.xml.gz`; the guide country is guessed from the file name as the app does) and
 * playlists (`*.m3u`). Per guide it parses the app's day window (`LiveSourceManager.dayWindow`) for the channels
 * the playlists match through [EpgMatcher] and for every channel, and reports parse time, retained
 * heap, the D11 JSON cache's size and decode time. The matched snapshot keeps only the matched
 * channels' `<channel>` entries too (the parser keeps every one; a matched-only store would not).
 * Channels are matched without `ChannelEnricher` (iptv-org countries), so name matching is a lower
 * bound. It writes `report.md`, and per guide `rows-<guide>.tsv` (matched window) plus
 * `window.txt` (window start ms) for `scripts/epg-g1-sqlite.py`, the SQLite candidate.
 * Desktop JVM numbers: Shield figures are a separate, on-go measurement.
 *
 * `EPG_G1_DIR=/tmp/epg-g1 gradlew-serial :app:testFullDebugUnitTest --tests '*GuideWindowMeasureTest'`
 */
class GuideWindowMeasureTest {

    private val dayParser = XmltvNowNextParser(newParser = { KXmlParser() }, maxProgrammesPerChannel = MEASURE_CAP)

    @Test
    fun measure() {
        val dir = System.getenv("EPG_G1_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        dir!!
        val guides = dir.listFiles { f -> f.name.endsWith(".xml") || f.name.endsWith(".xml.gz") }!!.sortedBy { it.name }
        val playlists = dir.listFiles { f -> f.name.endsWith(".m3u") }!!.sortedBy { it.name }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val (windowStart, windowEnd) = LiveSourceManager.dayWindow(now, zone)
        File(dir, "window.txt").writeText("$windowStart\n")
        // The SQLite script reads every rows-*.tsv: drop an earlier run's files first.
        dir.listFiles { f -> f.name.startsWith("rows-") && f.name.endsWith(".tsv") }!!.forEach { it.delete() }

        val (channelCount, refs) = match(guides, playlists, windowStart, windowEnd)

        val report = StringBuilder()
        report.appendLine("# Spec 67 G1a desktop measurement")
        report.appendLine()
        report.appendLine("- now ${Instant.ofEpochMilli(now)}, window [${Instant.ofEpochMilli(windowStart)}, ${Instant.ofEpochMilli(windowEnd)}), zone $zone")
        report.appendLine("- playlists: ${playlists.joinToString { it.name }}; $channelCount channels, ${refs.size} matched")
        report.appendLine("- JVM ${System.getProperty("java.version")}, max heap ${Runtime.getRuntime().maxMemory() shr 20} MiB")
        report.appendLine()
        report.appendLine("| guide | set | channel entries | with programmes | programmes | max/channel | parse ms (1st / best of 3) | retained heap MiB | JSON KiB | JSON decode ms (best of 3) |")
        report.appendLine("|---|---|---|---|---|---|---|---|---|---|")

        for (g in guides) {
            val matched = refs.values.filter { it.epgSourceId == g.name }.map { it.epgChannelId }.toSet()
            for (set in listOf("matched", "all")) {
                val only = matched.takeIf { set == "matched" }
                val times = (1..3).map { timedParse(g, windowStart, windowEnd, only) }
                val heap = retainedHeap { parse(g, windowStart, windowEnd, only) }
                val s = parse(g, windowStart, windowEnd, only)
                report.append(row(g, set, s, times, heap, dir))
                if (set == "matched") writeTsv(File(dir, "rows-${stem(g)}.tsv"), s)
            }
        }
        File(dir, "report.md").writeText(report.toString())
        println(report)
    }

    /**
     * Channel count and matches, as the app derives them since Spec 67 G1c: day-window snapshots of every
     * guide against every playlist channel. Its own function so none of the snapshots is reachable when heap is sampled later.
     */
    private fun match(guides: List<File>, playlists: List<File>, start: Long, end: Long): Pair<Int, Map<String, EpgRef>> {
        val sources = guides.map { g ->
            val country = LiveSourceManager.guessCountry("https://local/${g.name}".toHttpUrl())
            EpgSource(g.name, country, parse(g, start, end, null))
        }
        val channels = playlists.flatMap { p ->
            ChannelIdentity.channelsFromM3u(p.name, p.reader().use { M3uParser.parse(it) })
        }
        return channels.size to EpgMatcher.match(channels, sources, emptyMap())
    }

    /** The window snapshot; with [only], just those channels' programmes and `<channel>` entries. */
    private fun parse(g: File, start: Long, end: Long, only: Set<String>?): EpgSnapshot {
        val s = g.inputStream().use { dayParser.parse(it, start, end - start) { id -> only == null || id in only } }
        return if (only != null) s.copy(channels = s.channels.filterKeys { it in only }) else s
    }

    private fun timedParse(g: File, start: Long, end: Long, only: Set<String>?): Long {
        val t0 = System.nanoTime()
        g.inputStream().use { dayParser.parse(it, start, end - start) { id -> only == null || id in only } }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** Heap the value [make] returns keeps alive: nothing else from the run is reachable when sampled. */
    private fun retainedHeap(make: () -> Any): Long {
        val before = usedHeap()
        val held = make()
        val after = usedHeap()
        Reference.reachabilityFence(held)
        return after - before
    }

    private fun row(g: File, set: String, s: EpgSnapshot, times: List<Long>, heap: Long, dir: File): String {
        val lists = s.programmes.values
        val json = File(dir, "cache-$set-${stem(g)}.json")
        json.writeText(localStoreJson.encodeToString(GuideCache.serializer(), GuideCache(snapshot = s)))
        val decode = (1..3).minOf {
            val t0 = System.nanoTime()
            localStoreJson.decodeFromString(GuideCache.serializer(), json.readText())
            (System.nanoTime() - t0) / 1_000_000
        }
        return "| ${g.name} | $set | ${s.channels.size} | ${lists.size} | ${lists.sumOf { it.size }} | ${lists.maxOfOrNull { it.size } ?: 0} | " +
            "${times.first()} / ${times.min()} | ${"%.1f".format(heap / 1048576.0)} | ${json.length() / 1024} | $decode |\n"
    }

    private fun writeTsv(file: File, s: EpgSnapshot) {
        file.bufferedWriter().use { w ->
            for ((channel, list) in s.programmes) for (p in list) {
                w.write("${clean(channel)}\t${p.startMs}\t${p.stopMs}\t${clean(p.title)}\t${clean(p.category.orEmpty())}\t${if (p.live) 1 else 0}\n")
            }
        }
    }

    /** The whole file name, so `x.xml` and `x.xml.gz` keep distinct output names. */
    private fun stem(g: File) = g.name

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]"), " ")

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    private companion object {
        /** Far above any real per-channel count, so the report shows the true maximum. */
        const val MEASURE_CAP = 5_000
    }
}
