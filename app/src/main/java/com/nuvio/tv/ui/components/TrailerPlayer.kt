package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nuvio.tv.core.player.LocalTrailerPlayerPool
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.common.VideoSize
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500)),
    trailerPlayerPool: TrailerPlayerPool? = null,
    // Where a (re)start begins; read when playback starts, so changing it alone does not restart.
    startPositionMs: Long = 0L,
    onError: () -> Unit = {},
    // False for an overlay that must never take D-pad focus (Spec 96's card preview).
    focusable: Boolean = true,
    // Player.Listener.onIsPlayingChanged (Spec 123's timing trace).
    onPlayingChanged: (Boolean) -> Unit = {},
    // The frame's display aspect (width x pixel ratio / height), on each change; zero sizes are not reported
    // (Spec 123 C4).
    onVideoSizeChanged: (frameAspect: Float) -> Unit = {},
    // The player view's TextureView while it exists, null once released, so no reference outlives the view
    // (Spec 123 C4 reads frames from it).
    onVideoSurface: (TextureView?) -> Unit = {},
    // Spec 123 C2: a start (and an app resume) honours isPaused, so a preview prepares paused and holds its first
    // frame. Off for the other callers: they start playing and let the isPaused effect pause them, as before.
    startPaused: Boolean = false,
    // The 300 ms fade-in at the first frame; off where the caller fades the player itself (Spec 123 C2's preview).
    fadeInFirstFrame: Boolean = true,
    // Spec 123 C7: start at 2 s buffered instead of 5 s (card previews); written to the pool before every load.
    quickStart: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityLifecycleOwner = remember(context) { context as? androidx.lifecycle.LifecycleOwner ?: lifecycleOwner }
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentTrailerUrl by rememberUpdatedState(trailerUrl)
    val currentTrailerAudioUrl by rememberUpdatedState(trailerAudioUrl)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnPlayingChanged by rememberUpdatedState(onPlayingChanged)
    val currentOnVideoSizeChanged by rememberUpdatedState(onVideoSizeChanged)
    val currentOnVideoSurface by rememberUpdatedState(onVideoSurface)
    val currentStartPositionMs by rememberUpdatedState(startPositionMs)
    val currentMuted by rememberUpdatedState(muted)
    val currentPlayWhenReady by rememberUpdatedState(!(startPaused && isPaused))
    val currentQuickStart by rememberUpdatedState(quickStart)
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trailerFirstFrameAlpha"
    )

    // Resolve pool: explicit parameter > CompositionLocal
    val resolvedPool = trailerPlayerPool ?: LocalTrailerPlayerPool.current

    // Use the shared pool instance instead of creating a new ExoPlayer per focus.
    // The pool keeps one ExoPlayer alive across poster focus changes, eliminating
    // the expensive create/teardown cycle that was the app-launch bottleneck.
    val trailerPlayer = remember(trailerUrl, resolvedPool) {
        if (trailerUrl != null) {
            resolvedPool?.acquire()
        } else {
            null
        }
    }

    // Configure player settings when acquired
    LaunchedEffect(trailerPlayer, muted, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    // Not keyed on muted: a mute change only sets the volume (effect above) and never restarts the trailer.
    LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (isPlaying && trailerUrl != null) {
            hasRenderedFirstFrame = false
            // Each start sets the volume too: the pooled player may come from another caller.
            player.volume = if (currentMuted) 0f else 1f
            resolvedPool?.quickStart = currentQuickStart
            player.loadTrailer(trailerUrl, trailerAudioUrl, currentStartPositionMs)
            player.playWhenReady = currentPlayWhenReady
        } else {
            hasRenderedFirstFrame = false
            player.playWhenReady = false
            // Defer heavy stop and clear until focus settling/collapse has finished
            delay(150)
            if (!isPlaying) {
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    LaunchedEffect(trailerPlayer, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(isPaused, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        player.playWhenReady = !isPaused
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0) return@LaunchedEffect
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val current = player.currentPosition
        val target = (current + seekDeltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    LaunchedEffect(trailerPlayer, isPlaying) {
        val player = trailerPlayer ?: return@LaunchedEffect
        while (isPlaying) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            currentOnProgressChanged(position, duration)
            delay(250)
        }
        currentOnProgressChanged(0L, 0L)
    }

    DisposableEffect(activityLifecycleOwner, trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnEnded()
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                currentOnFirstFrameRendered()
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnError()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                currentOnPlayingChanged(isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSize.frameAspect()?.let { currentOnVideoSizeChanged(it) }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (currentIsPlaying && !currentTrailerUrl.isNullOrBlank()) {
                        if (player.currentMediaItem == null) {
                            resolvedPool?.quickStart = currentQuickStart
                            player.loadTrailer(currentTrailerUrl!!, currentTrailerAudioUrl, currentStartPositionMs)
                        }
                        player.playWhenReady = currentPlayWhenReady
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    player.playWhenReady = false
                    player.pause()
                    player.stop()
                    player.clearMediaItems()
                }
                // Do NOT release on destroy — the pool owns the lifecycle.
                else -> Unit
            }
        }
        player.addListener(listener)
        // A size reported before this listener attached (the size callback fires only on a change).
        player.videoSize.frameAspect()?.let { currentOnVideoSizeChanged(it) }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { activityLifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.removeListener(listener) }
            // Only stop — never release. The pool manages the ExoPlayer lifecycle.
            resolvedPool?.stop()
        }
    }

    if (trailerPlayer != null) {
        AnimatedVisibility(
            visible = isPlaying,
            enter = enter,
            exit = exit
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).apply {
                        player = trailerPlayer
                        isFocusable = focusable
                        isFocusableInTouchMode = focusable
                        setOnKeyListener { _, keyCode, event ->
                            currentOnRemoteKey(keyCode, event.action, event.repeatCount)
                        }
                        keepScreenOn = true
                        resizeMode = if (cropToFill) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        currentOnVideoSurface(videoSurfaceView as? TextureView)
                    }
                },
                update = { view ->
                    // Re-attach player in case it was reclaimed after yield
                    if (view.player !== trailerPlayer) {
                        view.player = trailerPlayer
                    }
                    view.resizeMode = if (cropToFill) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { view ->
                    currentOnVideoSurface(null)
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = if (fadeInFirstFrame) playerAlphaState.value else 1f
                    }
            )
        }
    }
}

/** Display aspect of the frame, pixel aspect included; null while the size is unknown. */
private fun VideoSize.frameAspect(): Float? =
    if (width > 0 && height > 0) width * (pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f) / height else null

/**
 * Loads [videoUrl] (with a separate [audioUrl] track when given) and prepares. [startPositionMs] > 0 starts
 * there (Spec 96 resume); otherwise the item's default start, exactly as before the parameter existed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun ExoPlayer.loadTrailer(
    videoUrl: String,
    audioUrl: String?,
    startPositionMs: Long,
    mediaSourceFactory: () -> MediaSource.Factory = { DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory()) }
) {
    val start = startPositionMs.takeIf { it > 0L }
    if (!audioUrl.isNullOrBlank()) {
        val factory = mediaSourceFactory()
        val source = MergingMediaSource(
            factory.createMediaSource(MediaItem.fromUri(videoUrl)),
            factory.createMediaSource(MediaItem.fromUri(audioUrl))
        )
        if (start != null) setMediaSource(source, start) else setMediaSource(source)
    } else {
        val item = MediaItem.fromUri(videoUrl)
        if (start != null) setMediaItem(item, start) else setMediaItem(item)
    }
    prepare()
}
