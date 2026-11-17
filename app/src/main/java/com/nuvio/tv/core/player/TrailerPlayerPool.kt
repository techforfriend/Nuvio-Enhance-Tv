package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Application-scoped singleton that holds a single ExoPlayer instance dedicated to
 * trailer/preview playback on the home screen.
 *
 * Creating and tearing down ExoPlayer for every poster focus is extremely expensive
 * (codec init, hardware decoder allocation). This pool keeps one instance alive and
 * reuses it across focus changes. The player is stopped and cleared between uses and
 * lives for the process, except that [yield] releases it.
 *
 * When the full-screen player needs hardware decoders, call [yield] to release the
 * instance and its codecs. Call [reclaim] when returning to the home screen; the next
 * [acquire] lazily builds a fresh instance.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Singleton
class TrailerPlayerPool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) {
    companion object {
        private const val TAG = "TrailerPlayerPool"
    }

    private var _player: ExoPlayer? = null
    private val yielded = AtomicBoolean(false)
    private val screensaverSuppressed = AtomicBoolean(false)

    @Volatile
    private var cachedForceNative: Boolean = false

    /**
     * Spec 123 C7: the next start may go at 2 s buffered (card previews). Written by each loader immediately before it
     * loads ([com.nuvio.tv.ui.components.TrailerPlayer]), so the last loader owns it; read on the playback thread.
     */
    @Volatile
    var quickStart: Boolean = false

    init {
        Thread {
            try {
                cachedForceNative = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    playerSettingsDataStore.nuvioPerformanceModeEnabled.first()
                }
            } catch (_: Exception) {
                cachedForceNative = false
            }
        }.start()
    }

    /**
     * Returns the shared trailer ExoPlayer, creating it lazily if needed.
     * Returns null while the OLED screensaver has suppressed trailer playback via
     * [setScreensaverSuppressed].
     */
    fun acquire(): ExoPlayer? {
        if (screensaverSuppressed.get()) return null
        if (yielded.get()) {
            // Reclaim was not called yet but someone wants the player — rebuild.
            reclaim()
        }
        return _player ?: createPlayer().also { _player = it }
    }

    /**
     * Stops playback and clears media but keeps the instance alive for reuse.
     * Call this when the trailer is no longer visible (poster lost focus, screen change).
     */
    fun stop() {
        _player?.let { player ->
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    /**
     * While suppressed, [acquire] returns null so every trailer start silently no-ops.
     * Set by the OLED screensaver on engage (together with [stop]) and cleared on wake.
     * Trailers resume on their next natural trigger (focus change or hero rotation).
     */
    fun setScreensaverSuppressed(suppressed: Boolean) {
        screensaverSuppressed.set(suppressed)
    }

    /**
     * Releases codec resources so the detail-screen player can claim hardware decoders.
     * The ExoPlayer instance is released here; the next [acquire] creates a fresh one.
     */
    fun yield() {
        if (yielded.compareAndSet(false, true)) {
            Log.d(TAG, "Yielding trailer player for detail playback")
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
        }
    }

    /**
     * Re-creates the player after a [yield]. Safe to call multiple times.
     */
    fun reclaim() {
        if (yielded.compareAndSet(true, false)) {
            Log.d(TAG, "Reclaiming trailer player")
            // Player will be lazily created on next acquire()
        }
    }

    private fun createPlayer(): ExoPlayer {
        val forceNative = cachedForceNative
        Log.d(TAG, "Creating shared trailer ExoPlayer instance with forceNativeAllocation = $forceNative")
        val loadControl = if (forceNative) {
            val allocator = DefaultAllocator(
                /* trimOnReset = */ true,
                /* individualAllocationSize = */ 65536,
                /* initialAllocationCount = */ 0,
                /* forceNativeAllocation = */ true
            )
            TrailerLoadControl({ quickStart }, allocator)
        } else {
            TrailerLoadControl({ quickStart })
        }
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .clearVideoSizeConstraints()
                    .setForceHighestSupportedBitrate(true)
            )
        }
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(50_000_000L) // 50 Mbps – force highest HLS variant from start
                    .build()
            )
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }
}
