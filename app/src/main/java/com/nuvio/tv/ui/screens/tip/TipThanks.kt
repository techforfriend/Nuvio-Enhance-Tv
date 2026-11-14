package com.nuvio.tv.ui.screens.tip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * One spark of the thank-you burst (Spec 81 D8): it flies to ([x], [y]) dp from the heart's centre,
 * turning [rotation] degrees, then drifts on and falls 110 dp while it fades. [size] dp; [colourIndex]
 * indexes the spark colours; [heart] draws a tiny heart instead of a dot; [delayMs] staggers the start.
 */
internal data class Spark(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val size: Float,
    val colourIndex: Int,
    val heart: Boolean,
    val delayMs: Int
)

/** Where a spark is at one moment: offset dp from the heart's centre, scale, degrees, alpha. */
internal data class SparkFrame(val dx: Float, val dy: Float, val scale: Float, val rotation: Float, val alpha: Float)

internal const val SPARK_COUNT = 30
internal const val SPARK_MS = 1_700
internal const val SPARK_MAX_DELAY_MS = 160
private const val SPARK_FALL_DP = 110f

/** The whole animation: every part has settled by then (the longest-starting spark ends last). */
internal const val THANKS_TOTAL_MS = SPARK_MAX_DELAY_MS + SPARK_MS

/**
 * The heart blooms over this long (scale 0.4 to 1.08 to 1); the text rises from [TEXT_START_MS], 40 ms
 * apart; Done only fades in, [DONE_DELAY_MS] after the text starts (the mockup's d0 + 160).
 */
internal const val HEART_MS = 500
internal const val TEXT_START_MS = 420
internal const val TEXT_RISE_MS = 250
internal const val TEXT_STAGGER_MS = 40
private const val TEXT_RISE_DP = 12f
internal const val DONE_DELAY_MS = 160

/**
 * The same 30 sparks on every showing (seeded; the same recipe as sandbox e1-thanks, not its exact
 * positions): evenly round the circle with a little jitter, 170 to 360 dp out, flattened to the TV's
 * shape and lifted 40 dp; every fifth a 16 dp heart.
 */
internal fun thanksSparks(colours: Int): List<Spark> {
    val random = Random(81)
    return List(SPARK_COUNT) { i ->
        val angle = i.toFloat() / SPARK_COUNT * 2f * PI.toFloat() + random.nextFloat() * 0.24f - 0.12f
        val distance = 170f + random.nextFloat() * 190f
        val heart = i % 5 == 0
        Spark(
            x = distance * cos(angle) * 1.25f,
            y = distance * sin(angle) * 0.8f - 40f,
            rotation = random.nextInt(-120, 121).toFloat(),
            size = if (heart) 16f else listOf(6f, 7f, 8f, 10f, 12f)[random.nextInt(5)],
            colourIndex = random.nextInt(colours),
            heart = heart,
            delayMs = random.nextInt(SPARK_MAX_DELAY_MS + 1)
        )
    }
}

/**
 * [spark] at [tMs] after the screen opened: hidden before its start; fades in over the first 12 %;
 * flies out over the first 45 % (scale 0.3 to 1); then drifts 12 % further, falls and fades to 0 by
 * the end, shrinking to 0.7. Linear throughout, as drawn.
 */
internal fun sparkAt(spark: Spark, tMs: Float): SparkFrame {
    val p = ((tMs - spark.delayMs) / SPARK_MS).coerceIn(0f, 1f)
    if (p <= 0f) return SparkFrame(0f, 0f, 0.3f, 0f, 0f)
    return if (p <= OUT) {
        val q = p / OUT
        SparkFrame(spark.x * q, spark.y * q, 0.3f + 0.7f * q, spark.rotation * q, (p / FADE_IN).coerceAtMost(1f))
    } else {
        val q = (p - OUT) / (1f - OUT)
        SparkFrame(
            dx = spark.x * (1f + 0.12f * q),
            dy = spark.y + SPARK_FALL_DP * q,
            scale = 1f - 0.3f * q,
            rotation = spark.rotation * (1f + q),
            alpha = 1f - q
        )
    }
}

private const val OUT = 0.45f
private const val FADE_IN = 0.12f

/** The heart's (scale, alpha) at [tMs]: 0.4 to 1.08 over the first 55 % of [HEART_MS] (fading in), then to 1. */
internal fun heartAt(tMs: Float): Pair<Float, Float> {
    val p = (tMs / HEART_MS).coerceIn(0f, 1f)
    return if (p <= 0.55f) {
        val q = emphasized(p / 0.55f)
        (0.4f + 0.68f * q) to q
    } else {
        (1.08f - 0.08f * emphasized((p - 0.55f) / 0.45f)) to 1f
    }
}

/** Text line [index] (0 = title) at [tMs]: (alpha, rise dp still to go), the hero text motion. */
internal fun textAt(index: Int, tMs: Float): Pair<Float, Float> {
    val start = TEXT_START_MS + index * TEXT_STAGGER_MS
    val q = emphasized(((tMs - start) / TEXT_RISE_MS).coerceIn(0f, 1f))
    return q to TEXT_RISE_DP * (1f - q)
}

