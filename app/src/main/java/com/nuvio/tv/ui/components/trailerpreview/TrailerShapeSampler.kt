package com.nuvio.tv.ui.components.trailerpreview

import android.graphics.Bitmap
import android.graphics.Color
import android.view.TextureView

/**
 * One preview's bar sampling (Spec 123 C4), on the main thread from the overlay's progress tick: copies the
 * player's current frame small ([TextureView.getBitmap] into one reused bitmap), detects its bars and feeds the
 * [TrailerShapeDecider]; once decided it reports the shape to the machine (trace, memory) and to [onShape].
 */
internal class TrailerShapeSampler(
    private val machine: TrailerPreviewMachine,
    private val videoUrl: String,
    startPositionMs: Long,
    // The frame's own display aspect; 0 until reported (no sample before it).
    private val frameAspect: () -> Float,
    private val onShape: (Float) -> Unit
) {
    /** The player's TextureView while it exists (TrailerPlayer's onVideoSurface). */
    var surface: TextureView? = null

    private val decider = TrailerShapeDecider(startPositionMs)
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(TRAILER_SAMPLE_WIDTH * TRAILER_SAMPLE_HEIGHT)
    // The previous sample's pixels: a pixel-identical repeat (a frozen texture) is invalid.
    private var previous: IntArray? = null

    /** The progress tick at playback [positionMs]; [deferred] while the panel grows (a sample would cost a frame). */
    fun onPosition(positionMs: Long, deferred: Boolean) {
        val frame = frameAspect()
        if (deferred || frame <= 0f || !decider.isDue(positionMs)) return
        val copy = bitmap ?: Bitmap.createBitmap(TRAILER_SAMPLE_WIDTH, TRAILER_SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
            .also { bitmap = it }
        // Erased first: a missed copy (no surface yet) reads as a dark, invalid sample.
        copy.eraseColor(Color.BLACK)
        // Timed from the copy (the Shield acceptance's getBitmap figure) through getPixels and the scan (the budget).
        val start = System.nanoTime()
        surface?.takeIf { it.isAvailable }?.getBitmap(copy)
        val copied = System.nanoTime()
        copy.getPixels(pixels, 0, TRAILER_SAMPLE_WIDTH, 0, 0, TRAILER_SAMPLE_WIDTH, TRAILER_SAMPLE_HEIGHT)
        val aspect = trailerSampleAspect(pixels, previous, TRAILER_SAMPLE_WIDTH, TRAILER_SAMPLE_HEIGHT, frame)
        val last = previous ?: IntArray(pixels.size)
        previous = pixels
        pixels = last
        val end = System.nanoTime()
        val totalMs = (end - start) / NANOS_PER_MS
        machine.onShapeSample((copied - start) / NANOS_PER_MS, totalMs, aspect)
        decider.onSample(positionMs, aspect, totalMs, frame)?.let { shape ->
            machine.onShapeDecided(videoUrl, shape)
            onShape(shape.aspect)
            release()
        }
    }

    /** Frees the bitmap; called once decided and when the preview's frame leaves. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        previous = null
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000f
    }
}
