package com.nuvio.tv.livetv.source

import android.content.Context
import android.util.Log
import android.util.Xml
import com.nuvio.tv.livetv.data.ChannelEnricher
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.EpgMatcher
import com.nuvio.tv.livetv.data.EpgSource
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.GuideService
import com.nuvio.tv.livetv.data.localDayStartMs
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.M3uParser
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds, refreshes and removes Live TV sources (Spec 34 L5): fetches through [SourceFetcher],
 * checks the detected kind, parses, and saves the result: a playlist's channels replace the
 * source's block in [ChannelRepository]; a guide's snapshot of today and tomorrow ([dayWindow],
 * Spec 67 G1c) goes to [GuideRepository].
 * A failed refresh (network, HTTP status, cap, wrong content, empty or cut-short file) keeps the
 * previous data and records the reason on the source. The broadcaster preset (D3, Q7) is an M3U
 * source with the fixed id [PRESET_ID] whose playlist is the bundled asset [PRESET_ASSET]. The
 * guide presets ([GUIDE_PRESETS]: services and countries, Spec 55 S2) are Sources' suggestions
 * ([addPresetGuides]). The channel pack (Spec 73 S2b) is a [SourceKind.PACK] source with the fixed
 * id [ChannelPack.SOURCE_ID]: two files from the public list repo ([fetchPack]), kept whole in
 * [PackStore] and listed through [ChannelPack.listing], with the results of [PackScanner]'s scans
 * from this TV ([PackScanStore], [applyScan], Spec 73 S3a). A
 * playlist's own header guides are probed at each check and refresh and kept on its record
 * ([LiveSource.headerGuides], Spec 55 S1); Sources offers them ([addHeaderGuides]).
 *
 * A Stremio `tv` addon (L5d, O2) is a source whose manifest URL is fetched, checked and expanded
 * into its catalog channels by [StremioTvAdapter]; their streams resolve at play time
 * ([streamsFor]). One mutex serialises every operation on one source, so a removal never races a
 * refresh that would re-add the removed source's data. It is held for the whole fetch: one call
 * ([SourceFetcher.CALL_TIMEOUT_MINUTES] each) for a playlist or guide (a playlist's header guides'
 * probes after it, [PROBE_PARALLEL] at a time, [SourceFetcher.PROBE_TIMEOUT_SECONDS] each), up to
 * 1 + catalogs x [StremioTvAdapter.MAX_PAGES] calls for an addon. Suspend
 * functions are main-safe: the stores and the fetcher move their own work to IO, the preset read
 * runs on [io]. File-write failures are reported (refused add, `lastError`, false from [remove]),
 * never thrown.
 */