/** Done's alpha at [tMs]: a fade, no rise (`.btn` uses s81fade in the mockup). */
internal fun doneAt(tMs: Float): Float =
    emphasized(((tMs - TEXT_START_MS - DONE_DELAY_MS) / TEXT_RISE_MS).coerceIn(0f, 1f))

/** The design system's emphasized easing (0.2, 0, 0, 1). */
private fun emphasized(x: Float): Float = NuvioMotion.tokens.easings.emphasized.transform(x)

/**
 * The thank-you screen (sandbox spec-81-donate v3, stage e1-thanks; D7/D8): sparks burst from a
 * blooming heart, the words rise in, Done (focused by the dialog through [doneFocus]) fades in; no auto-close. One [Animatable] drives every part,
 * read in draw and layer blocks only (no recomposition per frame); Android's animator duration scale
 * reaches it through Compose's MotionDurationScale, so with Remove animations it opens settled.
 */
@Composable
internal fun TipThanks(doneFocus: FocusRequester, onDone: () -> Unit) {
    val tokens = NuvioTheme.marquee.tip
    val time = remember { Animatable(0f) }
    val sparks = remember(tokens.sparkColors.size) { thanksSparks(tokens.sparkColors.size) }
    val sparkHeart = rememberVectorPainter(Icons.Filled.Favorite)
    LaunchedEffect(Unit) {
        time.animateTo(THANKS_TOTAL_MS.toFloat(), tween(THANKS_TOTAL_MS, easing = LinearEasing))
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
            // `#s81grad`: stops at 0, 0.55 and 1, top-left to bottom-right.
            val gradient = Brush.linearGradient(
                0f to tokens.heartGradient[0], 0.55f to tokens.heartGradient[1], 1f to tokens.heartGradient[2]
            )
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = NuvioPrimitives.white,
                modifier = Modifier
                    .size(tokens.thanksHeart)
                    // Sparks first, behind and outside the heart's box (draw is not clipped).
                    .drawBehind {
                        val t = time.value
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        sparks.forEach { spark ->
                            val f = sparkAt(spark, t)
                            if (f.alpha <= 0f) return@forEach
                            val colour = tokens.sparkColors[spark.colourIndex]
                            val side = spark.size.dp.toPx() * f.scale
                            val at = centre + Offset(f.dx.dp.toPx(), f.dy.dp.toPx())
                            if (spark.heart) {
                                translate(at.x - side / 2f, at.y - side / 2f) {
                                    rotate(f.rotation, pivot = Offset(side / 2f, side / 2f)) {
                                        with(sparkHeart) {
                                            draw(Size(side, side), alpha = f.alpha, colorFilter = ColorFilter.tint(colour))
                                        }
                                    }
                                }
                            } else {
                                drawCircle(colour, radius = side / 2f, center = at, alpha = f.alpha)
                            }
                        }
                    }
                    .graphicsLayer {
                        val (scale, alpha) = heartAt(time.value)
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    // The white glyph takes the coral gradient.
                    .drawWithContent {
                        drawContent()
                        drawRect(gradient, blendMode = BlendMode.SrcAtop)
                    }
            )
            ThanksLine(0, time, Modifier.padding(top = tokens.thanksHeartGap)) {
                Text(
                    text = stringResource(R.string.tip_thanks_title),
                    style = NuvioTheme.textStyles.signInTitle.copy(
                        fontSize = tokens.thanksTitleSize,
                        lineHeight = tokens.thanksTitleLineHeight
                    ),
                    color = NuvioPrimitives.white
                )
            }
            ThanksLine(1, time, Modifier.padding(top = tokens.thanksLineTop).widthIn(max = tokens.thanksLineWidth)) {
                Text(
                    text = stringResource(R.string.tip_thanks_line),
                    style = NuvioTheme.textStyles.signInIntro.copy(fontSize = 17.sp, lineHeight = 25.sp),
                    color = NuvioTheme.marquee.onImage2,
                    textAlign = TextAlign.Center
                )
            }
            ThanksLine(2, time, Modifier.padding(top = tokens.thanksSignatureTop)) {
                Text(
                    text = stringResource(R.string.tip_thanks_signature),
                    style = NuvioTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.signature
                )
            }
            Box(modifier = Modifier.padding(top = tokens.thanksDoneTop).graphicsLayer { alpha = doneAt(time.value) }) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.tip_done),
                    onClick = onDone,
                    modifier = Modifier.focusRequester(doneFocus)
                )
            }
        }
    }
}

/** One line of the thank-you text, faded and lifted by [textAt] in its layer (no recomposition per frame). */
@Composable
private fun ThanksLine(index: Int, time: Animatable<Float, *>, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.graphicsLayer {
            val (alpha, rise) = textAt(index, time.value)
            this.alpha = alpha
            translationY = rise.dp.toPx()
        }
    ) { content() }
}
