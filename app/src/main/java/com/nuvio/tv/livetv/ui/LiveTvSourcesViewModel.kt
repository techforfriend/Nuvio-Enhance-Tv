package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.PackScanRecord
import com.nuvio.tv.livetv.source.PackScanStore
import com.nuvio.tv.livetv.source.PackScanner
import com.nuvio.tv.livetv.source.PackStore
import com.nuvio.tv.livetv.source.SourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Sources route draws (D16 stage 14). */
data class LiveTvSourcesUiState(
    val loading: Boolean = true,
    val rows: List<SourceRow> = emptyList(),
    val channelSources: Int = 0,
    val guides: Int = 0,
    /** When the iptv-org channel data was fetched; null = never (no DATA row). */
    val metadataFetchedAtMs: Long? = null,
    val hidden: List<HiddenRow> = emptyList(),
    /**
     * Spec 55 S2: the guide presets offered above the sources, most channels first (Pluto's and
     * India's rows among them); more than [LiveTvSourcesModel.FOLD_PRESETS] show as one row (D11).
     */
    val presets: List<PresetSuggestion> = emptyList(),
    /** Spec 55 S1: playlists' header guides not yet added (SUGGESTED rows and Choose guides). */
    val headers: List<HeaderSuggestion> = emptyList()
)

/** The Add source dialog's check (stage 15): Add is enabled only in [Ready]. */
sealed interface AddCheck {
    data object Idle : AddCheck
    data object Checking : AddCheck
    data class Ready(val checked: LiveSourceManager.Checked) : AddCheck
    data class Refused(val reason: String) : AddCheck
    data object Adding : AddCheck
}

/** One-shot outcomes the route shows as a toast; [Added] also moves focus to the new row. */
sealed interface SourcesEvent {
    data class Added(val id: String, val name: String) : SourcesEvent
    data object RenameFailed : SourcesEvent
    data object RemoveFailed : SourcesEvent
    data object UnhideFailed : SourcesEvent
    /** A preset guide record could not be saved. */
    data object PresetAddFailed : SourcesEvent
    /** A header guide record could not be saved. */
    data object HeaderAddFailed : SourcesEvent
    /** Add pack failed (Spec 73 D6); [reason] is the manager's user-facing refusal. */
    data class PackAddFailed(val reason: String) : SourcesEvent
    /**
     * A pack scan ended (done or cancelled) while this route is open: its result panel (Spec 73
     * stages 7, 8; D12 Q3: only over Sources, nothing elsewhere).
     */
    data class ScanEnded(val record: PackScanRecord) : SourcesEvent
}

/**
 * The Sources route (Spec 34 L7e): the source list with what each gave and its health, Refresh
 * (one or all), Rename, Remove, unhide, and the Add source dialog's check-then-add. Refreshes run
 * one at a time per source in this ViewModel's scope (leaving the route cancels them; the next
 * open's stale refresh picks them up). The Live TV screen sees every change through the shared
 * stores.
 */
