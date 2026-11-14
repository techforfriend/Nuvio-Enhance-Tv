package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.core.util.TtffTrace
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.flow.update

// Startup-stage tracing: every loading phase change is mirrored to TtffTrace as a
// STARTUP_STAGE line (the fork's time-to-first-frame instrument).

internal fun PlayerRuntimeController.resetLoadingDiagnostics(
    phase: String,
    message: String? = null,
    progress: Float? = null
) {
    val now = SystemClock.elapsedRealtime()
    loadingDiagnosticsStartedAtMs = now
    currentLoadingPhase = phase
    currentLoadingPhaseStartedAtMs = now
    lastLoadingDiagnosticSignature = ""
    startupPhaseSequence = 0
    recordLoadingDiagnosticEvent(phase = phase, message = message, progress = progress)
}

internal fun PlayerRuntimeController.setLoadingStatus(
    phase: String,
    message: String?,
    progress: Float? = null,
    showOverlay: Boolean? = null,
    detail: String? = null
) {
    recordLoadingDiagnosticEvent(phase = phase, message = message, progress = progress, detail = detail)
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = showOverlay ?: state.showLoadingOverlay,
            loadingMessage = message,
            loadingProgress = progress
        )
    }
}

internal fun PlayerRuntimeController.recordLoadingDiagnosticEvent(
    phase: String,
    message: String? = null,
    progress: Float? = null,
    detail: String? = null
) {
    val now = SystemClock.elapsedRealtime()
    if (loadingDiagnosticsStartedAtMs == 0L) {
        loadingDiagnosticsStartedAtMs = now
        currentLoadingPhaseStartedAtMs = now
    }
    if (phase != currentLoadingPhase) {
        currentLoadingPhase = phase
        currentLoadingPhaseStartedAtMs = now
    }
    val normalizedProgress = progress?.let { "%.3f".format(it.coerceIn(0f, 1f)) }.orEmpty()
    val signature = "$phase|${message.orEmpty()}|$normalizedProgress|${detail.orEmpty()}"
    if (signature == lastLoadingDiagnosticSignature) return
    lastLoadingDiagnosticSignature = signature
    startupPhaseSequence += 1
    val elapsedMs = (now - loadingDiagnosticsStartedAtMs).coerceAtLeast(0L)
    val phaseElapsedMs = (now - currentLoadingPhaseStartedAtMs).coerceAtLeast(0L)
    val clickElapsedMs = launchStartedAtElapsedMs?.let { (now - it).coerceAtLeast(0L) }
    TtffTrace.mirror(
        "STARTUP_STAGE: seq=$startupPhaseSequence phase=$phase elapsedMs=$elapsedMs " +
            "phaseElapsedMs=$phaseElapsedMs clickElapsedMs=${clickElapsedMs ?: -1L} " +
            "engine=$currentInternalPlayerEngine host=${currentStreamUrl.safeHost()} " +
            "message=${message?.compactTraceValue() ?: "n/a"} progress=${progress ?: -1f} " +
            "detail=${detail?.compactTraceValue() ?: "n/a"}"
    )
}

/** Called only at a first frame (Exo rendered/ready, MPV ready), so it also ends the Picture step (Spec 75). */
internal fun PlayerRuntimeController.finishLoadingDiagnostics(phase: String) {
    stepClock.pictureShown(SystemClock.elapsedRealtime())
    recordLoadingDiagnosticEvent(phase = phase, message = null, progress = 1f)
}

internal fun Int.playbackStateName(): String =
    when (this) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

/** One-line, length-capped form of a diagnostics value (STARTUP_STAGE and EXO_EVENT lines). */
internal fun String.compactTraceValue(): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(160)
