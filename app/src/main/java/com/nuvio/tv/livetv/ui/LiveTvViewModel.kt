package com.nuvio.tv.livetv.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideCandidate
import com.nuvio.tv.livetv.data.GuideCandidates
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.data.LiveTvUserState
import com.nuvio.tv.livetv.data.LiveTvUserStore
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.player.LivePlayer
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.livetv.player.StreamHealth
import com.nuvio.tv.livetv.player.StreamHealthRecord
import com.nuvio.tv.livetv.player.StreamHealthStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject

/** What the Live TV screen draws (D16), minus the tune state ([LivePlayer.state]). */
data class LiveTvUiState(
    /** The stores are loading or the first rows are being derived: the static skeleton. */
    val loading: Boolean = true,
    /** A playlist or `tv` addon exists; false = the "Add a channel source" empty state. */
    val hasChannelSources: Boolean = false,
    val filter: LiveTvFilter = LiveTvFilter.initial,
    /** Favorites and Recents counts (the pickers carry their own, [pickers]). */
    val counts: Map<LiveTvFilter, Int> = emptyMap(),
    /** The pickers' values: [filter] when it is a Browse, else the last one (kept while Favorites or Recents show). */
    val browse: LiveTvFilter.Browse = LiveTvFilter.initial,
    /** The three pickers' options under [browse] (Spec 38 Q1); null while loading. */
    val pickers: Pickers? = null,
    /** Category key -> label as first written ([Lineup.categoryLabels]). */
    val categoryLabels: Map<String, String> = emptyMap(),
    val rows: List<LiveRow> = emptyList(),
    /** The minute the rows were derived at (the info block's minutes left). */
    val nowMs: Long = 0L,
    /** List or the guide grid (Spec 67 D7 Q1); switches together with [guide]. */
    val view: LiveTvView = LiveTvView.LIST,
    /** [rows] laid out for the grid; null in [LiveTvView.LIST] (no layout work there). */
    val guide: GuideRows? = null
)

/**
 * The side panel's guide facts for one channel (D16, L7d), read when the panel opens: [current] is the
 * guide in use (null = none), [pickedNone] the user chose No guide, [currentNow] the current
 * guide's programme now, [guides] how many guides are cached, [candidates] Choose guide's list,
 * [sourceNames] each guide source's name by id.
 */
data class LiveGuidePanel(
    val current: EpgRef?,
    val pickedNone: Boolean,
    val currentNow: String?,
    val guides: Int,
    val candidates: List<GuideCandidate>,
    val sourceNames: Map<String, String>
)

/**
 * The Live TV screen's state (Spec 34 L7a): loads the stores, refreshes stale sources off the UI
 * path on open (D14), and derives the rows. Enrichment reruns only when channels, source ids, metadata
 * or hidden keys change, EPG matching also on guide or override changes; now/next re-derives each
 * minute for the shown filter.
 * Owns the screen's [LivePlayer] and releases it with the screen. The preview follows list focus
 * once it rests [PREVIEW_REST_MS] (Q2); the info block ([info]) follows it at once. Full screen
 * (L7c) zaps within the shown filter ([zap]) and swaps to the channel tuned before ([lastChannel]).
 * While a channel tunes or plays the OLED screensaver holds off (the VOD player's rule). The hold-OK
 * side panel (L7d) picks a channel's guide ([guidePanel], [chooseGuide]), steps its stream
 * ([nextStream]), hides it ([hideChannel]) and toggles its favorite ([toggleFavorite], L8).
 * The profile's favorites, recents, last filter and last view live in [LiveTvUserStore] (L8-D1..D3); lost
 * channels (Favorites and Recents only) are never tuned (L8-D4). Right on a row opens its day
 * schedule ([schedule], Spec 67 G2); the profile's view ([selectView], G3) shows the rows as the
 * list or the guide grid ([LiveTvUiState.guide]). [derive] runs the row derivation.
 */
