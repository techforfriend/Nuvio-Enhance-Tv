package com.nuvio.tv.ui.components.trailerpreview

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import com.nuvio.tv.domain.model.TrailerPreviewDelay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * Spec 96's app-level preview (PD2): owns the [TrailerPreviewMachine] for the activity and resolves a card's
 * trailer through [TrailerService] in Home's order (the item lookup, then the item's first YouTube id). The
 * screensaver engaging cancels the preview and its end resumes it (PD6; it stops the pool without a lifecycle
 * event); stage 2 holds the screensaver's playbackActive, cleared only if it set it (PD4). Each preview's timing
 * goes to logcat tag [TIMING_TAG] (Spec 123 C1).
 */
@HiltViewModel
class TrailerPreviewViewModel @Inject constructor(
    private val trailerService: TrailerService,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val screensaverController: ScreensaverController,
    trailerSettingsDataStore: TrailerSettingsDataStore
) : ViewModel() {

    /** The Layout › Home choice is In the card (D7). */
    val cardModeEnabled: StateFlow<Boolean> = layoutPreferenceDataStore.trailerAutoplayMode
        .map { it == TrailerAutoplayMode.CARD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Layout › Home "Preview delay" (D12), read by the machine each time a wait starts.
    private val delaySeconds: StateFlow<Int> = layoutPreferenceDataStore.trailerCardPreviewDelaySeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrailerPreviewDelay.DEFAULT_S)

    // The trailer source setting, only for the timing trace (the lookup reads it itself).
    private val trailerSource: StateFlow<TrailerSource?> = trailerSettingsDataStore.settings
        .map { it.source }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The card is always muted, the panel always has sound (D11): no mute setting is read here.
    val machine = TrailerPreviewMachine(
        scope = viewModelScope,
        now = SystemClock::elapsedRealtime, // monotonic: the resume window survives clock changes
        delayMs = { delaySeconds.value * 1_000L },
        trace = ::logTiming,
        resolve = ::resolve
    )

    private fun logTiming(event: TrailerPreviewEvent) {
        Log.d(TIMING_TAG, "+${event.sinceArmMs} ms ${trailerPreviewTimingLine(event, trailerSource.value)}")
    }

    private var ownsPlaybackActive = false

    // Tests swap in their dispatcher; the lookup itself is blocking network work.
    internal var resolveDispatcher: CoroutineDispatcher = Dispatchers.IO

    init {
        viewModelScope.launch {
            screensaverController.overlayVisible.collect { visible ->
                if (visible) machine.cancel(TrailerPreviewCancelCause.SCREENSAVER)
                else machine.resume(TrailerPreviewCancelCause.SCREENSAVER)
            }
        }
        viewModelScope.launch {
            machine.state.map { it?.stage == TrailerPreviewStage.PANEL }.distinctUntilChanged().collect(::holdPlaybackActive)
        }
    }

    private fun holdPlaybackActive(panel: Boolean) {
        if (panel) {
            if (!screensaverController.playbackActive.value) {
                screensaverController.setPlaybackActive(true)
                ownsPlaybackActive = true
            }
        } else if (ownsPlaybackActive) {
            ownsPlaybackActive = false
            screensaverController.setPlaybackActive(false)
        }
    }

    override fun onCleared() {
        holdPlaybackActive(panel = false)
    }

    private suspend fun resolve(candidate: TrailerPreviewCandidate): TrailerPreviewSource? =
        withContext(resolveDispatcher) { resolveTrailerPreviewSource(trailerService, candidate) }
}

private const val TAG = "TrailerPreview"
private const val TIMING_TAG = "TrailerPreviewTiming"

/** One timing log line after its "+N ms" (Spec 123 C1); [source] is the trailer source setting, null until read. */
internal fun trailerPreviewTimingLine(event: TrailerPreviewEvent, source: TrailerSource?): String = when (event) {
    is TrailerPreviewEvent.Armed -> "${if (event.rearm) "re-arm (resume)" else "arm (focus)"} ${event.itemId} source=${source ?: "unknown"}"
    is TrailerPreviewEvent.ResolveStarted -> "resolve start"
    is TrailerPreviewEvent.Resolved -> "resolve end ${if (event.found) "found" else "none"} in ${event.durationMs} ms"
    is TrailerPreviewEvent.Prepared -> "prepared (loading)"
    is TrailerPreviewEvent.DwellEnded -> "dwell end"
    is TrailerPreviewEvent.FirstFrame -> if (event.hidden) "first frame (hidden)" else "first frame (card)"
    is TrailerPreviewEvent.Playing -> "playing"
    is TrailerPreviewEvent.Shown -> "shown"
    is TrailerPreviewEvent.Panel -> "panel"
    is TrailerPreviewEvent.Aborted ->
        "aborted while ${event.phase.name.lowercase()}${if (event.hidden) " (hidden, before dwell end)" else ""}"
    is TrailerPreviewEvent.ShapeSample -> String.format(
        Locale.US, "shape sample copy %.1f ms total %.1f ms %s",
        event.copyMs, event.totalMs, event.aspect?.let { String.format(Locale.US, "%.3f", it) } ?: "invalid"
    )
    is TrailerPreviewEvent.ShapeDecided ->
        String.format(Locale.US, "shape %.3f (%s)", event.shape.aspect, event.shape.reason.name.lowercase())
}

/**
 * Home's order (HomeViewModelPresentationPipeline): the item lookup, then the item's first YouTube id when that
 * finds nothing. A failed lookup is no trailer (Home's job fails the same way); a blank audio URL is none.
 */
internal suspend fun resolveTrailerPreviewSource(
    trailerService: TrailerService,
    candidate: TrailerPreviewCandidate
): TrailerPreviewSource? = try {
    val source = trailerService.getTrailerPlaybackSourceForItem(
        itemId = candidate.itemId,
        title = candidate.title,
        releaseInfo = candidate.releaseInfo,
        apiType = candidate.apiType
    )?.takeUnless { it.videoUrl.isBlank() }
        ?: candidate.fallbackYtId?.let { ytId ->
            trailerService.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=$ytId")
        }?.takeUnless { it.videoUrl.isBlank() }
    source?.let { TrailerPreviewSource(it.videoUrl, it.audioUrl?.takeUnless(String::isBlank)) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Trailer lookup failed: ${e.javaClass.simpleName}")
    null
}
