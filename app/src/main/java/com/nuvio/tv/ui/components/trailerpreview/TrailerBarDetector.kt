package com.nuvio.tv.ui.components.trailerpreview

import kotlin.math.abs
import kotlin.math.max

/**
 * Spec 123 C4 (D3, D10, D12, D13): the trailer's visible shape from bars baked into its frames. Pure, so it runs on
 * synthetic frames in unit tests; the overlay feeds it the player's current frame, copied small.
 */

// One sample: the current frame scaled into a 192x216 ARGB copy (tall enough that a 1.85 bar is ~4 rows).
const val TRAILER_SAMPLE_WIDTH = 192
const val TRAILER_SAMPLE_HEIGHT = 216

// A row is bar when no pixel in it is brighter than this (0-255 luma).
private const val BAR_LUMA = 24
// The picture between the bars must average at least this: a black or dark frame never counts.
private const val PICTURE_MIN_MEAN_LUMA = 40
// The picture must reach both side edges: these first and last columns may not be all dark.
private const val EDGE_COLUMNS = 4
// Bars thinner than this are none (encoder edge rows).
private const val MIN_BAR_ROWS = 2
// The two bars may differ by this many rows (subtitles in one bar make them differ more: invalid).
private const val BAR_MATCH_ROWS = 1

/**
 * The visible aspect of one sample ([pixels], ARGB, [width] x [height]) of a frame whose own aspect is
 * [frameAspect], or null when the sample says nothing (D10): bars are dark rows from the top and from the bottom
 * edge; they must match within a row, the picture between them must reach both side edges and not be dark. The
 * result is never below [frameAspect]: darkness can only thicken bars, so a sample errs wide, never narrow.
 */
fun detectTrailerVisibleAspect(pixels: IntArray, width: Int, height: Int, frameAspect: Float): Float? {
    require(pixels.size >= width * height)
    fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
    fun rowIsBar(y: Int): Boolean {
        val start = y * width
        for (x in 0 until width) if (luma(pixels[start + x]) > BAR_LUMA) return false
        return true
    }
    var top = 0
    while (top < height && rowIsBar(top)) top++
    if (top == height) return null // all dark
    var bottom = 0
    while (rowIsBar(height - 1 - bottom)) bottom++
    if (abs(top - bottom) > BAR_MATCH_ROWS) return null
    val firstRow = top
    val endRow = height - bottom
    var leftLit = false
    var rightLit = false
    var sum = 0L
    for (y in firstRow until endRow) {
        val start = y * width
        for (x in 0 until width) {
            val l = luma(pixels[start + x])
            sum += l
            if (l > BAR_LUMA) {
                if (x < EDGE_COLUMNS) leftLit = true
                if (x >= width - EDGE_COLUMNS) rightLit = true
            }
        }
    }
    if (!leftLit || !rightLit) return null // a logo or text card on black, or a dark-sided shot
    if (sum < PICTURE_MIN_MEAN_LUMA.toLong() * width * (endRow - firstRow)) return null
    val bar = minOf(top, bottom).takeIf { it >= MIN_BAR_ROWS } ?: 0
    return frameAspect * height / (height - 2 * bar)
}

/** One sample's visible aspect: a sample pixel-identical to the [previous] one (a frozen texture) says nothing. */
fun trailerSampleAspect(pixels: IntArray, previous: IntArray?, width: Int, height: Int, frameAspect: Float): Float? =
    if (previous?.contentEquals(pixels) == true) null else detectTrailerVisibleAspect(pixels, width, height, frameAspect)

/** Why the panel took its shape (trace). */
enum class TrailerShapeReason {
    /** 4 valid samples agreed (D12). */
    AGREED,
    /** Decided on an earlier preview of the same video (no sampling). */
    REMEMBERED,
    /** 10 samples without 4 agreeing: the video's own shape. */
    CAP,
    /** Sampling cost too much on this device: the video's own shape. */
    BUDGET
}

data class TrailerShape(val aspect: Float, val reason: TrailerShapeReason)

/**
 * Shape once (D12/D13): samples by playback position from the preview's start (+1, 3, 6, 9 s, then every 3 s);
 * when 4 valid samples lie within 3% of the newest valid one, the shape is the smallest aspect among them and
 * sampling stops. 10 samples (invalid included) without that, or a 3rd sample over the cost budget: the frame's
 * own aspect, sampling stops.
 */
class TrailerShapeDecider(private val startPositionMs: Long) {
    private val valid = ArrayList<Float>()
    private var samples = 0
    private var slowSamples = 0
    private var nextDueMs = startPositionMs + SAMPLE_OFFSETS_MS[0]

    var shape: TrailerShape? = null
        private set

    /** Whether a sample is due at playback [positionMs]. */
    fun isDue(positionMs: Long): Boolean = shape == null && positionMs >= nextDueMs

    /**
     * One sample taken at [positionMs]: its visible [aspect] (null = invalid), [costMs] its copy + scan, of a frame
     * whose own aspect is [frameAspect]. Returns the shape once decided.
     */
    fun onSample(positionMs: Long, aspect: Float?, costMs: Float, frameAspect: Float): TrailerShape? {
        if (shape != null) return shape
        samples++
        if (costMs > SAMPLE_BUDGET_MS) slowSamples++
        if (aspect != null) {
            valid += aspect
            val agreeing = valid.filter { abs(it - aspect) <= aspect * AGREE_TOLERANCE }
            if (agreeing.size >= AGREE_COUNT) shape = TrailerShape(agreeing.min(), TrailerShapeReason.AGREED)
        }
        if (shape == null && slowSamples >= SLOW_SAMPLE_LIMIT) shape = TrailerShape(frameAspect, TrailerShapeReason.BUDGET)
        if (shape == null && samples >= SAMPLE_CAP) shape = TrailerShape(frameAspect, TrailerShapeReason.CAP)
        val offset = SAMPLE_OFFSETS_MS.getOrNull(samples)
            ?: (SAMPLE_OFFSETS_MS.last() + (samples - SAMPLE_OFFSETS_MS.lastIndex) * SAMPLE_STEP_MS)
        // A late sample (deferred while the panel grew) keeps the next one a step away, not at once.
        nextDueMs = max(startPositionMs + offset, positionMs + MIN_GAP_MS)
        return shape
    }

    companion object {
        private val SAMPLE_OFFSETS_MS = longArrayOf(1_000, 3_000, 6_000, 9_000)
        private const val SAMPLE_STEP_MS = 3_000L
        private const val MIN_GAP_MS = 1_000L
        private const val AGREE_TOLERANCE = 0.03f
        private const val AGREE_COUNT = 4
        private const val SAMPLE_CAP = 10
        // Copy + getPixels + scan; the 3rd sample over it stops sampling (Shield acceptance: p95 copy < 4 ms).
        private const val SAMPLE_BUDGET_MS = 8f
        private const val SLOW_SAMPLE_LIMIT = 3
    }
}
