package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

/**
 * Spec 62 H2d (D7, upstream NuvioTV 0d08672f5): draws this node's content with its RGB scaled by
 * [factor] and alpha kept, so controls and panels over HDR video do not draw at full graphics
 * brightness. A factor of 1 or more returns the modifier unchanged (no offscreen layer). The video
 * surface and its cues must stay outside every node that carries it, or cues dim twice.
 *
 * Remembered per factor: each call builds a new drawWithCache lambda, so an unremembered call
 * would rebuild the cache and its Paint on every recomposition. [slideOverflow]: the carrier is an
 * AnimatedVisibility whose content slides by a fraction of its size past its bounds (the post-play
 * card); every other carrier stays within its bounds plus a focus glow, so its layer stays small.
 */
@Composable
internal fun rememberPlayerControlsDim(factor: Float, slideOverflow: Boolean = false): Modifier =
    remember(factor, slideOverflow) { Modifier.playerControlsBrightness(factor, slideOverflow) }

private fun Modifier.playerControlsBrightness(factor: Float, slideOverflow: Boolean): Modifier {
    if (factor >= 1f) return this
    val scale = factor.coerceIn(0f, 1f)
    return drawWithCache {
        val paint = Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(scale, scale, scale, 1f) })
        }
        // Focused controls scale up and glow past their bounds (LAYER_OVERFLOW covers it); a sliding
        // carrier also moves its content up to its own width or height out. The canvas clip still
        // bounds the allocation to what is on screen.
        val slide = if (slideOverflow) maxOf(size.width, size.height) else 0f
        val layerBounds = size.toRect().inflate(slide + LAYER_OVERFLOW.toPx())
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(layerBounds, paint)
                drawContent()
                canvas.restore()
            }
        }
    }
}

private val LAYER_OVERFLOW = 48.dp
