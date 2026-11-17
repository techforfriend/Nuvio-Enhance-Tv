package com.nuvio.tv.livetv.player

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nuvio.tv.R
import com.nuvio.tv.core.player.AudioOutputPlan
import com.nuvio.tv.core.player.AudioOutputRouteDetector
import com.nuvio.tv.core.player.DeniedTranscodePlanner
import com.nuvio.tv.core.player.GainAudioProcessor
import com.nuvio.tv.core.player.PLAYER_RELEASE_TIMEOUT_MS
import com.nuvio.tv.core.player.SubtitleOffsetRenderersFactory
import com.nuvio.tv.core.player.audioPassthroughPolicyOf
import com.nuvio.tv.core.player.effectiveDecoderPriority
import com.nuvio.tv.core.player.findCause
import com.nuvio.tv.core.player.learnedDeniedAudioGroups
import com.nuvio.tv.core.player.movieAudioAttributes
import com.nuvio.tv.core.player.resolveDeviceAudioLanguages
import com.nuvio.tv.core.player.resolvePreferredAudioLanguages
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.resolve.StreamResolver
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceFetchException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** What the Live TV screen shows for the tuned channel (D16 tuning / unavailable states). */
sealed interface LiveTuneState {
    /** The channel this state is about; null only when [Idle]. */
    val channel: Channel?

    data object Idle : LiveTuneState {
        override val channel: Channel? get() = null
    }

    /**
     * Trying stream [stream] (1-based, the channel's source order, as [LiveStreamPick.position]) of
     * [streams]; both 0 while the addon lookup runs. [keySystem] = the stream's licence key system
     * (a `LiveDrm` id, Spec 74 D6), null for a clear stream.
     */
    data class Tuning(
        override val channel: Channel,
        val stream: Int,
        val streams: Int,
        val keySystem: String? = null
    ) : LiveTuneState

    data class Playing(override val channel: Channel) : LiveTuneState

    /**
     * The channel gave up: [tried] of [streams] were attempted (fewer when the 30 s budget ran out,
     * 0 when the lookup failed or none exist); [failure] is the last one, [atMs] wall-clock.
     * [licensed] of the [streams] need a DRM licence (Spec 74 D4).
     */
    data class Unavailable(
        override val channel: Channel,
        val tried: Int,
        val streams: Int,
        val failure: LiveFailure,
        val atMs: Long,
        val licensed: Int = 0
    ) : LiveTuneState
}

/**
 * The stream being tried or played (D16 side panel "Stream n of m"): [position] (1-based) of [count]
 * in the channel's playable streams, source order. [mimeType] is null until resolved, and for a
 * progressive stream.
 */
data class LiveStreamPick(
    val channelKey: String,
    val stream: LiveStream,
    val position: Int,
    val count: Int,
    val mimeType: String? = null
)

/**
 * The dedicated Live TV player (Spec 34 O1 (a), L6d): one ExoPlayer for preview and full screen,
 * built once on the first tune from the shared renderer and audio setup, and driven by
 * [LiveTuneLadder] with the live overrides of D7 by construction (ExoPlayer only, live sources and
 * client, load control pinned, no AFR preflight, no watch progress). Owned by one screen: the
 * owner calls [release] once, never from a `Player.Listener` callback (M3-PLAYER-02). Every
 * method runs on the main thread.
 *
 * The audio route (Bluetooth or not, the learned-denial route key) is read at build time; a route
 * change applies from the next [LivePlayer] (the VOD in-place route switch is not repeated here).
 */
