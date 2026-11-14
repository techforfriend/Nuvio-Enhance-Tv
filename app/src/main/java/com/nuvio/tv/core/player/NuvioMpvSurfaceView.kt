package com.nuvio.tv.core.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import kotlin.math.roundToLong

class NuvioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var pendingInitialMediaUrl: String? = null
    private var pendingInitialStartOption: String? = null
    private var hardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
    private var pendingAspectRetryCount = 0
    // readChapters() cache: the list is fixed per load, but the snapshot is read every poll tick.
    private var chapterCacheKey: String? = null
    private var chapterCache: List<PlaybackChapter> = emptyList()
    private val aspectReapplyRunnable = Runnable {
        applyAspectModeInternal(currentAspectMode, allowRetry = true)
    }
    // Spec 85 D3: this core's failed load (END_FILE reason=error) and whether its playback has
    // started (PLAYBACK_RESTART), written on mpv's event thread and read by the controller. One
    // core per start (initializeMpvPlayer releases a live core first), so per-core slots are enough.
    private val loadFailure = AtomicReference<String?>(null)
    private val playbackRestarted = AtomicBoolean(false)
    // Set by stopPlayback (main thread): this core's file was ended on purpose.
    private var stopped = false
    // Runs on mpv's native event thread, holding the observer list's lock that releasePlayer's
    // removeObserver waits on: it must never block or throw.
    private val endFileObserver = object : MPV.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: MPVNode) = Unit
        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> playbackRestarted.set(true)
                MPV.mpvEvent.MPV_EVENT_END_FILE -> runCatching {
                    mpvLoadFailureOf(
                        reason = data.get("reason")?.asString(),
                        fileError = data.get("file_error")?.asString()
                    )
                }.onFailure {
                    Log.w(TAG, "END_FILE decode failed: ${it.message}")
                }.getOrNull()?.let { loadFailure.set(it) }
            }
        }
    }

    fun ensureInitialized() {
        if (initialized) return
        Utils.copyAssets(context)
        initialize(
            configDir = context.filesDir.path,
            cacheDir = context.cacheDir.path
        )
        initialized = true
    }

    fun setMedia(url: String, headers: Map<String, String>, startPositionMs: Long = 0L) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers) +
            "#start=${startPositionMs.coerceAtLeast(0L)}"
        if (hasQueuedInitialMedia && requestKey == lastMediaRequestKey) {
            return
        }
        applyHeaders(headers)
        val startOption = startPositionMs
            .takeIf { it > 0L }
            ?.let { String.format(Locale.US, "start=+%.3f", it / 1000.0) }
        if (startOption != null && holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            loadFileWithOptions(url, startOption)
            hasQueuedInitialMedia = true
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
        } else if (startOption != null) {
            pendingInitialMediaUrl = url
            pendingInitialStartOption = startOption
            hasQueuedInitialMedia = true
        } else if (hasQueuedInitialMedia) {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            if (holder.surface?.isValid == true) {
                ensureSurfaceAttachedIfAlreadyAvailable()
                mpv.command("loadfile", url, "replace")
            } else {
                playFile(url)
            }
        } else {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            playFile(url)
            ensureSurfaceAttachedIfAlreadyAvailable()
            hasQueuedInitialMedia = true
        }
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        val url = pendingInitialMediaUrl ?: return
        val startOption = pendingInitialStartOption
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        if (startOption != null) {
            loadFileWithOptions(url, startOption)
        } else {
            mpv.command("loadfile", url, "replace")
        }
    }

    /**
     * mpv's `loadfile` signature is `<url> [<flags> [<index> [<options>]]]`, so the per-file option
     * list belongs in the fifth argument. Passing it where `<index>` is expected makes mpv reject
     * the whole command and stay idle, i.e. resuming at a position would never load the file.
     */
    private fun loadFileWithOptions(url: String, options: String) {
        mpv.command("loadfile", url, "replace", LOADFILE_DEFAULT_INDEX, options)
    }

    private fun ensureSurfaceAttachedIfAlreadyAvailable() {
        if (!initialized) return
        val currentHolder = holder
        val currentSurface = currentHolder.surface ?: return
        if (!currentSurface.isValid) return
        runCatching {
            // Some fallback transitions initialize mpv after the Surface is already alive.
            // In that path, SurfaceHolder callback may not fire again, so force attach.
            surfaceCreated(currentHolder)
        }.onFailure {
            Log.w(TAG, "Failed to force MPV surface attach: ${it.message}")
        }
    }

    private fun applyDefaultTrackSelectionForNewLoad() {
        runCatching {
            // Let mpv choose the default streams for every new media load.
            mpv.setPropertyString("aid", "auto")
            mpv.setPropertyString("sid", "auto")
            mpv.setPropertyBoolean("sub-visibility", true)
        }.onFailure {
            Log.w(TAG, "Failed to reset default A/V track selection: ${it.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("pause") == false
    }

    /** mpv's `pause` property is set (a user or app pause; a cache stall is [isPausedForCacheNow]). */
    fun isPausedNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("pause") == true
    }

    fun isPausedForCacheNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("paused-for-cache") == true
    }

    fun demuxerCacheDurationSec(): Double {
        if (!initialized) return 0.0
        return mpv.getPropertyDouble("demuxer-cache-duration") ?: 0.0
    }

    fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("core-idle") == true
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        mpv.setPropertyDouble("time-pos", seconds)
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("time-pos/full")
            ?: mpv.getPropertyDouble("time-pos")
            ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("duration/full") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun hasVideoTrackSelectedNow(): Boolean {
        if (!initialized) return false
        val vid = mpv.getPropertyString("vid")?.trim()
        return !vid.isNullOrBlank() && !vid.equals("no", ignoreCase = true)
    }

    /** False once [releasePlayer] destroyed the core (until the next load initializes a new one). */
    fun isCoreAlive(): Boolean = initialized

    /** Spec 85 D3: the failed load mpv reported for this core, once; null when none or stopped. */
    fun takeLoadFailure(): String? = loadFailure.getAndSet(null)?.takeUnless { stopped }

    /**
     * Spec 85: mpv finished this core's first playback start (MPV_EVENT_PLAYBACK_RESTART): audio and
     * video ready, the first frame decoded; it also fires on a paused start (mpv playloop.c
     * handle_playback_restart, audio.c reaches READY on a full queue whether paused or not). Unlike
     * `time-pos`, which reports the seek target of a resume start once the file opens.
     */
    fun hasStartedPlayback(): Boolean = playbackRestarted.get()

    /**
     * Spec 85 G9/E7: ends the file (mpv `stop`, which also cancels a stalled open), so nothing it
     * loaded can play again and its demuxer cache stops filling. Its END_FILE is reason=stop, which
     * [mpvLoadFailureOf] ignores. The core then quits (idle=once); the next start releases it.
     */
    fun stopPlayback() {
        if (!initialized) return
        stopped = true
        loadFailure.set(null)
        runCatching { mpv.command("stop") }
            .onFailure { Log.w(TAG, "Failed to stop mpv playback: ${it.message}") }
    }

    /** [stopPlayback] ended this core's file: nothing here can play again until the next start. */
    fun isStopped(): Boolean = stopped

    /** mpv's `eof-reached`: with keep-open, a file that hit its end sits paused there (no END_FILE). */
    fun isEofReachedNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("eof-reached") == true
    }

    /** Spec 85 G10: the startup watchdog's track facts on mpv ([mpvWatchdogFireInput]). */
    fun startupWatchdogFireInput(): PlayerStartupWatchdogPolicy.FireInput {
        if (!initialized) return mpvWatchdogFireInput(emptyList(), videoSelected = false)
        val trackCount = runCatching { mpv.getPropertyInt("track-list/count") ?: 0 }.getOrDefault(0)
        val types = (0 until trackCount).mapNotNull { i -> mpv.getPropertyString("track-list/$i/type") }
        return mpvWatchdogFireInput(types, videoSelected = hasVideoTrackSelectedNow())
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun applyAudioAmplificationDb(db: Int) {
        if (!initialized) return
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        val linearScale = 10.0.pow(clampedDb / 20.0)
        val targetVolumePercent = (100.0 * linearScale).coerceIn(0.0, MPV_MAX_VOLUME_PERCENT)
        runCatching {
            mpv.setPropertyDouble("volume", targetVolumePercent)
        }.onFailure {
            Log.w(TAG, "Failed to apply audio amplification on mpv (db=$clampedDb): ${it.message}")
        }
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (!initialized) return
        val normalized = languages
            .mapNotNull { language ->
                language.trim().takeIf { it.isNotBlank() }
            }
            .distinct()
        runCatching {
            // Empty value resets language preference back to default behavior.
            mpv.setPropertyString("alang", normalized.joinToString(","))
            // Re-run automatic audio selection with the latest preferences.
            mpv.setPropertyString("aid", "auto")
        }.onFailure {
            Log.w(TAG, "Failed to set audio language preference: ${it.message}")
        }
    }

    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        hardwareDecodeMode = mode
        if (!initialized) return
        runCatching {
            mpv.setPropertyString("hwdec", mode.toMpvHwdecValue())
        }.onFailure {
            Log.w(TAG, "Failed to apply mpv hardware decode mode ($mode): ${it.message}")
        }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle delay on mpv: ${it.message}")
        }
    }

    fun setAudioDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyDouble("audio-delay", audioDelayMsToSeconds(delayMs))
        }.onFailure {
            Log.w(TAG, "Failed to set audio delay on mpv (delayMs=$delayMs): ${it.message}")
        }
    }

    /**
     * Bluetooth A2DP/LE cannot carry encoded passthrough. Force a stereo PCM mix.
     * Mid-session route changes pass [reloadOutput] so AudioTrack follows the new device
     * without restarting video.
     */
    fun applyBluetoothAudioRoute(isBluetooth: Boolean, reloadOutput: Boolean = false) {
        if (!initialized) return
        val wasPaused = !isPlayingNow()
        runCatching {
            mpv.setPropertyString("audio-channels", MpvBluetoothAudioPolicy.audioChannels(isBluetooth))
            if (MpvBluetoothAudioPolicy.shouldClearAudioSpdif(isBluetooth)) {
                mpv.setPropertyString("audio-spdif", "")
            }
            if (reloadOutput) {
                reloadAudioOutput()
                if (wasPaused) {
                    mpv.setPropertyBoolean("pause", true)
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to apply bluetooth audio route on mpv (bt=$isBluetooth): ${it.message}")
        }
    }

    private fun reloadAudioOutput() {
        val reloaded = runCatching {
            mpv.command("ao-reload")
            true
        }.getOrDefault(false)
        if (reloaded) return
        val aid = mpv.getPropertyString("aid")
        if (!aid.isNullOrBlank() && !aid.equals("no", ignoreCase = true)) {
            runCatching { mpv.setPropertyString("aid", aid) }
        }
    }

    fun applyAspectMode(mode: AspectMode) {
        currentAspectMode = mode
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        applyAspectModeInternal(mode, allowRetry = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        post {
            applyAspectModeInternal(currentAspectMode, allowRetry = true)
        }
    }

    private fun applyAspectModeInternal(mode: AspectMode, allowRetry: Boolean) {
        val viewAspect = readViewAspectRatio(width, height)
        val videoAspect = readVideoAspectRatio()
        val scale = resolveAspectScale(
            mode = mode,
            viewAspect = viewAspect,
            videoAspect = videoAspect
        )
        scaleX = scale.scaleX
        scaleY = scale.scaleY
        if (
            allowRetry &&
            aspectModeNeedsVideoAspect(mode) &&
            (viewAspect <= 0f || videoAspect == null || videoAspect <= 0f)
        ) {
            scheduleAspectModeRefresh(resetRetryCount = false)
        }
    }

    private fun scheduleAspectModeRefresh(resetRetryCount: Boolean) {
        if (resetRetryCount) {
            pendingAspectRetryCount = 0
        }
        removeCallbacks(aspectReapplyRunnable)
        if (pendingAspectRetryCount >= MAX_ASPECT_RETRY_COUNT) {
            return
        }
        val delayMs = if (pendingAspectRetryCount == 0) 0L else ASPECT_RETRY_DELAY_MS
        pendingAspectRetryCount += 1
        postDelayed(aspectReapplyRunnable, delayMs)
    }

    fun applySubtitleStyle(style: SubtitleStyleSettings) {
        if (!initialized) return
        runCatching {
            val scale = (style.size / 100.0).coerceIn(0.5, 3.0)
            val clampedOffset = style.verticalOffset.coerceIn(
                SUBTITLE_VERTICAL_OFFSET_MIN,
                SUBTITLE_VERTICAL_OFFSET_MAX
            )
            val normalizedOffset = (clampedOffset - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble() /
                (SUBTITLE_VERTICAL_OFFSET_MAX - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble()
            val subPos = MPV_SUB_POS_AT_BOTTOM -
                (normalizedOffset * (MPV_SUB_POS_AT_BOTTOM - MPV_SUB_POS_AT_TOP))
            val subMarginY = (MPV_SUB_MARGIN_Y_MIN +
                (normalizedOffset * (MPV_SUB_MARGIN_Y_MAX - MPV_SUB_MARGIN_Y_MIN))).toInt()
            val edges = mpvSubtitleEdges(style, isAssOrSsaSubtitleSelectedNow())

            mpv.setPropertyDouble("sub-scale", scale)
            mpv.setPropertyBoolean("sub-bold", style.bold)
            mpv.setPropertyDouble("sub-outline-size", edges.outlineSize)
            mpv.setPropertyDouble("sub-pos", subPos)
            mpv.setPropertyInt("sub-margin-y", subMarginY)
            mpv.setPropertyDouble("sub-shadow-offset", edges.shadowOffset)
            mpv.setPropertyString("sub-border-style", edges.borderStyle)
            mpv.setPropertyString("sub-color", toMpvColor(style.textColor))
            mpv.setPropertyString("sub-back-color", toMpvColor(edges.backColor))
            mpv.setPropertyString("sub-outline-color", toMpvColor(style.outlineColor))
            mpv.setPropertyBoolean("sub-filter-sdh", style.stripSdh)
            mpv.setPropertyBoolean("sub-filter-sdh-harder", style.stripSdh)
        }.onFailure {
            Log.w(TAG, "Failed to apply subtitle style on mpv: ${it.message}")
        }
    }

    /**
     * True when mpv renders to a PQ or HLG target (the TV receives HDR), false for any other
     * target transfer, null until mpv has chosen one (Spec 62 D7: the output, not the file).
     */
    fun readOutputIsHdr(): Boolean? {
        if (!initialized) return null
        val gamma = runCatching { mpv.getPropertyString("video-target-params/gamma") }.getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return gamma == "pq" || gamma == "hlg"
    }

    private fun isAssOrSsaSubtitleSelectedNow(): Boolean {
        if (!initialized) return false
        val trackCount = mpv.getPropertyInt("track-list/count") ?: return false
        if (trackCount <= 0) return false

        val selectedSubtitleId = mpv.getPropertyString("sid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/sub/id")

        for (i in 0 until trackCount) {
            val type = mpv.getPropertyString("track-list/$i/type")?.lowercase(Locale.US) ?: continue
            if (type != "sub") continue

            val isSelectedByFlag = mpv.getPropertyBoolean("track-list/$i/selected") == true
            val trackId = mpv.getPropertyInt("track-list/$i/id")
            val isSelected = isSelectedByFlag || (selectedSubtitleId != null && trackId == selectedSubtitleId)
            if (!isSelected) continue

            val codec = mpv.getPropertyString("track-list/$i/codec")
                ?.trim()
                ?.lowercase(Locale.US)
                ?: return false
            return codec.contains("ass") || codec.contains("ssa")
        }
        return false
    }

    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyInt("aid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select audio track id=$trackId: ${it.message}")
            false
        }
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyBoolean("sub-visibility", true)
            mpv.setPropertyInt("sid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select subtitle track id=$trackId: ${it.message}")
            false
        }
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyString("sid", "no")
            mpv.setPropertyBoolean("sub-visibility", false)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to disable subtitles: ${it.message}")
            false
        }
    }

    fun addAndSelectExternalSubtitle(
        url: String,
        title: String? = null,
        language: String? = null
    ): Boolean {
        if (!initialized) return false
        if (url.isBlank()) return false
        return runCatching {
            // "cached" avoids duplicate re-loads for the same external subtitle.
            val safeTitle = title?.takeIf { it.isNotBlank() }
            val safeLanguage = language?.takeIf { it.isNotBlank() }
            when {
                safeTitle != null && safeLanguage != null ->
                    mpv.command("sub-add", url, "cached", safeTitle, safeLanguage)
                safeTitle != null ->
                    mpv.command("sub-add", url, "cached", safeTitle)
                else ->
                    mpv.command("sub-add", url, "cached")
            }
            mpv.setPropertyBoolean("sub-visibility", true)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to add external subtitle: ${it.message}")
            false
        }
    }

    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) {
        if (!initialized) return
        val languages = listOfNotNull(
            preferred.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            secondary?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        )
        if (languages.isEmpty()) {
            disableSubtitles()
            return
        }
        runCatching {
            mpv.setPropertyString("slang", languages.joinToString(","))
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle language preference: ${it.message}")
        }
    }

    fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        val trackCount = runCatching { mpv.getPropertyInt("track-list/count") ?: 0 }
            .getOrDefault(0)
        if (trackCount <= 0) {
            return MpvTrackSnapshot(emptyList(), emptyList())
        }

        val selectedAudioTrackId = mpv.getPropertyString("aid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/audio/id")
        val selectedSubtitleTrackId = mpv.getPropertyString("sid")?.toIntOrNull()
            ?: mpv.getPropertyInt("current-tracks/sub/id")

        val audioTracks = mutableListOf<MpvTrack>()
        val subtitleTracks = mutableListOf<MpvTrack>()

        for (i in 0 until trackCount) {
            val type = mpv.getPropertyString("track-list/$i/type")?.lowercase() ?: continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val language = mpv.getPropertyString("track-list/$i/lang")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val title = mpv.getPropertyString("track-list/$i/title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val codec = mpv.getPropertyString("track-list/$i/codec")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val selectedByFlag = mpv.getPropertyBoolean("track-list/$i/selected") == true
            val external = mpv.getPropertyBoolean("track-list/$i/external") == true
            val channelCount = mpv.getPropertyInt("track-list/$i/demux-channel-count")
                ?: mpv.getPropertyInt("track-list/$i/audio-channels")
                ?: mpv.getPropertyInt("track-list/$i/channels")
            val forced = (mpv.getPropertyBoolean("track-list/$i/forced") == true) || listOfNotNull(title, language).any {
                it.contains("forced", ignoreCase = true)
            }
            val selected = when (type) {
                "audio" -> (selectedAudioTrackId != null && selectedAudioTrackId == id) || selectedByFlag
                "sub" -> (selectedSubtitleTrackId != null && selectedSubtitleTrackId == id) || selectedByFlag
                else -> selectedByFlag
            }

            when (type) {
                "audio" -> {
                    audioTracks += MpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: context.getString(com.nuvio.tv.R.string.player_track_audio_fallback, id),
                        language = language,
                        codec = codec,
                        channelCount = channelCount,
                        isSelected = selected,
                        isForced = false,
                        isExternal = external
                    )
                }

                "sub" -> {
                    subtitleTracks += MpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: context.getString(com.nuvio.tv.R.string.player_track_subtitle_fallback, id),
                        language = language,
                        codec = codec,
                        channelCount = null,
                        isSelected = selected,
                        isForced = forced,
                        isExternal = external
                    )
                }
            }
        }

        return MpvTrackSnapshot(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            chapters = readChapters()
        )
    }

    /**
     * The loaded file's `chapter-list` (format-capability F2/G1), re-read when mpv's loaded `path`
     * or the count changes. Keyed on mpv's own path, not [lastMediaRequestKey]: that is set before
     * mpv drops the previous file, so the old list could be cached under the new key.
     */
    private fun readChapters(): List<PlaybackChapter> {
        val count = runCatching { mpv.getPropertyInt("chapter-list/count") ?: 0 }.getOrDefault(0)
        val key = "${mpv.getPropertyString("path")}#$count"
        if (key == chapterCacheKey) return chapterCache
        val chapters = (0 until count.coerceAtMost(PlaybackChapter.MAX_CHAPTERS)).mapNotNull { i ->
            val seconds = mpv.getPropertyDouble("chapter-list/$i/time")
                ?.takeIf { it.isFinite() } ?: return@mapNotNull null
            PlaybackChapter(
                startMs = (seconds * 1000.0).roundToLong(),
                title = mpv.getPropertyString("chapter-list/$i/title")
            )
        }
        chapterCache = PlaybackChapter.normalized(chapters)
        chapterCacheKey = key
        return chapterCache
    }

    fun releasePlayer() {
        if (!initialized) return
        removeCallbacks(aspectReapplyRunnable)
        // Before destroy: the MPV instance (and its observer list) outlives the core, and the
        // teardown's own END_FILE belongs to no load.
        runCatching { mpv.removeObserver(endFileObserver) }
        runCatching { destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
        loadFailure.set(null)
        playbackRestarted.set(false)
        stopped = false
        initialized = false
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
    }

    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("user-agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        // Preserve native ASS/SSA styling behavior on MPV.
        mpv.setOptionString("sub-ass-override", "no")
        mpv.setOptionString("sub-codepage", "auto:utf-8")
        mpv.setOptionString("sub-font", "Roboto")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", "yes")
        mpv.setOptionString("hwdec", hardwareDecodeMode.toMpvHwdecValue())
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("input-default-bindings", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("softvol", "yes")
        mpv.setOptionString("volume-max", MPV_MAX_VOLUME_PERCENT.toInt().toString())
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        // Progress is polled by PlayerRuntimeController; only END_FILE is observed (Spec 85 D3).
        // Runs once per core, inside initialize(); releasePlayer removes the observer.
        loadFailure.set(null)
        playbackRestarted.set(false)
        stopped = false
        mpv.addObserver(endFileObserver)
    }

    private fun applyHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            mpv.setPropertyString("http-header-fields", "")
            return
        }
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = ",") { (key, value) ->
                val escapedHeader = "$key: $value"
                    .replace("\\", "\\\\")
                    .replace(",", "\\,")
                escapedHeader
            }
        mpv.setPropertyString("http-header-fields", raw)
    }

    private fun buildMediaRequestKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = "|") { "${it.key.trim()}:${it.value.trim()}" }
        return "$url#$normalizedHeaders"
    }

    private fun MpvHardwareDecodeMode.toMpvHwdecValue(): String {
        return when (this) {
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> "mediacodec,mediacodec-copy"
            MpvHardwareDecodeMode.AUTO_SAFE -> "auto-safe"
            MpvHardwareDecodeMode.HARDWARE_COPY -> "mediacodec-copy"
            MpvHardwareDecodeMode.HARDWARE_DIRECT -> "mediacodec"
            MpvHardwareDecodeMode.DISABLED -> "no"
        }
    }

    private fun toMpvColor(color: Int): String {
        return String.format(Locale.US, "#%08X", color)
    }

    private fun readVideoAspectRatio(): Float? {
        if (!initialized) return null

        val directAspect = runCatching {
            mpv.getPropertyDouble("video-out-params/aspect")
                ?: mpv.getPropertyDouble("video-params/aspect")
        }.getOrNull()
        if (directAspect != null && directAspect > 0.0) {
            return directAspect.toFloat()
        }

        val width = runCatching {
            mpv.getPropertyInt("video-out-params/dw")
                ?: mpv.getPropertyInt("video-params/w")
        }.getOrNull() ?: return null
        val height = runCatching {
            mpv.getPropertyInt("video-out-params/dh")
                ?: mpv.getPropertyInt("video-params/h")
        }.getOrNull() ?: return null
        if (width <= 0 || height <= 0) return null

        return width.toFloat() / height.toFloat()
    }

    companion object {
        private const val TAG = "NuvioMpvSurfaceView"
        /** `loadfile` insertion index; only meaningful for insert-at flags, -1 is mpv's default. */
        private const val LOADFILE_DEFAULT_INDEX = "-1"
        private const val MPV_MAX_VOLUME_PERCENT = 400.0
        private const val ASPECT_RETRY_DELAY_MS = 120L
        private const val MAX_ASPECT_RETRY_COUNT = 10
        private const val SUBTITLE_VERTICAL_OFFSET_MIN = -20
        private const val SUBTITLE_VERTICAL_OFFSET_MAX = 50
        private const val MPV_SUB_POS_AT_BOTTOM = 103.4
        private const val MPV_SUB_POS_AT_TOP = 72.4
        private const val MPV_SUB_MARGIN_Y_MIN = 0
        private const val MPV_SUB_MARGIN_Y_MAX = 60
    }
}

/**
 * Spec 85 D3: what an mpv END_FILE event says about the load: mpv's error text (`file_error`, an
 * mpv_error_string such as "loading failed") when [reason] is `error`, null for eof, stop, quit
 * and redirect.
 */
internal fun mpvLoadFailureOf(reason: String?, fileError: String?): String? {
    if (reason != "error") return null
    return fileError?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown error"
}

/**
 * Spec 85 G10: the startup watchdog's track facts from mpv's `track-list` types. Tracks count as
 * read once the file has an audio or video track (an external subtitle added before the load does
 * not count); [videoSelected] is mpv's `vid`.
 */
internal fun mpvWatchdogFireInput(
    trackTypes: List<String>,
    videoSelected: Boolean
): PlayerStartupWatchdogPolicy.FireInput {
    val types = trackTypes.map { it.lowercase(Locale.US) }
    return PlayerStartupWatchdogPolicy.FireInput(
        hasScannedTracksOnce = types.any { it == "video" || it == "audio" },
        streamHasVideoTrack = "video" in types,
        videoTrackSelected = videoSelected
    )
}

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>,
    val chapters: List<PlaybackChapter> = emptyList()
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)