@Singleton
class LiveSourceManager internal constructor(
    private val store: SourceStore,
    private val channels: ChannelRepository,
    private val guides: GuideRepository,
    private val metadata: IptvMetadataRepository,
    private val fetcher: SourceFetcher,
    private val stremio: StremioTvAdapter,
    private val xmltv: XmltvNowNextParser,
    private val openPreset: () -> InputStream,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    private val io: CoroutineDispatcher,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val pack: PackStore,
    private val scans: PackScanStore,
    private val packUrls: PackUrls = PackUrls()
) {
    /** Where the pack's files are fetched from (tests point them at a local server). */
    internal data class PackUrls(
        val state: String = ChannelPack.STATE_URL,
        val streams: String = ChannelPack.STREAMS_URL,
        val summary: String = ChannelPack.SUMMARY_URL
    )

    @Inject
    constructor(
        store: SourceStore,
        channels: ChannelRepository,
        guides: GuideRepository,
        metadata: IptvMetadataRepository,
        fetcher: SourceFetcher,
        stremio: StremioTvAdapter,
        pack: PackStore,
        scans: PackScanStore,
        @ApplicationContext context: Context
    ) : this(
        store,
        channels,
        guides,
        metadata,
        fetcher,
        stremio,
        XmltvNowNextParser(Xml::newPullParser, maxProgrammesPerChannel = DAY_WINDOW_MAX_PROGRAMMES),
        { context.assets.open(PRESET_ASSET) },
        System::currentTimeMillis,
        { UUID.randomUUID().toString() },
        Dispatchers.IO,
        pack = pack,
        scans = scans
    )

    sealed interface AddResult {
        data class Added(val source: LiveSource) : AddResult
        data class Refused(val reason: String) : AddResult
    }

    /**
     * A URL that passed [check], ready for [add]: [source] is the record adding it writes, [listed]
     * the channels the playlist or addon lists or the guide file holds (for a guide,
     * [LiveSource.channelCount] is the channels it matched). Holds the fetched data, so adding
     * never fetches again.
     */
    class Checked internal constructor(val source: LiveSource, val listed: Int, internal val loaded: Loaded)

    sealed interface CheckResult {
        data class Ready(val checked: Checked) : CheckResult
        data class Refused(val reason: String) : CheckResult
    }

    /** What one fetch produced, before it is saved. */
    internal sealed interface Loaded {
        /**
         * [addonName] is set for a Stremio addon's channels (its manifest name); [epgUrls] are a
         * playlist header's guides, [playlistAttrs] its other attributes ([LiveSource.playlistAttrs]).
         */
        data class Channels(
            val list: List<Channel>,
            val addonName: String? = null,
            val epgUrls: List<String> = emptyList(),
            val playlistAttrs: Map<String, String> = emptyMap()
        ) : Loaded
        data class Guide(val snapshot: EpgSnapshot) : Loaded
        /** The channel pack: every kept channel, listed or not ([ChannelPack.listing] picks). */
        data class Pack(val channels: List<PackChannel>) : Loaded
    }

    /** What the fetched body alone gives: final data, or an addon manifest still to expand. */
    private sealed interface Parsed {
        data class Ready(val loaded: Loaded) : Parsed
        data class Addon(val manifest: StremioTvManifest) : Parsed
    }

    private val mutex = Mutex()

    /**
     * Serialises the record-only adds ([addGuideRecords]) with [add], so a URL is never stored
     * twice, without waiting on [mutex], which a refresh holds for its whole download.
     */
    private val addMutex = Mutex()

    val sources: StateFlow<List<LiveSource>> = store.sources

    private val _packSaves = MutableStateFlow(0)

    /**
     * Counts the pack's completed downloads (its file, kept scan results and listing all written):
     * readers of [PackStore] key on it, as an add writes the pack's record before its file
     * ([addLocked]), so the record alone does not mean the file is there (Spec 73 S3c).
     */
    val packSaves: StateFlow<Int> = _packSaves.asStateFlow()

    /**
     * Reads the source list, channel cache, iptv-org metadata and cached guides from disk (once
     * each), after removing any retired preset guide ([retireGuides]).
     */
    suspend fun load() {
        store.load()
        channels.load()
        metadata.load()
        retireGuides()
        guides.load(store.sources.value.filter { it.kind == SourceKind.XMLTV }.map { it.id })
    }

    /**
     * Removes each installed [RETIRED_GUIDE_IDS] record ([remove]: its cache file and the guide
     * choices pointing at it go too). Idempotent; a failed write leaves the record for the next load.
     * Only a load that finds one takes [mutex] (so it may wait behind a refresh's download, once per
     * device); every other load passes the check without the lock.
     */
    private suspend fun retireGuides() {
        for (id in RETIRED_GUIDE_IDS) if (store.get(id) != null) remove(id)
    }

    /** [check], then [add]: adds the source [url] serves once its content checks out. */
    suspend fun addUrl(url: String): AddResult = when (val result = check(url)) {
        is CheckResult.Refused -> AddResult.Refused(result.reason)
        is CheckResult.Ready -> add(result.checked)
    }

    /**
     * Fetches [url] and checks its content without storing anything (the Add source dialog's
     * check, D16): an M3U playlist with at least one channel, or an XMLTV guide with at least one
     * channel (its country, for name matching, is read from the file name: `epg_IN.xml.gz` ->
     * `in`), or a Stremio manifest with a usable `tv` catalog and at least one channel
     * (`stremio://` links are read as https). Named after the addon, else the URL's host (Rename
     * changes it later). A playlist's header guides are probed here ([headerGuides]). Holds no
     * lock while it fetches: a check that runs for minutes never holds up a refresh ([load] takes
     * [mutex] only to retire a preset guide, [retireGuides]).
     */
    suspend fun check(url: String): CheckResult {
        val trimmed = StremioTvAdapter.normalizeUrl(url)
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return CheckResult.Refused("Not a web address")
        load()
        if (store.sources.value.any { it.url == trimmed }) return CheckResult.Refused(ALREADY_ADDED)
        val id = newId()
        val (kind, loaded) = try {
            fetchLoaded(trimmed, id, expect = null)
        } catch (e: IOException) {
            return CheckResult.Refused(describe(e))
        }
        emptyReason(loaded)?.let { return CheckResult.Refused(it) }
        val draft = LiveSource(
            id = id,
            kind = kind,
            name = (loaded as? Loaded.Channels)?.addonName ?: httpUrl.host,
            url = trimmed,
            addedAtMs = nowMs(),
            lastRefreshMs = nowMs(),
            country = if (loaded is Loaded.Guide) guessCountry(httpUrl) else null,
            headerGuides = headerGuides(loaded),
            playlistAttrs = playlistAttrs(loaded)
        )
        val source = draft.copy(lastError = incompleteNote(loaded), channelCount = count(draft, loaded))
        val listed = when (loaded) {
            is Loaded.Channels -> loaded.list.size
            is Loaded.Guide -> loaded.snapshot.channels.size
            is Loaded.Pack -> loaded.channels.size
        }
        return CheckResult.Ready(Checked(source, listed, loaded))
    }

    /** Stores a [check]ed source and its data; refused when its URL was added meanwhile or a write failed. */
    suspend fun add(checked: Checked): AddResult = mutex.withLock { addMutex.withLock { addLocked(checked) } }

    private suspend fun addLocked(checked: Checked): AddResult {
        val source = checked.source
        if (store.sources.value.any { it.url == source.url }) return AddResult.Refused(ALREADY_ADDED)
        // Record first: if the data write then fails (or the process dies), the record stays
        // reachable on the Sources route, where Refresh or Remove repairs it; data written first
        // could be orphaned with no source to remove it by. Not cancellable: a cancel between the
        // writes (the route closing) would leave a record that looks healthy with no data.
        return withContext(NonCancellable) {
            try {
                store.upsert(source)
                try {
                    save(source, checked.loaded)
                } catch (e: IOException) {
                    // The pack file is written before the channels: drop it with the record.
                    if (checked.loaded is Loaded.Pack) {
                        try {
                            pack.clear()
                        } catch (cleanup: IOException) {
                            logFailure("drop pack file", cleanup) // a stale file; the next add overwrites it
                        }
                    }
                    store.remove(source.id)
                    throw e
                }
                AddResult.Added(source)
            } catch (e: IOException) {
                AddResult.Refused(describe(e, saving = true))
            }
        }
    }

    /** Renames source [id] (Sources > Rename); false when it is gone, [name] is blank, or the file write failed. */
    suspend fun rename(id: String, name: String): Boolean = mutex.withLock {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@withLock false
        val source = store.get(id) ?: return@withLock false
        if (source.name == trimmed) return@withLock true
        try {
            store.upsert(source.copy(name = trimmed))
            true
        } catch (e: IOException) {
            logFailure("rename", e)
            false
        }
    }

    /** Adds the broadcaster preset (or refreshes it when present) and returns its record. */
    suspend fun addBroadcasters(): LiveSource = mutex.withLock {
        val preset = store.get(PRESET_ID) ?: LiveSource(
            id = PRESET_ID,
            kind = SourceKind.M3U,
            name = PRESET_NAME,
            url = PRESET_URL,
            addedAtMs = nowMs()
        )
        refreshLocked(preset)
    }

    /**
     * Downloads the channel pack and adds it (Spec 73 D3); refused, with nothing stored, when it
     * is already added or the download or a write fails. Downloads without the lock, as [check]
     * does; [addLocked] checks again under it.
     */
    suspend fun addPack(): AddResult {
        load()
        if (store.get(ChannelPack.SOURCE_ID) != null) return AddResult.Refused(ALREADY_ADDED)
        val loaded = try {
            fetchPack()
        } catch (e: IOException) {
            return AddResult.Refused(describe(e))
        }
        emptyReason(loaded)?.let { return AddResult.Refused(it) }
        val source = LiveSource(
            id = ChannelPack.SOURCE_ID,
            kind = SourceKind.PACK,
            name = ChannelPack.NAME,
            url = ChannelPack.BASE_URL,
            addedAtMs = nowMs(),
            lastRefreshMs = nowMs(),
            channelCount = loaded.channels.size
        )
        return add(Checked(source, loaded.channels.size, loaded))
    }

    /**
     * The pack's figures for the empty state and the Sources offer (Spec 73 D2, D9), read from the
     * list repo; null when they cannot be read (offline, missing, malformed, or slower than
     * [SUMMARY_TIMEOUT_MS]). Stores nothing.
     */
    suspend fun packSummary(): PackSummary? = withTimeoutOrNull(SUMMARY_TIMEOUT_MS) {
        try {
            fetcher.fetch(packUrls.summary) { _, body -> ChannelPack.readSummary(readCapped(body, SUMMARY_MAX_BYTES)) }
        } catch (e: IOException) {
            logFailure("pack summary", e)
            null
        }
    }

    /**
     * Adds the guide records of [presets] (Sources' suggestions, Spec 55 S2) that no source holds
     * by URL; returns the records holding every one of their guides, in order, or null when a write
     * failed ([addGuideRecords]). Fetching is the caller's [refresh] (or the next [refreshStale]).
     */
    suspend fun addPresetGuides(presets: List<GuidePreset>): List<LiveSource>? =
        addGuideRecords(presets.flatMap { it.guides }, "preset guide")

    /**
     * Adds a guide record for each of [named] (URL to row name: a playlist's header guides, Spec 55
     * S1) that no source holds by URL, its country read from the file name ([guessCountry]);
     * returns the records holding them, or null when a write failed ([addGuideRecords]). Fetching
     * is the caller's [refresh].
     */
    suspend fun addHeaderGuides(named: List<Pair<String, String>>): List<LiveSource>? = addGuideRecords(
        named.map { (url, name) ->
            LiveSource(
                id = newId(),
                kind = SourceKind.XMLTV,
                name = name,
                url = url,
                addedAtMs = 0,
                country = url.toHttpUrlOrNull()?.let(::guessCountry)
            )
        },
        "header guide"
    )

    /**
     * Stores each of [drafts] (guide records, stamped now) that no source holds by URL; returns
     * the records holding them, in order, or null when a write failed. Takes [addMutex], not
     * [mutex]: a new record touches no other source's data. Records written before a
     * failed one stay: each is an ordinary guide row (fetched by the next [refreshStale]) and the
     * rest can be added again.
     */
    private suspend fun addGuideRecords(drafts: List<LiveSource>, what: String): List<LiveSource>? = addMutex.withLock {
        val held = mutableListOf<LiveSource>()
        for (draft in drafts) {
            val existing = store.sources.value.firstOrNull { it.url == draft.url }
            if (existing != null) {
                held += existing
                continue
            }
            val guide = draft.copy(addedAtMs = nowMs())
            try {
                store.upsert(guide)
            } catch (e: IOException) {
                logFailure("save $what", e)
                return@withLock null
            }
            held += guide
        }
        held
    }

    /** Refreshes source [id]; null when no such source. */
    suspend fun refresh(id: String): LiveSource? = mutex.withLock {
        store.get(id)?.let { refreshLocked(it) }
    }

    /**
     * Refreshes every source [isStale] calls stale, one at a time (each takes the lock on its
     * own, so a removal can slip in between), after the iptv-org metadata when there are channel
     * sources (L5c, every 3 days) so guide match counts see its names. Called on Live TV open,
     * off the UI path (D14).
     */
    suspend fun refreshStale() {
        load()
        // Only playlists and the pack carry tvg-ids to enrich; Stremio channels have provider ids.
        if (store.sources.value.any { it.kind == SourceKind.M3U || it.kind == SourceKind.PACK }) metadata.refreshIfStale()
        for (id in store.sources.value.map { it.id }) {
            // Re-checked under the lock: an overlapping call may have refreshed it meanwhile.
            mutex.withLock {
                val source = store.get(id) ?: return@withLock
                if (isStale(source, guides.guides.value[id], nowMs(), zone())) refreshLocked(source)
            }
        }
    }

    /**
     * Removes source [id] with its channels (and their guide overrides) or its guide; false when
     * a file could not be written. Data goes first, so a failure leaves the record to retry from.
     */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        val source = store.get(id) ?: return@withLock true
        try {
            when (source.kind) {
                // A user's "this guide" choice must not outlive the guide: the channel falls back to matching.
                SourceKind.XMLTV -> {
                    guides.remove(id)
                    channels.clearOverridesTo(id)
                }
                SourceKind.M3U, SourceKind.STREMIO -> channels.removeSource(id)
                // Channels first, as a playlist's: a failed file delete then keeps the record to retry from.
                SourceKind.PACK -> {
                    channels.removeSource(id)
                    pack.clear()
                    scans.clear()
                }
            }
            store.remove(id)
            true
        } catch (e: IOException) {
            logFailure("remove", e)
            false
        }
    }

    private suspend fun refreshLocked(source: LiveSource): LiveSource {
        if (source.kind == SourceKind.XMLTV) guides.load(listOf(source.id))
        val loaded = try {
            when {
                source.id == PRESET_ID -> withContext(io) { openPreset().use { parseM3u(source.id, it) } }
                source.kind == SourceKind.PACK -> fetchPack()
                else -> fetchLoaded(source.url, source.id, expect = source.kind).second
            }
        } catch (e: IOException) {
            return record(source.copy(lastError = describe(e)))
        }
        emptyReason(loaded)?.let { return record(source.copy(lastError = it)) }
        // A cut-short guide (bad XML, or a network drop or timeout mid-body: the parser keeps what
        // it read) loses to a complete snapshot that still covers today and tomorrow. Otherwise
        // (after midnight; kept losing, every open would refetch) it wins, filled from the cached
        // snapshot ([fillFrom]: an early cut must not blank today, nor an hourly retry what the last fill kept).
        val partial = (loaded as? Loaded.Guide)?.snapshot?.takeIf { !it.complete }
        val cached = partial?.let { guides.guides.value[source.id] }
        if (cached != null && cached.complete && coversDays(cached, nowMs(), zone())) {
            return record(source.copy(lastError = GUIDE_INCOMPLETE))
        }
        val saving = if (partial != null && cached != null) Loaded.Guide(fillFrom(partial, cached)) else loaded
        return try {
            save(source, saving)
            val saved = record(
                source.copy(
                    lastRefreshMs = nowMs(),
                    lastError = incompleteNote(saving),
                    channelCount = count(source, saving),
                    playlistAttrs = playlistAttrs(loaded)
                )
            )
            // Probed once the refresh is recorded: a cancel meanwhile only keeps the old header guides.
            val header = headerGuides(loaded)
            if (header == saved.headerGuides) saved else record(saved.copy(headerGuides = header))
        } catch (e: IOException) {
            record(source.copy(lastError = describe(e, saving = true)))
        }
    }

    /** Stores [updated]; a failed write only loses the status line until the next refresh. */
    private suspend fun record(updated: LiveSource): LiveSource {
        try {
            store.upsert(updated)
        } catch (e: IOException) {
            logFailure("save source", e)
        }
        return updated
    }

    /**
     * Fetches [url] and parses it by its detected kind ([expect], when set, must match); a Stremio
     * manifest is expanded into its catalog channels. Throws IOException / [SourceFetchException].
     */
    private suspend fun fetchLoaded(url: String, sourceId: String, expect: SourceKind?): Pair<SourceKind, Loaded> {
        val (kind, parsed) = fetcher.fetch(url) { detected, body ->
            if (expect != null && detected != expect) throw SourceFetchException(noLongerReason(expect))
            val found = detected ?: throw SourceFetchException(UNRECOGNISED)
            found to parse(found, sourceId, body)
        }
        val loaded = when (parsed) {
            is Parsed.Ready -> parsed.loaded
            is Parsed.Addon -> Loaded.Channels(stremio.channels(sourceId, url, parsed.manifest), parsed.manifest.name)
        }
        return kind to loaded
    }

    /**
     * The channel pack from the list repo: the monitor's states first, then the pool, joined by
     * URL ([ChannelPack]). Each file is one [SourceFetcher.fetch] (its call timeout and its text cap:
     * a TSV is detected as no kind, and a body detected as any kind is refused before it is read,
     * since a guide-like head would raise the cap to the guide's).
     * Throws IOException / [SourceFetchException] (a missing column, a cut-short body).
     */
    private suspend fun fetchPack(): Loaded.Pack {
        val state = fetcher.fetch(packUrls.state) { kind, body -> ChannelPack.readState(tsv(kind, body)) }
        val channels = fetcher.fetch(packUrls.streams) { kind, body -> ChannelPack.readStreams(tsv(kind, body), state) }
        return Loaded.Pack(channels)
    }

    private fun tsv(kind: SourceKind?, body: InputStream): java.io.BufferedReader =
        if (kind != null) throw SourceFetchException(ChannelPack.FORMAT_CHANGED) else body.bufferedReader(Charsets.UTF_8)

    private fun parse(kind: SourceKind, sourceId: String, body: InputStream): Parsed = when (kind) {
        SourceKind.M3U -> Parsed.Ready(parseM3u(sourceId, body))
        SourceKind.XMLTV -> {
            val startNs = System.nanoTime()
            val (windowStart, windowEnd) = dayWindow(nowMs(), zone())
            val snapshot = xmltv.parse(body, windowStart, windowEnd - windowStart)
            // Spec 12/S57: Shield guide time (D14). The body streams from the network, so this is
            // download + parse together.
            Log.i(TAG, "Guide $sourceId fetched + parsed in ${(System.nanoTime() - startNs) / 1_000_000} ms: " +
                "${snapshot.channels.size} channels, complete=${snapshot.complete}")
            Parsed.Ready(Loaded.Guide(snapshot))
        }
        SourceKind.STREMIO -> Parsed.Addon(stremio.readManifest(body))
        // Never detected from a body (SourceFetcher.detectKind).
        SourceKind.PACK -> throw SourceFetchException(UNRECOGNISED)
    }

    /**
     * Streams to try for [channel], in order: its own for playlist channels; for a Stremio
     * channel, the addon's answer now (D6, never cached: addon stream URLs expire), within
     * [STREAM_LOOKUP_MS] so a dead addon cannot eat the per-channel budget (D7, ~30 s). Throws
     * IOException / [SourceFetchException] when the addon cannot answer; the player (L6) treats
     * that like a failed stream.
     */
    suspend fun streamsFor(channel: Channel): List<LiveStream> {
        val providerId = channel.providerId ?: return channel.streams
        val source = store.get(channel.sourceId) ?: throw SourceFetchException("Source removed")
        return withTimeoutOrNull(STREAM_LOOKUP_MS) { stremio.streams(source.url, providerId) }
            ?: throw SourceFetchException("Addon did not answer")
    }

    private suspend fun save(source: LiveSource, loaded: Loaded) = when (loaded) {
        is Loaded.Channels -> channels.replaceSource(source.id, loaded.list)
        is Loaded.Guide -> guides.put(source.id, loaded.snapshot)
        // The whole pack first (the scan's input), then its scan results kept by URL, then the listing.
        is Loaded.Pack -> {
            pack.save(loaded.channels)
            val stored = scans.read()
            val results = withContext(io) { forPack(stored.results, loaded.channels) }
            if (results.size != stored.results.size) scans.save(stored.copy(results = results))
            channels.replaceSource(source.id, withContext(io) { ChannelPack.listing(loaded.channels, results) })
            _packSaves.update { it + 1 }
        }
    }

    /**
     * Stores a running or ended scan's [results] (this scan's so far, over the stored ones; the
     * stored ones of [cleared] dropped: back to the monitor's state) and [record] (the last scan),
     * then lists the pack anew from them (Spec 73 S3a, [PackScanner]). False when the pack is no
     * longer added (the scan stops); a failed write is logged and the next call retries it.
     */
    suspend fun applyScan(
        results: Map<String, PackScanResult>,
        cleared: Set<String>,
        record: PackScanRecord
    ): Boolean = mutex.withLock {
        if (store.get(ChannelPack.SOURCE_ID) == null) return@withLock false
        try {
            val packChannels = pack.read()
            val merged = withContext(io) { forPack(scans.read().results - cleared + results, packChannels) }
            scans.save(PackScans(results = merged, last = record))
            channels.replaceSource(ChannelPack.SOURCE_ID, withContext(io) { ChannelPack.listing(packChannels, merged) })
        } catch (e: IOException) {
            logFailure("save scan", e)
        }
        true
    }

    /** Channels for a playlist; for a guide, the visible channels it gives a guide to today. */
    private suspend fun count(source: LiveSource, loaded: Loaded): Int = when (loaded) {
        is Loaded.Channels -> loaded.list.size
        // Every pack channel, listed or waiting for a scan (the PACK row's count, D6).
        is Loaded.Pack -> loaded.channels.size
        is Loaded.Guide -> {
            channels.load()
            metadata.load()
            withContext(io) {
                EpgMatcher.match(
                    ChannelEnricher.listable(ChannelEnricher.enrich(channels.channels.value, metadata.metadata.value)),
                    listOf(EpgSource(source.id, source.country, loaded.snapshot)),
                    channels.overrides.value
                ).values.count { it.epgSourceId == source.id }
            }
        }
    }

    private fun emptyReason(loaded: Loaded): String? = when (loaded) {
        is Loaded.Channels if loaded.list.isEmpty() ->
            if (loaded.addonName != null) "No channels in this addon" else "No channels in this playlist"
        is Loaded.Guide if loaded.snapshot.channels.isEmpty() -> "No channels in this guide"
        is Loaded.Pack if loaded.channels.isEmpty() -> "No channels in the channel pack"
        else -> null
    }

    private fun incompleteNote(loaded: Loaded): String? =
        if (loaded is Loaded.Guide && !loaded.snapshot.complete) GUIDE_INCOMPLETE else null

    private fun noLongerReason(kind: SourceKind): String = when (kind) {
        SourceKind.M3U -> "No longer an M3U playlist"
        SourceKind.XMLTV -> "No longer an XMLTV guide"
        SourceKind.STREMIO -> "No longer a Stremio addon"
        SourceKind.PACK -> ChannelPack.FORMAT_CHANGED
    }

    private fun parseM3u(sourceId: String, body: InputStream): Loaded.Channels {
        val playlist = M3uParser.parse(body.reader(Charsets.UTF_8))
        return Loaded.Channels(
            ChannelIdentity.channelsFromM3u(sourceId, playlist),
            epgUrls = playlist.epgUrls,
            playlistAttrs = playlist.attrs
        )
    }

    private fun playlistAttrs(loaded: Loaded): Map<String, String>? =
        (loaded as? Loaded.Channels)?.playlistAttrs?.ifEmpty { null }

    /**
     * The header guides of a fetched playlist Sources may offer ([headerCandidates]), each country's
     * files tried in header order until one passes [offerable]; probes run [PROBE_PARALLEL] at a
     * time. Null for anything else, or when none passes.
     */
    private suspend fun headerGuides(loaded: Loaded): HeaderGuides? {
        if (loaded !is Loaded.Channels) return null
        val (groups, byCountry) = headerCandidates(loaded.epgUrls, loaded.list) ?: return null
        val gate = Semaphore(PROBE_PARALLEL)
        val urls = coroutineScope {
            groups.map { files -> async { gate.withPermit { files.firstOrNull { offerable(it) } } } }.awaitAll()
        }.filterNotNull()
        return urls.takeIf { it.isNotEmpty() }?.let { HeaderGuides(it, byCountry) }
    }

    /**
     * False when a HEAD finds [url] gone (404/410) or larger than [MAX_GUIDE_DOWNLOAD_BYTES]; a
     * probe that fails on the network keeps it (its fetch reports the problem on the guide's row).
     */
    private suspend fun offerable(url: String): Boolean {
        val probe = try {
            fetcher.probe(url)
        } catch (e: IOException) {
            logFailure("probe", e)
            return true
        }
        return probe.code != 404 && probe.code != 410 && (probe.length ?: 0) <= MAX_GUIDE_DOWNLOAD_BYTES
    }

    private fun describe(e: Throwable, saving: Boolean = false): String = when {
        e is SourceFetchException -> e.message ?: UNRECOGNISED
        saving -> {
            logFailure("save", e)
            "Could not save (${e.javaClass.simpleName})"
        }
        else -> {
            logFailure("fetch", e)
            "Network error (${e.javaClass.simpleName})"
        }
    }

    // Class name only: messages of network and file failures can carry the URL or path (KT-SEC-02).
    private fun logFailure(what: String, e: Throwable) = Log.w(TAG, "Source $what failed: ${e.javaClass.simpleName}")

    companion object {
        const val PRESET_ID = "preset"
        const val PRESET_ASSET = "livetv/broadcasters.m3u"
        const val PRESET_URL = "asset:///$PRESET_ASSET"
        const val PRESET_NAME = "Broadcasters"

        /**
         * D5 guide presets: India now/next for the broadcaster and user channels (Spec 49 D27 F5:
         * suggested). One guide since Spec 55 S4 (D16): epg.pw India was dropped, its times 8 h late.
         */
        val INDIA_GUIDES = listOf(
            guideRecord("preset-free-epg-in", "free-epg.de India", "https://www.free-epg.de/api/epg/in.xml.gz", "in")
        )

        /**
         * Preset guide records [load] removes from installed lists: epg.pw India (Spec 55 S4, D16)
         * writes UTC+8 times as UTC (386 of 387 channels 8 h late, 2026-11-11).
         */
        internal val RETIRED_GUIDE_IDS = listOf("preset-epg-pw-in")

        /**
         * Spec 38 Q2: every Pluto channel's guide, matched by the id in its `jmp2.uk/plu-` stream
         * URL (EpgMatcher), never by name (country null). Only suggested on Sources when a playlist
         * holds Pluto channels; never added by [addBroadcasters].
         */
        val PLUTO_GUIDE = guideRecord("preset-pluto", "Pluto TV", "https://i.mjh.nz/PlutoTV/all.xml.gz", null)

        /**
         * Sources' guide suggestions (Spec 55 S2, D6-D10; URLs curl-checked 2026-11-10). A service
         * preset fits channels whose stream URL carries its id ([ChannelIdentity.services]) and is
         * matched by that id (country null); a country preset fits channels of its country and is
         * matched by tvg-id or name. One combined guide per service (D10): Plex and Samsung TV Plus
         * "all", as Pluto's. Australia takes two guides (D7: 9 of 68 channels matched with one, 19
         * with both).
         */
        val GUIDE_PRESETS: List<GuidePreset> = listOf(
            GuidePreset("pluto", listOf(PLUTO_GUIDE), service = GuideService.PLUTO),
            GuidePreset(
                "plex",
                listOf(guideRecord("preset-plex", "Plex TV", "https://i.mjh.nz/Plex/all.xml.gz", null)),
                service = GuideService.PLEX
            ),
            GuidePreset(
                "samsung",
                listOf(guideRecord("preset-samsung", "Samsung TV Plus", "https://i.mjh.nz/SamsungTVPlus/all.xml.gz", null)),
                service = GuideService.SAMSUNG
            ),
            GuidePreset("in", INDIA_GUIDES, country = "in"),
            GuidePreset(
                "uk",
                listOf(openEpg("uk", "unitedkingdom", "United Kingdom")),
                country = "uk"
            ),
            GuidePreset("ca", listOf(openEpg("ca", "canada", "Canada")), country = "ca"),
            GuidePreset(
                "au",
                listOf(
                    openEpg("au", "australia", "Australia"),
                    guideRecord("preset-mjh-au", "i.mjh.nz Australia", "https://i.mjh.nz/au/all/epg.xml.gz", "au")
                ),
                country = "au"
            ),
            GuidePreset("nz", listOf(openEpg("nz", "newzealand", "New Zealand")), country = "nz")
        )

        private fun openEpg(country: String, file: String, countryName: String) = guideRecord(
            "preset-open-epg-$country",
            "open-epg $countryName",
            "https://www.open-epg.com/files/$file.xml.gz",
            country
        )

        private fun guideRecord(id: String, name: String, url: String, country: String?) =
            LiveSource(id = id, kind = SourceKind.XMLTV, name = name, url = url, addedAtMs = 0, country = country)

        /** [results] of streams [pack] still holds: a refresh keeps results by URL and drops the rest. */
        private fun forPack(results: Map<String, PackScanResult>, pack: List<PackChannel>): Map<String, PackScanResult> {
            if (results.isEmpty()) return results
            val keys = pack.flatMapTo(HashSet()) { ch -> ch.streams.map { ChannelPack.scanKey(it.url) } }
            return results.filterKeys { it in keys }
        }

        /** [packSummary]'s bound: it runs as the empty state opens, so a slow answer just shows no figures. */
        const val SUMMARY_TIMEOUT_MS = 10_000L

        /** `monitor/pack.json` is four numbers; anything near this is not it. */
        private const val SUMMARY_MAX_BYTES = 4 * 1024

        /** [body] as UTF-8 text; throws [SourceFetchException] past [max] bytes. */
        private fun readCapped(body: InputStream, max: Int): String {
            // readNBytes needs API 33 on Android (minSdk 24).
            val bytes = ByteArray(max + 1)
            var n = 0
            while (n < bytes.size) {
                val r = body.read(bytes, n, bytes.size - n)
                if (r < 0) break
                n += r
            }
            if (n > max) throw SourceFetchException("Larger than expected")
            return String(bytes, 0, n, Charsets.UTF_8)
        }

        /** Play-time stream lookup bound for a Stremio channel. */
        const val STREAM_LOOKUP_MS = 10_000L

        /** Any source: refetch after a day. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** Guides: a cut-short snapshot is retried this long after its refresh. */
        const val GUIDE_MARGIN_MS = 60L * 60 * 1000

        private const val UNRECOGNISED = "Not a playlist, guide or Stremio addon"
        private const val ALREADY_ADDED = "Already added"
        /** A guide refresh that kept part of a cut-short file (Sources shows it as partial, not failed). */
        internal const val GUIDE_INCOMPLETE = "Guide file incomplete; kept what was read"
        private const val TAG = "LiveSourceManager"
        private val COUNTRY_STEM = Regex("""(?:^|[_.-])([A-Za-z]{2})\d?$""")

        /** Spec 55 D4: a header naming this many guides or fewer offers them all, no country check. */
        const val FEW_HEADER_GUIDES = 3

        /**
         * Header guides at or under this size are offered: what a slow 2 Mbit/s line fetches within
         * [SourceFetcher.CALL_TIMEOUT_MINUTES]; a gz guide inflates ~7x (epgshare01 PL1: 9 -> 63 MB,
         * 2026-11-08), so this also keeps under [XmltvNowNextParser.MAX_INPUT_BYTES].
         */
        const val MAX_GUIDE_DOWNLOAD_BYTES = 50L * 1024 * 1024

        private const val PROBE_PARALLEL = 8

        /** Header files that hold every country or placeholder channels (epgshare01's ALL_SOURCES1, DUMMY_CHANNELS). */
        private val CATCH_ALL = Regex("all_sources|dummy", RegexOption.IGNORE_CASE)

        /**
         * Which of a playlist header's [epgUrls] Sources may offer (Spec 55 D4/D5), before the live
         * probe: web addresses only, catch-alls dropped. 3 or fewer left: each alone, byCountry
         * false. More: one group per country of [channels] that a file name gives (`_UK1` -> uk),
         * its files in header order, the country with most channels first (header order on ties),
         * byCountry true. Null when nothing is left.
         */
        internal fun headerCandidates(epgUrls: List<String>, channels: List<Channel>): Pair<List<List<String>>, Boolean>? {
            val usable = epgUrls.distinct().filter { url ->
                val http = url.toHttpUrlOrNull() ?: return@filter false
                !CATCH_ALL.containsMatchIn(http.pathSegments.lastOrNull().orEmpty())
            }
            if (usable.isEmpty()) return null
            if (usable.size <= FEW_HEADER_GUIDES) return usable.map { listOf(it) } to false
            val perCountry = channels.mapNotNull { ChannelIdentity.normalizeCountry(it.country) }.groupingBy { it }.eachCount()
            val groups = usable.groupBy { guessCountry(it.toHttpUrl()) }
                .filterKeys { it != null && it in perCountry }
                .entries
                .sortedByDescending { perCountry.getValue(it.key!!) }
                .map { it.value }
            return groups.takeIf { it.isNotEmpty() }?.let { it to true }
        }

        /**
         * Per-channel programme cap for the day window (Spec 67 G1c): G1a measured at most 201 in
         * 48 h (free-epg.de), so 300 is a 10-minute average slot.
         */
        const val DAY_WINDOW_MAX_PROGRAMMES = 300

        /**
         * The window a guide parse keeps (Spec 67 G1c): [local midnight today, local midnight of
         * the day after tomorrow) in [zone], today and tomorrow as calendar days (a DST day is not
         * cut or stretched by an hour).
         */
        internal fun dayWindow(nowMs: Long, zone: ZoneId): Pair<Long, Long> =
            localDayStartMs(nowMs, zone) to localDayStartMs(nowMs, zone, 2)

        /**
         * [partial] (a cut-short parse) filled from [old], the cached snapshot: [old]'s programmes
         * that have not ended by [partial]'s window start, for channels [partial] holds none for and
         * after the end of a channel's cut-off list; [old]'s channel entries only for channels the
         * result holds programmes for. [partial]'s own programmes and entries win. Programmes ended
         * before the window start never carry over, so across days of cut-short files a list cannot
         * go all-past (the matcher's live pass reads a non-empty list as current), and a channel the
         * guide dropped goes at the first refresh whose window starts after its last programme ends.
         */
        internal fun fillFrom(partial: EpgSnapshot, old: EpgSnapshot): EpgSnapshot {
            val current = old.programmes
                .mapValues { (_, list) -> list.filter { it.stopMs > partial.windowStartMs } }
                .filterValues { it.isNotEmpty() }
            // The parser drops empty lists, so maxOf has an element.
            val extended = partial.programmes.mapValues { (id, list) ->
                val end = list.maxOf { it.stopMs }
                list + current[id].orEmpty().filter { it.startMs >= end }
            }
            val programmes = current + extended
            return partial.copy(channels = old.channels.filterKeys { it in programmes } + partial.channels, programmes = programmes)
        }

        /**
         * Whether [snapshot] reaches the end of tomorrow at [nowMs] in [zone]. False after local
         * midnight for yesterday's window, for a pre-G1c 6 h snapshot, and after a zone change west.
         * A refresh writes exactly [dayWindow]'s end, so a fresh guide never fails it.
         */
        private fun coversDays(snapshot: EpgSnapshot, nowMs: Long, zone: ZoneId): Boolean =
            snapshot.windowEndMs >= dayWindow(nowMs, zone).second

        /**
         * Stale = never refreshed, or older than [MAX_AGE_MS]; a guide also when its snapshot is
         * missing, no longer reaches the end of tomorrow ([coversDays]: the first Live TV open after
         * local midnight refetches), or was cut short and is [GUIDE_MARGIN_MS] old (retried hourly,
         * not on every open).
         */
        internal fun isStale(source: LiveSource, snapshot: EpgSnapshot?, nowMs: Long, zone: ZoneId): Boolean {
            val last = source.lastRefreshMs ?: return true
            return when (source.kind) {
                SourceKind.M3U, SourceKind.STREMIO, SourceKind.PACK -> nowMs - last >= MAX_AGE_MS
                SourceKind.XMLTV -> snapshot == null || nowMs - last >= MAX_AGE_MS ||
                    !coversDays(snapshot, nowMs, zone) ||
                    (!snapshot.complete && nowMs - last >= GUIDE_MARGIN_MS)
            }
        }

        /**
         * Country of a guide from its file name (`epg_IN.xml.gz`, `in.xml`, epgshare01's
         * `epg_ripper_UK1.xml.gz`); null when none reads as one.
         */
        internal fun guessCountry(url: HttpUrl): String? {
            val stem = url.pathSegments.lastOrNull().orEmpty()
                .removeSuffix(".gz").removeSuffix(".xml")
            return COUNTRY_STEM.find(stem)?.groupValues?.get(1)?.let(ChannelIdentity::normalizeCountry)
        }
    }
}

/**
 * One guide suggestion Sources offers (Spec 55 S2): [key] is stable, [guides] the records adding
 * it stores; it fits channels of [service] (by stream URL id) or of [country] (exactly one is set).
 */
data class GuidePreset(
    val key: String,
    val guides: List<LiveSource>,
    val service: GuideService? = null,
    val country: String? = null
) {
    init {
        require((service == null) != (country == null)) { "A preset fits a service or a country" }
    }
}