@HiltViewModel
class LiveTvSourcesViewModel @Inject constructor(
    private val sourceManager: LiveSourceManager,
    channelRepository: ChannelRepository,
    guideRepository: GuideRepository,
    metadataRepository: IptvMetadataRepository,
    private val hiddenStore: HiddenChannelStore,
    private val scanner: PackScanner,
    private val packStore: PackStore,
    private val scanStore: PackScanStore
) : ViewModel() {

    private val loaded = MutableStateFlow(false)
    private val refreshing = MutableStateFlow<Set<String>>(emptySet())
    private var refreshAllJob: Job? = null
    /** Presets whose records are being written (a second press on the same row would toast Added twice). */
    private val presetsAdding = MutableStateFlow<Set<String>>(emptySet())
    private var headerJob: Job? = null

    private val _addUrl = MutableStateFlow("")
    val addUrl: StateFlow<String> = _addUrl.asStateFlow()
    private val _addCheck = MutableStateFlow<AddCheck>(AddCheck.Idle)
    val addCheck: StateFlow<AddCheck> = _addCheck.asStateFlow()
    private var addJob: Job? = null

    private val events = Channel<SourcesEvent>(Channel.BUFFERED)
    val event: Flow<SourcesEvent> = events.receiveAsFlow()

    private val rows = combine(
        sourceManager.sources,
        channelRepository.channels,
        guideRepository.guides,
        refreshing
    ) { sources, channels, guides, busy -> LiveTvSourcesModel.rows(sources, channels, guides, busy) }

    private val hidden = combine(hiddenStore.keys, channelRepository.channels, sourceManager.sources) { keys, channels, sources ->
        LiveTvSourcesModel.hidden(keys, channels, sources)
    }

    // Their own flow: a refresh starting or ending must not rescan every stream URL.
    private val suggestions = combine(sourceManager.sources, channelRepository.channels) { sources, channels ->
        Suggestions(
            LiveTvSourcesModel.presetSuggestions(sources, channels),
            LiveTvSourcesModel.headerSuggestions(sources, channels)
        )
    }

    val uiState: StateFlow<LiveTvSourcesUiState> = combine(
        loaded,
        rows,
        hidden,
        metadataRepository.metadata,
        suggestions
    ) { loaded, rows, hidden, metadata, suggested ->
        LiveTvSourcesUiState(
            loading = !loaded,
            rows = rows,
            channelSources = rows.count { it.source.kind != SourceKind.XMLTV },
            guides = rows.count { it.source.kind == SourceKind.XMLTV },
            metadataFetchedAtMs = metadata?.fetchedAtMs,
            hidden = hidden,
            presets = suggested.presets,
            headers = suggested.headers
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LiveTvSourcesUiState())

    private val packState = MutableStateFlow(PackOffer())

    /**
     * The SUGGESTED channel pack row (Spec 73 D6): null while the pack is added (or the list is
     * loading); its figures are read whenever it is offered and not read yet (also after a Remove), none when they cannot be read.
     */
    val packOffer: StateFlow<PackOffer?> = combine(loaded, sourceManager.sources, packState) { loaded, sources, offer ->
        offer.takeIf { loaded && sources.none { it.kind == SourceKind.PACK } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The pack's scan, running or not (Spec 73 S3b: the row's SCANNING n% / PAUSED, the progress panel). */
    val scan: StateFlow<PackScanner.State> = scanner.state

    /**
     * The stored pack and scans as the scan UI reads them (country list, the row's last scan and
     * waiting count); null while no pack is added, before the first read, and while the pack's
     * file is not written yet (an add stores the record first: the country list waits). Read
     * again when a pack download completes and when a scan starts or ends (a running scan's counts
     * come from [scan]).
     */
    val scanFacts: StateFlow<PackScanFacts?> = packScanFacts(
        sourceManager.sources,
        sourceManager.packSaves,
        scanner.state.map { it is PackScanner.State.Running }
    ) {
        val scans = scanStore.read()
        PackScanModel.facts(packStore.read(), scans.results, scans.last, thisTvRegion())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch { scanner.ended.collect { events.send(SourcesEvent.ScanEnded(it)) } }
        viewModelScope.launch {
            hiddenStore.load()
            sourceManager.load()
            loaded.value = true
            // Whenever the pack is not added (also after a Remove here) and its figures are not read yet.
            sourceManager.sources
                .map { list -> list.none { it.kind == SourceKind.PACK } }
                .distinctUntilChanged()
                .collect { offered ->
                    if (offered && packState.value.summary == null) {
                        val summary = sourceManager.packSummary()
                        packState.update { it.copy(summary = summary) }
                    }
                }
        }
    }

    /**
     * Sources' "Add pack" (Spec 73 D6): downloads and adds the pack; Added moves focus to its new
     * row, a failure toasts its reason. A second press while it runs does nothing.
     */
    fun addPack() {
        if (packState.value.adding) return
        packState.update { it.copy(adding = true) }
        viewModelScope.launch {
            try {
                when (val result = sourceManager.addPack()) {
                    is LiveSourceManager.AddResult.Added -> events.send(SourcesEvent.Added(result.source.id, result.source.name))
                    is LiveSourceManager.AddResult.Refused -> events.send(SourcesEvent.PackAddFailed(result.reason))
                }
            } finally {
                packState.update { it.copy(adding = false) }
            }
        }
    }

    /** Scans the pack's channels of [countries] (Spec 73 D4); false when a scan runs already. */
    fun startScan(countries: Set<String?>): Boolean = scanner.start(countries)

    /** Cancel scan (D5): stops the scan, keeping what it checked. */
    fun cancelScan() = scanner.cancel()

    fun refresh(id: String) {
        if (id in refreshing.value) return
        viewModelScope.launch { refreshNow(id) }
    }

    /** Refresh all: every source, one after another (each takes the manager's lock on its own). */
    fun refreshAll() {
        if (refreshAllJob?.isActive == true) return
        refreshAllJob = viewModelScope.launch {
            for (id in sourceManager.sources.value.map { it.id }) {
                if (id !in refreshing.value) refreshNow(id)
            }
        }
    }

    private suspend fun refreshNow(id: String) {
        refreshing.update { it + id }
        try {
            sourceManager.refresh(id)
        } finally {
            refreshing.update { it - id }
        }
    }

    /**
     * Spec 55 S2 "Add guide(s)" on a preset row, or Choose guides' "Add N guides": stores the guide
     * records of the offered presets among [keys], then fetches each ([refresh], so one guide's
     * download never holds up the next round). A preset whose write is still running is skipped,
     * so a second press on its row cannot toast Added twice; another row's press still adds.
     */
    fun addPresets(keys: Collection<String>) {
        val busy = presetsAdding.value
        val presets = uiState.value.presets.map { it.preset }.filter { it.key in keys && it.key !in busy }
        if (presets.isEmpty()) return
        val adding = presets.mapTo(HashSet()) { it.key }
        presetsAdding.update { it + adding }
        viewModelScope.launch {
            try {
                val guides = sourceManager.addPresetGuides(presets)
                if (guides == null) {
                    events.send(SourcesEvent.PresetAddFailed)
                    return@launch
                }
                events.send(SourcesEvent.Added(guides.first().id, guides.first().name))
                guides.forEach { refresh(it.id) }
            } finally {
                presetsAdding.update { it - adding }
            }
        }
    }

    /**
     * Spec 55 S1 "Add guides" / Choose guides' "Add N guides": stores a record for each of [urls]
     * among playlist [sourceId]'s offered header guides, named as offered, then fetches them here.
     * The guard covers the record write only: each fetch runs on its own ([refresh]), so a second
     * Choose guides round is not held up by the first round's downloads.
     */
    fun addHeaderGuides(sourceId: String, urls: Collection<String>) {
        if (headerJob?.isActive == true) return
        val offered = uiState.value.headers.firstOrNull { it.sourceId == sourceId }?.guides.orEmpty()
        val named = offered.filter { it.url in urls }.map { it.url to it.name }
        if (named.isEmpty()) return
        headerJob = viewModelScope.launch {
            val guides = sourceManager.addHeaderGuides(named)
            if (guides == null) {
                events.send(SourcesEvent.HeaderAddFailed)
                return@launch
            }
            events.send(SourcesEvent.Added(guides.first().id, guides.first().name))
            guides.forEach { refresh(it.id) }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            if (!sourceManager.rename(id, name)) events.send(SourcesEvent.RenameFailed)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            if (!sourceManager.remove(id)) events.send(SourcesEvent.RemoveFailed)
        }
    }

    fun unhide(key: String) {
        viewModelScope.launch {
            if (!hiddenStore.setHidden(key, false)) events.send(SourcesEvent.UnhideFailed)
        }
    }

    /** The dialog opened: an empty field, nothing checked (an add still saving finishes first). */
    fun openAdd() {
        if (resetCheck()) _addUrl.value = ""
    }

    /** The URL changed: any check of the old text no longer applies (ignored while an add saves). */
    fun onAddUrlChange(url: String) {
        if (url == _addUrl.value || _addCheck.value == AddCheck.Adding) return
        _addUrl.value = url
        resetCheck()
    }

    /** The dialog closed: a check still running is dropped with its data; an add still saving finishes. */
    fun closeAdd() {
        resetCheck()
    }

    /** Drops any check (running or passed) back to Idle; false, doing nothing, while an add saves. */
    private fun resetCheck(): Boolean {
        if (_addCheck.value == AddCheck.Adding) return false
        addJob?.cancel()
        _addCheck.value = AddCheck.Idle
        return true
    }

    /** Checks the typed URL (IME Done, D16): Add is enabled once it passes. */
    fun checkAdd() {
        val url = _addUrl.value.trim()
        val state = _addCheck.value
        if (url.isEmpty() || (state != AddCheck.Idle && state !is AddCheck.Refused)) return
        _addCheck.value = AddCheck.Checking
        addJob = viewModelScope.launch {
            _addCheck.value = when (val result = sourceManager.check(url)) {
                is LiveSourceManager.CheckResult.Refused -> AddCheck.Refused(result.reason)
                is LiveSourceManager.CheckResult.Ready -> AddCheck.Ready(result.checked)
            }
        }
    }

    /** Add (enabled only after a passed check): stores it; the route closes the dialog on [SourcesEvent.Added]. */
    fun confirmAdd() {
        val pending = (_addCheck.value as? AddCheck.Ready)?.checked ?: return
        _addCheck.value = AddCheck.Adding
        addJob = viewModelScope.launch {
            _addCheck.value = when (val result = sourceManager.add(pending)) {
                is LiveSourceManager.AddResult.Added -> {
                    _addUrl.value = ""
                    events.send(SourcesEvent.Added(result.source.id, result.source.name))
                    AddCheck.Idle
                }
                is LiveSourceManager.AddResult.Refused -> AddCheck.Refused(result.reason)
            }
        }
    }

    private data class Suggestions(
        val presets: List<PresetSuggestion>,
        val headers: List<HeaderSuggestion>
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** This TV's region from its time zone (D4: THIS TV); null for a zone with none (UTC: "001"). */
        fun thisTvRegion(): String? = try {
            android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id)
                .takeIf { it.length == 2 && it.all(Char::isLetter) }
        } catch (_: RuntimeException) {
            null // an unknown zone id; on the JVM (unit tests) the android.icu stub
        }
    }
}

/**
 * [LiveTvSourcesViewModel.scanFacts]' triggers (Spec 73 S3c): [read] runs when the pack's record
 * comes or goes, when a pack download completes ([packSaves], counted once its file is written:
 * an add writes the record first), and when a scan starts or ends; null while no record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun packScanFacts(
    sources: Flow<List<LiveSource>>,
    packSaves: Flow<Int>,
    running: Flow<Boolean>,
    read: suspend () -> PackScanFacts?
): Flow<PackScanFacts?> = combine(
    sources.map { list -> list.any { it.kind == SourceKind.PACK } }.distinctUntilChanged(),
    packSaves,
    running.distinctUntilChanged()
) { added, saves, scanning -> if (added) saves to scanning else null }
    .mapLatest { key -> key?.let { read() } }