@HiltViewModel
class LiveTvViewModel internal constructor(
    private val sourceManager: LiveSourceManager,
    private val channelRepository: ChannelRepository,
    private val guideRepository: GuideRepository,
    metadataRepository: IptvMetadataRepository,
    private val hiddenStore: HiddenChannelStore,
    private val health: StreamHealthStore,
    private val userStore: LiveTvUserStore,
    private val screensaver: ScreensaverController,
    val player: LivePlayer,
    private val derive: CoroutineDispatcher
) : ViewModel() {

    @Inject
    constructor(
        sourceManager: LiveSourceManager,
        channelRepository: ChannelRepository,
        guideRepository: GuideRepository,
        metadataRepository: IptvMetadataRepository,
        hiddenStore: HiddenChannelStore,
        health: StreamHealthStore,
        userStore: LiveTvUserStore,
        screensaver: ScreensaverController,
        player: LivePlayer
    ) : this(
        sourceManager, channelRepository, guideRepository, metadataRepository, hiddenStore, health, userStore,
        screensaver, player, Dispatchers.Default
    )

    private val loaded = MutableStateFlow(false)

    // The filter shown; null until the opening filter is chosen from the saved one (Q1, L8-D3).
    private val filter = MutableStateFlow<LiveTvFilter?>(null)

    // The pickers' values: the last Browse selected; Favorites and Recents leave it as it was (Spec 38 Q1).
    private val browse = MutableStateFlow(LiveTvFilter.initial)

    // List or grid (Spec 67 D7 Q1); set from the saved one before the first rows show.
    private val view = MutableStateFlow(LiveTvView.LIST)

    // The last row focused (the info block, focus on return); seeded with the last-watched channel (Q1).
    private val _focusedKey = MutableStateFlow<String?>(null)
    val focusedKey: StateFlow<String?> = _focusedKey

    private var previewJob: Job? = null

    // The channel tuned before the current one (held Back in full screen; the zap banner's "HOLD BACK Last channel"); in memory (L8-D7).
    private val _previousKey = MutableStateFlow<String?>(null)
    val previousKey: StateFlow<String?> = _previousKey

    private val sourceIds = sourceManager.sources.map { list -> list.map { it.id } }.distinctUntilChanged()
    private val guideCountries = sourceManager.sources.map(LiveTvModel::guideCountries).distinctUntilChanged()
    private val hasChannelSources = sourceManager.sources
        .map { list -> list.any { it.kind != SourceKind.XMLTV } }
        .distinctUntilChanged()

    /**
     * Null until the stores have loaded and the first lineup is derived from them, so the screen
     * never shows an empty or guide-less list on open. Keyed on source ids only: a source's
     * status writes during refresh do not rerun enrichment. Shared: rows and matching both read it.
     */
    private val lineup: StateFlow<Lineup?> = combine(
        loaded,
        channelRepository.channels,
        sourceIds,
        metadataRepository.metadata,
        hiddenStore.keys
    ) { loaded, all, ids, meta, hidden -> if (loaded) LiveTvModel.lineup(all, ids, meta, hidden) else null }
        .flowOn(derive)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The profile's favorites, recents and last filter; null until the store has emitted. */
    private val userState: StateFlow<LiveTvUserState?> = userStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The favorite keys as stored: the side panel's toggle (its row snapshot would go stale). Eager,
     * so the panel's first frame has the real set, not an empty one ("Add" flashing on a favorite).
     */
    val favoriteKeys: StateFlow<Set<String>> = userState
        .map { it?.favorites?.toSet().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Each source's name by id: a lost row's "<source> no longer lists it" (L8-D4). */
    val sourceNames: StateFlow<Map<String, String>> = sourceManager.sources
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    // Recents in the order shown, taken at open and each time Recents is selected again: a live order
    // would re-sort the list under the user 10 s into each channel, and zapping would bounce between
    // its top two rows. The store keeps the live order (L8-D2).
    private val shownRecents = MutableStateFlow<List<String>?>(null)

    /** The lineup with the profile's lists resolved against it; null until both exist. */
    private val listed: StateFlow<Pair<Lineup, UserLists>?> = combine(lineup, userState, shownRecents) { l, user, shown ->
        if (l == null || user == null) null else l to userLists(l, user, shown)
    }.flowOn(derive)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun userLists(lineup: Lineup, user: LiveTvUserState, recents: List<String>? = null): UserLists =
        LiveTvModel.userLists(lineup, user.favorites, recents ?: user.recentKeys)

    /** Null until the first lineup is matched against the guides. */
    private val refs: StateFlow<Map<String, EpgRef>?> = combine(
        lineup.filterNotNull(),
        guideCountries,
        guideRepository.guides,
        channelRepository.overrides
    ) { l, countries, guides, overrides -> LiveTvModel.guideRefs(l.channels, countries, guides, overrides) }
        .flowOn(derive)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Wall clock at each minute boundary: now/next and progress move with it. One ticker for rows
     * and info; no replay once it stopped (a reopen derives from the current time, not a stale one).
     */
    private val minute: SharedFlow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    private val guidesAtMinute = combine(guideRepository.guides, minute, ::Pair)

    private data class Selection(
        val filter: LiveTvFilter?,
        val browse: LiveTvFilter.Browse,
        val view: LiveTvView,
        val pickers: Pickers?
    )

    // The pickers' options derive with their browse in one step (never a frame of the old counts) and
    // apart from the minute ticker, which never changes them.
    private val selection = combine(filter, browse, view, listed) { filter, browse, view, listed ->
        Selection(filter, browse, view, listed?.let { (lineup, lists) -> LiveTvModel.pickers(lineup, browse, lists) })
    }

    // The grid's layout and what it was made from: laid out again only when one of them changes, not
    // each minute (bounds move at local midnight). Read and written only by uiState's derivation,
    // which runs one emission at a time.
    private data class GuideInputs(
        val channels: List<Channel>,
        val refs: Map<String, EpgRef>,
        val guides: Map<String, EpgSnapshot>,
        val bounds: GuideBounds
    )
    private var guideLayout: Pair<GuideInputs, GuideRows>? = null

    // Null in List, which also lets the last layout go.
    private fun guideRows(
        view: LiveTvView,
        channels: List<Channel>,
        refs: Map<String, EpgRef>,
        guides: Map<String, EpgSnapshot>,
        nowMs: Long
    ): GuideRows? {
        if (!view.isGrid) {
            guideLayout = null
            return null
        }
        val inputs = GuideInputs(channels, refs, guides, LiveTvGuideGrid.bounds(nowMs, ZoneId.systemDefault()))
        guideLayout?.let { (made, rows) -> if (made == inputs) return rows }
        return LiveTvGuideGrid.rows(channels, refs, guides, inputs.bounds).also { guideLayout = inputs to it }
    }

    val uiState: StateFlow<LiveTvUiState> = combine(
        hasChannelSources,
        listed,
        refs,
        selection,
        guidesAtMinute
    ) { hasChannelSources, listed, refs, (filter, browse, view, pickers), (guides, nowMs) ->
        if (listed == null || refs == null || filter == null) {
            LiveTvUiState(loading = true, filter = filter ?: LiveTvFilter.initial, browse = browse, view = view)
        } else {
            val (lineup, lists) = listed
            val channels = LiveTvModel.filtered(lineup, filter, lists)
            LiveTvUiState(
                loading = false,
                hasChannelSources = hasChannelSources,
                filter = filter,
                counts = LiveTvModel.counts(lists),
                browse = browse,
                pickers = pickers,
                categoryLabels = lineup.categoryLabels,
                rows = LiveTvModel.rows(channels, lineup.numbers, refs, guides, nowMs, lists.favoriteKeys),
                nowMs = nowMs,
                view = view,
                guide = guideRows(view, channels, refs, guides, nowMs)
            )
        }
    }.flowOn(derive)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LiveTvUiState())

    /** The info block's channel (D16): the focused row, whatever the filter; null before any focus. */
    val info: StateFlow<LiveRow?> = combine(
        lineup,
        refs,
        _focusedKey,
        guidesAtMinute
    ) { lineup, refs, key, (guides, nowMs) ->
        val channel = key?.let { lineup?.channel(it) }
        if (lineup == null || refs == null || channel == null) null
        else LiveTvModel.rows(listOf(channel), lineup.numbers, refs, guides, nowMs).single()
    }.flowOn(derive)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    // The open day schedule (Spec 67 G2): its channel and a per-open id; null = closed. The id makes a
    // close-then-reopen of one channel a new value, even when the null between them is conflated.
    private data class ScheduleOpen(val key: String, val id: Int)
    private val scheduleOpen = MutableStateFlow<ScheduleOpen?>(null)
    private var nextScheduleId = 0

    /**
     * The open day schedule (Spec 67 G2, D3): the channel's guide programmes as today and tomorrow in
     * the device zone, re-derived each minute. Null when closed, or once the channel or its guide is gone.
     */
    val schedule: StateFlow<LiveSchedule?> = combine(
        combine(lineup, refs, ::Pair),
        scheduleOpen,
        sourceNames,
        guidesAtMinute
    ) { (lineup, refs), open, names, (guides, nowMs) ->
        val key = open?.key
        val channel = key?.let { lineup?.channel(it) }
        val ref = key?.let { refs?.get(it) }
        val programmes = ref?.let { guides[it.epgSourceId]?.programmes?.get(it.epgChannelId) }
        if (lineup == null || refs == null || channel == null || ref == null || programmes == null) {
            null
        } else {
            val zone = ZoneId.systemDefault()
            LiveSchedule(
                openId = open.id,
                row = LiveTvModel.rows(listOf(channel), lineup.numbers, refs, guides, nowMs).single(),
                guideName = names[ref.epgSourceId],
                today = LiveTvSchedule.day(programmes, nowMs, zone, 0),
                tomorrow = LiveTvSchedule.day(programmes, nowMs, zone, 1),
                nowMs = nowMs
            )
        }
    }.flowOn(derive)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            hiddenStore.load()
            sourceManager.load()
            loaded.value = true
            sourceManager.refreshStale()
        }
        // Zapping reads the cooling records before any tune here has loaded them.
        viewModelScope.launch { health.load() }
        viewModelScope.launch {
            player.state
                .map { it is LiveTuneState.Tuning || it is LiveTuneState.Playing }
                .distinctUntilChanged()
                .collect(screensaver::setPlaybackActive)
        }
        // Q1 (L8-D3): open on the saved filter (else Favorites when one is listed, else All),
        // focus on the last-watched channel. The rows wait for this (filter null = loading).
        viewModelScope.launch {
            val (lineup, user) = combine(lineup.filterNotNull(), userState.filterNotNull(), ::Pair).first()
            val lists = userLists(lineup, user)
            shownRecents.value = user.recentKeys
            // The newest recent still held (a hidden or pruned head is passed over).
            if (_focusedKey.value == null) _focusedKey.value = lists.recents.firstOrNull()?.key
            view.value = LiveTvView.fromId(user.lastView)
            selectFilter(LiveTvFilter.opening(user.lastFilter, lists.favorites.isNotEmpty()))
        }
        // A pill selects on focus: save the one focus settles on, not every pill passed.
        viewModelScope.launch {
            filter.filterNotNull().drop(1).collectLatest { f ->
                delay(FILTER_SAVE_MS)
                userStore.setLastFilter(f.id)
            }
        }
        // Recents (L8-D2): a channel counts as watched after playing RECENT_AFTER_MS straight.
        viewModelScope.launch {
            player.state
                .map { (it as? LiveTuneState.Playing)?.channel?.key }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) return@collectLatest
                    delay(RECENT_AFTER_MS)
                    userStore.recordWatched(key, System.currentTimeMillis())
                }
        }
    }

    fun selectFilter(filter: LiveTvFilter) {
        if (filter == LiveTvFilter.Recents && this.filter.value != LiveTvFilter.Recents) {
            userState.value?.let { shownRecents.value = it.recentKeys }
        }
        if (filter is LiveTvFilter.Browse) browse.value = filter
        this.filter.value = filter
    }

    /** The View picker (Spec 67 D7 Q1): shows [view] and keeps it for the profile's next visit. */
    fun selectView(view: LiveTvView) {
        if (this.view.value == view) return
        this.view.value = view
        viewModelScope.launch { userStore.setLastView(view.id) }
    }

    /**
     * A row took focus: the info block follows now, the preview once focus rests (Q2). A lost
     * channel's row never tunes: the preview keeps its channel (L8-D4).
     */
    fun onRowFocused(channel: Channel) {
        _focusedKey.value = channel.key
        previewJob?.cancel()
        if (isTunedTo(channel) || channel.lost) return
        previewJob = viewModelScope.launch {
            delay(PREVIEW_REST_MS)
            tune(channel)
        }
    }

    /** OK on a row, Next channel, a zap: tune at once (no rest). False for a lost channel (L8-D4). */
    fun tuneNow(channel: Channel): Boolean {
        // Before focus moves: Back to a lost last channel must leave focus on the tuned one.
        if (channel.lost) return false
        _focusedKey.value = channel.key
        previewJob?.cancel()
        if (!isTunedTo(channel)) tune(channel)
        return true
    }

    /**
     * Up/Down and CH ± in full screen, CH ± in the list, Next channel (Q3, L8-D6): tunes the shown
     * filter's row [step] from the tuned channel, passing over lost channels and channels whose
     * every stream is cooling (D16; a channel that just gave up has its streams recorded as
     * failed). Returns the tuned key; null when no other channel qualifies.
     */
    fun zap(step: Int): String? {
        val from = player.state.value.channel?.key ?: _focusedKey.value
        val now = System.currentTimeMillis()
        val records = health.records.value
        val target = LiveTvModel.zapRow(uiState.value.rows, from, step) {
            it.lost || StreamHealth.allCooling(it.streams, records, now)
        } ?: return null
        tuneNow(target.channel)
        return target.channel.key
    }

    /** Held Back in full screen: the channel tuned before (any filter). False when there is none (or it is lost). */
    fun lastChannel(): Boolean {
        val channel = _previousKey.value?.let(::channel) ?: return false
        return tuneNow(channel)
    }

    /** A listable or lost channel by key (the banner's last channel); null before the first lineup. */
    fun channel(key: String): Channel? = lineup.value?.channel(key)

    /** Every tune goes through here: a different channel makes the current one the last channel. */
    private fun tune(channel: Channel) {
        player.state.value.channel?.key?.takeIf { it != channel.key }?.let { _previousKey.value = it }
        player.tune(channel)
    }

    /**
     * Right on a row with a schedule (Spec 67 D3 Q1): starts deriving [channel]'s day schedule and
     * tunes it at once (the head shows its picture; focus leaving the list would cancel a resting
     * preview). Returns the open's id (the screen shows the column for that id only); null for a
     * lost channel.
     */
    fun openSchedule(channel: Channel): Int? {
        if (!tuneNow(channel)) return null
        val id = ++nextScheduleId
        scheduleOpen.value = ScheduleOpen(channel.key, id)
        return id
    }

    /**
     * Stops deriving the schedule. The screen owns whether the column shows (Spec 67 D5): it calls
     * this when it closes the column, so a guide that returns later never reopens it.
     */
    fun closeSchedule() {
        scheduleOpen.value = null
    }

    /** Focus left the list: a preview still waiting for focus to rest never starts (Q2). */
    fun cancelPendingPreview() {
        previewJob?.cancel()
    }

    fun retry() {
        previewJob?.cancel()
        player.retry()
    }

    /** The filter selected now; [LiveTvUiState.filter] follows once its rows are derived. */
    val selectedFilter: LiveTvFilter get() = filter.value ?: LiveTvFilter.initial

    /** A channel's Q6 number (the preview's "CH n"); null before the first lineup or for an unknown key. */
    fun number(key: String): String? = lineup.value?.numbers?.get(key)

    /** The screen left view (another tab, app in background): no sound from an unseen preview. */
    fun stopPreview() {
        previewJob?.cancel()
        player.stop()
    }

    /** The screen is back: the focused channel plays again ([stopPreview] left the player idle). */
    fun resumePreview() {
        val key = _focusedKey.value ?: return
        if (player.state.value !is LiveTuneState.Idle) return
        val channel = lineup.value?.channel(key)?.takeUnless { it.lost } ?: return
        tune(channel)
    }

    /**
     * Whether [channel] is what the player holds: tuning, playing or given up on. An unavailable
     * channel is not retried by focus alone; Retry does that.
     */
    private fun isTunedTo(channel: Channel): Boolean = player.state.value.channel?.key == channel.key

    /** Health records by stream (the side panel's "worked at" / "failed at"). */
    val streamHealth: StateFlow<Map<String, StreamHealthRecord>> get() = health.records

    /** The side panel's guide facts for channel [key]; null when the lineup no longer holds it (lost ones included). */
    suspend fun guidePanel(key: String): LiveGuidePanel? = withContext(derive) {
        val channel = lineup.value?.channel(key) ?: return@withContext null
        val guides = guideRepository.guides.value
        val sources = sourceManager.sources.value
        val epgSources = LiveTvModel.epgSources(LiveTvModel.guideCountries(sources), guides)
        // A ref to a guide no longer cached reads as no guide (LiveTvModel.rows).
        val current = refs.value?.get(key)?.takeIf { it.epgSourceId in guides }
        val nowMs = System.currentTimeMillis()
        LiveGuidePanel(
            current = current,
            pickedNone = channelRepository.overrides.value[key]?.let { it.ref == null } == true,
            currentNow = current?.let { guides[it.epgSourceId]?.nowNext(it.epgChannelId, nowMs)?.first?.title },
            guides = epgSources.size,
            candidates = GuideCandidates.rank(channel, epgSources, current, nowMs),
            sourceNames = sources.associate { it.id to it.name }
        )
    }

    /** Choose guide (D16): [ref] for channel [key], null = No guide; wins over matching and survives refreshes. */
    fun chooseGuide(key: String, ref: EpgRef?) {
        viewModelScope.launch {
            try {
                channelRepository.setOverride(key, EpgOverride(ref))
            } catch (e: IOException) {
                // Unwritten: the guide stays as it was; nothing to show beyond that.
                Log.w(TAG, "Guide choice not saved: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The side panel's "Stream n of m": the next stream of the tuned channel (D16). */
    fun nextStream() {
        player.nextStream()
    }

    /** The side panel's first row (D16, L8-D5): adds channel [key] to Favorites or removes it. */
    fun toggleFavorite(key: String) {
        viewModelScope.launch { userStore.toggleFavorite(key) }
    }

    /** Hide channel (D16): gone from every filter; unhidden from Sources (L7e). */
    fun hideChannel(key: String) {
        viewModelScope.launch { hiddenStore.setHidden(key, true) }
    }

    private val _packOffer = MutableStateFlow(PackOffer())

    /** The empty state's channel pack offer (Spec 73 D2): figures, Add channel pack's progress and failure. */
    val packOffer: StateFlow<PackOffer> = _packOffer

    // No replay: this ViewModel outlives the screen (tab state is saved), so an add that ends while
    // Live TV is not shown must not open Sources when the viewer comes back.
    private val packAddedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once the pack is added from the empty state, only to a screen collecting it: it opens Sources (Spec 73 D3). */
    val packAdded: Flow<Unit> = packAddedEvents

    private var summaryJob: Job? = null

    /**
     * The empty state is shown (Spec 73 D2): clears a failure from an earlier visit and reads the
     * pack's figures unless they are already read or being read (a failed read is retried here).
     */
    fun loadPackSummary() {
        _packOffer.update { it.copy(error = null) }
        if (summaryJob?.isActive == true || _packOffer.value.summary != null) return
        summaryJob = viewModelScope.launch {
            val summary = sourceManager.packSummary()
            _packOffer.update { it.copy(summary = summary) }
        }
    }

    /**
     * Empty state "Add channel pack" (Spec 73 D3): downloads and adds the pack, then [packAdded];
     * a failure keeps the empty state with its reason. A second press while it runs does nothing.
     */
    fun addPack() {
        if (_packOffer.value.adding) return
        _packOffer.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = sourceManager.addPack()) {
                    is LiveSourceManager.AddResult.Added -> packAddedEvents.tryEmit(Unit)
                    is LiveSourceManager.AddResult.Refused -> _packOffer.update { it.copy(error = result.reason) }
                }
            } finally {
                _packOffer.update { it.copy(adding = false) }
            }
        }
    }

    /** The empty state's primary action (Q7): the broadcaster preset (its guides are a Sources suggestion, Spec 49 D27 F5). */
    fun addBroadcasters() {
        viewModelScope.launch {
            sourceManager.addBroadcasters()
            sourceManager.refreshStale()
        }
    }

    override fun onCleared() {
        screensaver.setPlaybackActive(false)
        player.release()
    }

    private companion object {
        const val TAG = "LiveTvViewModel"
        const val MINUTE_MS = 60_000L
        const val PREVIEW_REST_MS = 600L
        const val STOP_TIMEOUT_MS = 5_000L
        const val FILTER_SAVE_MS = 500L
        const val RECENT_AFTER_MS = 10_000L
    }
}