@OptIn(UnstableApi::class)
class LivePlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: LiveSourceManager,
    private val health: StreamHealthStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val resolver: StreamResolver,
    private val playbackActivity: PlaybackActivity,
    private val network: LiveNetwork
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<LiveTuneState>(LiveTuneState.Idle)
    val state: StateFlow<LiveTuneState> = _state.asStateFlow()

    init {
        // A tuning or playing channel pauses the channel pack scan (Spec 73 S3a); release clears it.
        scope.launch {
            _state.map { it is LiveTuneState.Tuning || it is LiveTuneState.Playing }
                .distinctUntilChanged()
                .collect { playbackActivity.setLive(this@LivePlayer, it) }
        }
    }

    private val _rateFamily = MutableStateFlow<LiveFrameRate.Family?>(null)

    /**
     * The playing stream's frame-rate family (Spec 94 T4); null while tuning or not yet known. Reset by
     * each tune and each stream load; the screen switches the display from it.
     */
    val rateFamily: StateFlow<LiveFrameRate.Family?> = _rateFamily.asStateFlow()

    /** Frame rate matching is on (Spec 94 D1: the Playback setting governs Live TV too); null until read. */
    val frameRateMatching: StateFlow<Boolean?> = playerSettingsDataStore.playerSettings
        .map<PlayerSettings, Boolean?> { it.frameRateMatchingMode != FrameRateMatchingMode.OFF }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Measures [rateFamily] while a stream plays; cancelled with the tune or the stream. */
    private var rateJob: Job? = null

    init {
        // The setting may change while this player lives (it is built once): keep Media3's strategy in step.
        scope.launch {
            frameRateMatching.collect { on -> if (on != null) exo?.setVideoChangeFrameRateStrategy(LiveFrameRate.strategy(on)) }
        }
    }

    private val _player = MutableStateFlow<Player?>(null)

    /** The player for the screen's view (L7); null until the first tune has built it. */
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val built = scope.async(start = CoroutineStart.LAZY) {
        build(playerSettingsDataStore.playerSettings.first()).also {
            exo = it
            _player.value = it
        }
    }

    /** Set once [built] completes; null before and after [release]. */
    private var exo: ExoPlayer? = null

    /** One tune: the channel, its playable streams (source order), the try order, and the ladder over it. */
    private class Session(
        val channel: Channel,
        val playable: List<LiveStream>,
        val streams: List<LiveStream>,
        val ladder: LiveTuneLadder
    ) {
        /** How many times a stream of this session reached READY: an outage answer older than a READY is moot (L1). */
        var readies = 0

        /** Try-order [index] as its 1-based place in [playable] (what the screen numbers). */
        fun position(index: Int): Int = playable.indexOfFirst { it === streams[index] } + 1

        fun tuning(index: Int): LiveTuneState.Tuning =
            LiveTuneState.Tuning(channel, position(index), streams.size, LiveDrm.from(streams[index].kodiProps)?.keySystem)
    }

    private var session: Session? = null
    private var lastChannel: Channel? = null

    /** [lastChannel]'s playable streams, source order; kept after it gave up so [nextStream] still works. */
    private var lastPlayable: List<LiveStream>? = null

    private val _stream = MutableStateFlow<LiveStreamPick?>(null)

    /** The stream of the last tune being tried or played, the last one tried once it gave up; null while idle or looking up. */
    val stream: StateFlow<LiveStreamPick?> = _stream.asStateFlow()

    /** Stream lookup, then each stream's resolve and mime sniff; cancelled by the next tune or [stop]. */
    private var tuneJob: Job? = null
    private var loadJob: Job? = null
    private var timerJob: Job? = null

    /** Waiting out a device outage before tuning the channel again (Spec 90 L1); cancelled with the tune. */
    private var waitJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val s = session ?: return
            val now = SystemClock.elapsedRealtime()
            when (playbackState) {
                Player.STATE_READY -> {
                    s.readies++
                    if (_state.value is LiveTuneState.Tuning) {
                        _state.value = LiveTuneState.Playing(s.channel)
                        val stream = s.streams[s.ladder.index]
                        scope.launch { health.recordPlayed(stream, System.currentTimeMillis()) }
                    }
                    if (_rateFamily.value == null && rateJob?.isActive != true) measureRate(s)
                    apply(s, s.ladder.onReady(now))
                }
                Player.STATE_BUFFERING -> apply(s, s.ladder.onBuffering(now))
                Player.STATE_ENDED -> apply(s, s.ladder.onEnded(now))
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = session ?: return
            val (classified, behindLiveWindow) = LiveTuneLadder.classify(error)
            // The words of a licence failure name the stream's key system (Spec 74 D5).
            val failure = if (classified.kind == FailureKind.DRM) {
                val drm = LiveDrm.from(s.streams[s.ladder.index].kodiProps)
                // Media3 answers an encrypted stream the playlist gave no licence for with 6001 too.
                if (drm == null) classified.copy(drm = DrmFailure.NO_LICENCE) else classified.copy(keySystem = drm.keySystem)
            } else {
                classified
            }
            // Spec 90 L5: an Xtream panel's refusal reloads after a wait for the old connection to close.
            val refusal = LiveTuneLadder.panelRefusal(failure, s.streams[s.ladder.index].url)
            val servedTs = error.findCause<HlsBodyIsTs>() != null
            val step = s.ladder.onError(refusal ?: failure, behindLiveWindow, SystemClock.elapsedRealtime(), servedTs)
            apply(s, step, reloadDelayMs = if (refusal != null) LiveTuneLadder.PANEL_RETRY_DELAY_MS else 0L)
        }
    }

    /** Tunes [channel], dropping whatever was playing or tuning. */
    fun tune(channel: Channel) {
        cancelTune()
        lastChannel = channel
        lastPlayable = null
        _stream.value = null
        _state.value = LiveTuneState.Tuning(channel, 0, 0)
        // D7: the 30 s budget runs from the tune, the addon lookup included.
        val tunedAtMs = SystemClock.elapsedRealtime()
        tuneJob = scope.launch {
            val player = built.await()
            player.stop()
            val streams = try {
                sources.streamsFor(channel)
            } catch (e: IOException) {
                // Spec 90 L1: an outage is waited out, not shown as a failed lookup.
                if (!network.online()) {
                    waitForNetwork(channel)
                    return@launch
                }
                // Only SourceFetchException's fixed reasons reach the screen; raw IOException text may carry the addon URL.
                unavailable(channel, 0, 0, LiveFailure(FailureKind.NETWORK, detail = (e as? SourceFetchException)?.message))
                return@launch
            }
            // Licensed streams are tried like any other (73/D10, Spec 74 S2b): the licence server answers.
            health.load()
            val ordered = StreamHealth.order(streams, health.records.value, System.currentTimeMillis())
            startSession(channel, streams, ordered, tunedAtMs)
        }
    }

    /**
     * The side panel's "Stream n of m" OK (D16): plays the stream after [stream] in source order
     * (wrapping) with a fresh ladder and budget, falling back through the ones after it as a tune
     * does. Also from a channel that gave up. False when the last tune has no other stream.
     */
    fun nextStream(): Boolean {
        val pick = _stream.value ?: return false
        val channel = lastChannel?.takeIf { it.key == pick.channelKey } ?: return false
        val playable = lastPlayable?.takeIf { it.size > 1 } ?: return false
        if (exo == null) return false
        val from = pick.position % playable.size
        cancelTune()
        startSession(channel, playable, playable.drop(from) + playable.take(from), SystemClock.elapsedRealtime())
        return true
    }

    /** Tunes the last channel again (the Unavailable state's Retry). */
    fun retry() {
        lastChannel?.let(::tune)
    }

    /** Stops playback and tuning; the player stays built for the next [tune]. */
    fun stop() {
        cancelTune()
        exo?.stop()
        _stream.value = null
        _state.value = LiveTuneState.Idle
    }

    /** Releases the player; this instance is done. Never call from a player callback. */
    fun release() {
        cancelTune()
        exo?.run {
            removeListener(listener)
            release()
        }
        exo = null
        _player.value = null
        _stream.value = null
        _state.value = LiveTuneState.Idle
        scope.cancel()
        playbackActivity.setLive(this, false)
    }

    private fun startSession(channel: Channel, playable: List<LiveStream>, ordered: List<LiveStream>, tunedAtMs: Long) {
        lastPlayable = playable
        val ladder = LiveTuneLadder(ordered.size, tunedAtMs)
        val s = Session(channel, playable, ordered, ladder)
        session = s
        apply(s, ladder.start(SystemClock.elapsedRealtime()))
    }

    private fun cancelTune() {
        tuneJob?.cancel()
        loadJob?.cancel()
        timerJob?.cancel()
        waitJob?.cancel()
        tuneJob = null
        loadJob = null
        timerJob = null
        waitJob = null
        session = null
        resetRate()
    }

    private fun resetRate() {
        rateJob?.cancel()
        rateJob = null
        _rateFamily.value = null
    }

    /**
     * Spec 94 T4: [s]'s stream family, from its format's frame rate when the stream declares one (HLS
     * `FRAME-RATE`), else counted from the video decoder ([LiveFrameRate.Meter]) every
     * [RATE_SAMPLE_MS] of real playback. Stops once known: a channel keeps its rate. Logged for the
     * Shield acceptance (no URLs or channel data).
     */
    private fun measureRate(s: Session) {
        val player = exo ?: return
        rateJob = scope.launch {
            val probe = LiveFrameRate.Probe()
            while (session === s) {
                val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
                val frames = counters?.let {
                    it.renderedOutputBufferCount.toLong() + it.droppedBufferCount + it.skippedOutputBufferCount
                }
                val settled = probe.step(
                    declaredFps = player.videoFormat?.frameRate,
                    frames = frames,
                    playing = player.isPlaying,
                    mediaMs = streamPositionMs(player),
                    wallMs = SystemClock.elapsedRealtime()
                )
                if (settled != null) {
                    Log.i(TAG, "Rate family ${settled.family} (${if (settled.declared) "declared" else "measured"})")
                    _rateFamily.value = settled.family
                    return@launch
                }
                delay(RATE_SAMPLE_MS)
            }
        }
    }

    private val rateWindow = Timeline.Window()

    /**
     * Media time that only moves with playback (the meter's clock): the position from the start of the
     * stream's period. A live window's own position drops back each time the playlist slides.
     */
    private fun streamPositionMs(player: Player): Long {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return player.currentPosition
        return timeline.getWindow(player.currentMediaItemIndex, rateWindow).positionInFirstPeriodMs + player.currentPosition
    }

    /**
     * Spec 90 L1 (D1, D5): the device is offline, so nothing is held against the channel's streams.
     * The tune stops, the screen keeps "Tuning <channel>", and the network is asked again on
     * [LiveNetwork.retryDelayMs]; the first yes tunes the channel again (fresh lookup, ladder and
     * budget). Any tune, [stop] or [release] cancels the wait.
     */
    private fun waitForNetwork(channel: Channel) {
        cancelTune()
        exo?.stop()
        _state.value = LiveTuneState.Tuning(channel, 0, 0)
        waitJob = scope.launch {
            var attempt = 0
            while (true) {
                delay(LiveNetwork.retryDelayMs(attempt++))
                if (network.online()) {
                    tune(channel)
                    return@launch
                }
            }
        }
    }

    /**
     * Spec 90 L1: runs [onOnline] when the device reaches the internet. Offline: waits the outage
     * out if [s] is still the session and none of its streams reached READY since the question
     * (`LiveNetwork.outcome`); else does nothing. Used before an outage-shaped failure is recorded
     * against a stream or ends the channel.
     */
    private fun whenOnline(s: Session, onOnline: () -> Unit) {
        val readiesAsked = s.readies
        scope.launch {
            val online = network.online()
            when (LiveNetwork.outcome(online, sameSession = session === s, playing = s.readies != readiesAsked)) {
                LiveNetwork.Outcome.PROCEED -> onOnline()
                LiveNetwork.Outcome.WAIT -> waitForNetwork(s.channel)
                LiveNetwork.Outcome.NONE -> Unit
            }
        }
    }

    /**
     * Does what the ladder answered, records an abandoned stream, and re-arms the timer. A Reload
     * waits [reloadDelayMs] first.
     */
    private fun apply(s: Session, step: LadderStep, reloadDelayMs: Long = 0L) {
        if (session !== s) return
        val abandoned = step.abandoned
        val failure = step.failure
        if (abandoned != null && failure != null) {
            val stream = s.streams[abandoned]
            val record = { scope.launch { health.recordFailed(stream, failure.kind, System.currentTimeMillis()) } }
            // The ladder goes on meanwhile; an offline answer stops it (Spec 90 L1).
            if (LiveNetwork.isNetworkClass(failure)) whenOnline(s) { record() } else record()
            // An answer without a probe (no network link) can run inline on Main.immediate and has then
            // already ended this session with the wait.
            if (session !== s) return
        }
        val player = exo ?: return
        when (val action = step.action) {
            is LadderAction.Play -> load(s, action.index)
            LadderAction.Reload -> load(s, s.ladder.index, fresh = true, delayMs = reloadDelayMs, progressive = s.ladder.asProgressive)
            LadderAction.ReloadAsProgressive -> load(s, s.ladder.index, progressive = true)
            LadderAction.SeekToDefault -> {
                _state.value = s.tuning(s.ladder.index)
                player.seekToDefaultPosition()
                player.prepare()
            }
            is LadderAction.Unavailable -> {
                player.stop()
                // A load still resolving must not start playback behind a pending decision.
                loadJob?.cancel()
                loadJob = null
                val tried = if (s.streams.isEmpty()) 0 else s.ladder.index + 1
                val giveUp = {
                    session = null
                    unavailable(s.channel, tried, s.streams.size, action.failure, licensed = s.streams.count { it.drm })
                }
                // Spec 90 L1: the session stays, shown as "Tuning" (D1), until the network answers.
                if (LiveNetwork.isNetworkClass(action.failure)) {
                    _state.value = s.tuning(s.ladder.index)
                    whenOnline(s) { if (session === s) giveUp() }
                } else {
                    giveUp()
                }
            }
            LadderAction.None -> Unit
        }
        armTimer(s)
    }

    /**
     * Stream [index] from a fresh source at the live edge (a Play and a Reload alike). The stored
     * stream stays the identity (pick, health); what plays is its [StreamResolver] answer, resolved
     * inside the ladder's watchdog; [fresh] (a Reload) skips cached tokens (Spec 72 D1). [delayMs]
     * waits after the stop, inside the watchdog too (a panel refusal's retry, Spec 90 L5).
     * [progressive] skips the mime resolve: the HLS link sent MPEG-TS (Spec 90 L2).
     */
    private fun load(s: Session, index: Int, fresh: Boolean = false, delayMs: Long = 0L, progressive: Boolean = false) {
        val player = exo ?: return
        val stream = s.streams[index]
        val position = s.position(index)
        _state.value = s.tuning(index)
        val pick = LiveStreamPick(s.channel.key, stream, position, s.playable.size)
        _stream.value = pick
        player.stop()
        // Another stream may have another rate; the display stays as it is until it is known (D3).
        resetRate()
        loadJob?.cancel()
        // Assigned before the body runs: on Main.immediate it can run inline and reach apply() (an
        // unsupported key system), whose next load must find this job to cancel, not lose its own.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (delayMs > 0) delay(delayMs)
            val resolution = resolver.resolve(stream, fresh)
            if (session !== s) return@launch
            s.ladder.onResolved(resolution.fromCache)
            val request = LiveMediaSources.normalize(resolution.stream)
            // Spec 90 L4: a scheme no source opens fails now, not after the 10 s watchdog.
            LiveMediaSources.unsupportedScheme(request.url)?.let { scheme ->
                val failure = LiveFailure(FailureKind.PARSE, detail = context.getString(R.string.live_tv_error_protocol, scheme))
                apply(s, s.ladder.onError(failure, false, SystemClock.elapsedRealtime()))
                return@launch
            }
            // Licence details from the stored stream: a resolve (Pluto/Plex) answers with a bare URL.
            val drm = LiveDrm.from(stream.kodiProps)
            if (drm != null && !LiveMediaSources.supported(drm)) {
                val failure = LiveFailure(FailureKind.DRM, drm = DrmFailure.UNSUPPORTED, keySystem = drm.keySystem)
                apply(s, s.ladder.onError(failure, false, SystemClock.elapsedRealtime()))
                return@launch
            }
            val client = LiveMediaSources.client(request.headers)
            val mime = if (progressive) null else LiveMediaSources.resolveMimeType(request, client)
            if (session !== s) return@launch
            _stream.value = pick.copy(mimeType = mime)
            val drmManager = drm?.let { LiveMediaSources.drmSessionManager(it, request) }
            player.setMediaSource(LiveMediaSources.create(request, mime, client, s.channel.key, drmManager))
            player.prepare()
            player.playWhenReady = true
        }
        loadJob = job
        job.start()
    }

    /** One timer at the ladder's next deadline (watchdog or budget); none while playing. */
    private fun armTimer(s: Session) {
        timerJob?.cancel()
        timerJob = null
        if (session !== s) return
        val deadline = s.ladder.deadlineMs() ?: return
        timerJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            apply(s, s.ladder.onTick(SystemClock.elapsedRealtime()))
        }
    }

    private fun unavailable(channel: Channel, tried: Int, streams: Int, failure: LiveFailure, licensed: Int = 0) {
        _state.value = LiveTuneState.Unavailable(channel, tried, streams, failure, System.currentTimeMillis(), licensed)
    }

    /**
     * The VOD audio chain minus its per-stream extras: passthrough policy and denied-codec
     * transcode from the user's settings, Bluetooth PCM rules, FFmpeg audio per Decoder Priority;
     * MAT off (TrueHD is a disc format, not broadcast), no subtitle/audio offsets, no DV conversion.
     */
    private fun build(settings: PlayerSettings): ExoPlayer {
        val route = runCatching { AudioOutputRouteDetector.detect(context) }.getOrNull()
        val bluetooth = route?.isBluetooth == true
        val audioOutputPlan = AudioOutputPlan.of(settings, isBluetooth = bluetooth)
        val decoderPriority = effectiveDecoderPriority(
            decoderPriority = settings.decoderPriority,
            preferExtensions = bluetooth,
            forcePassthroughActive = audioOutputPlan.forceOpticalPassthrough
        )
        val passthroughPolicy = audioPassthroughPolicyOf(
            settings = settings,
            effectiveDecoderPriority = decoderPriority,
            learnedDeniedGroups = learnedDeniedAudioGroups(settings.audioRejectionsConfirmed, route?.key)
        )
        val transcodeMimes = DeniedTranscodePlanner.effectiveTranscodeMimes(
            policy = passthroughPolicy,
            transcodeDeniedToAc3 = settings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3,
            forcePassthroughActive = audioOutputPlan.forceOpticalPassthrough
        )
        val renderersFactory = SubtitleOffsetRenderersFactory(
            context = context,
            subtitleDelayUsProvider = { 0L },
            audioDelayUsProvider = { 0L },
            shouldStripSdhProvider = { false },
            isBuiltInSubtitleProvider = { true },
            videoBoundsFractionProvider = { null },
            gainAudioProcessor = GainAudioProcessor(),
            audioOutputPlan = audioOutputPlan,
            deniedTranscodeMimes = transcodeMimes,
            preferFfmpegAudio = false,
            matPassthroughEnabled = false,
            audioPassthroughPolicy = passthroughPolicy,
            bluetoothForcePcm = bluetooth,
            playbackSpeedProvider = { 1f },
            initialForcePcm = bluetooth,
            preferSoftwareAudioOnly = bluetooth,
            onPlaybackSpeedAwareAudioSinkCreated = {},
            onMatRoutingAudioSinkCreated = {},
            onFfmpegAudioRendererChanged = {}
        ).setExtensionRendererMode(decoderPriority)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            val languages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = settings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages()
            )
            setParameters(
                buildUponParameters()
                    .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                    .setTunnelingEnabled(settings.effectiveTunnelingEnabled)
                    .setPreferredAudioLanguages(*languages.toTypedArray())
            )
        }
        // D7: Media3's start thresholds regardless of perf mode or the user's VOD buffer settings.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                START_BUFFER_MS,
                REBUFFER_START_MS
            )
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
            // Spec 94 T4: followed live from frameRateMatching (init); this is the build-time value.
            .setVideoChangeFrameRateStrategy(LiveFrameRate.strategy(settings.frameRateMatchingMode != FrameRateMatchingMode.OFF))
            .build()
            .apply {
                // TV: no focus handling, as VOD: ducking would drop a passthrough bitstream to PCM.
                val handleAudioFocus = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                setAudioAttributes(movieAudioAttributes, handleAudioFocus)
                setHandleAudioBecomingNoisy(true)
                addListener(listener)
            }
    }

    private companion object {
        const val TAG = "LivePlayer"
        const val RATE_SAMPLE_MS = 3_000L
        const val START_BUFFER_MS = 1_000
        const val REBUFFER_START_MS = 2_000
    }
}
