package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.errorStateText
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonTextBar
import com.nuvio.tv.ui.screens.profile.ceiling
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

// Detail states (Marquee spec §Screens "Detail as decided at the U2 slice", sandbox
// u2-detail-marquee v2 stages 11 loading and 12 error).

// Skeleton bar sizes from the approved mockup (stage 11), dp; positions follow the real first
// frame (DetailInfoColumn line boxes and gaps, the band at `detail.bandFirstFrameTop`).
private val BAR_RADIUS = 4.dp
private val EYEBROW_BAR = DpSize(150.dp, 12.dp)
private val TITLE_BAR = DpSize(280.dp, 56.dp)
private val META_BAR = DpSize(360.dp, 16.dp)
private val SOURCE_BAR = DpSize(300.dp, 20.dp)
private val DESCRIPTION_BARS = listOf(DpSize(460.dp, 14.dp), DpSize(440.dp, 14.dp), DpSize(300.dp, 14.dp))
private val CREDIT_BAR = DpSize(160.dp, 14.dp)
private val PRIMARY_BAR_WIDTH = 170.dp
// Library, About, and Watched (movies) or Trailer: the common count before the title type is known.
private const val GLASS_BARS = 3
private val BAND_TITLE_BAR = DpSize(300.dp, 16.dp)

/** The loaded page's fade-in, as Home's content (320 ms). */
private const val CONTENT_ENTER_MS = 320

/**
 * Detail loading state: the backdrop handed over from Home ([backdropUrl], cropped and
 * scrimmed as the first frame) under a static skeleton in the first-frame geometry: the info
 * column (eyebrow, title, meta, source line, 3 description lines, credit, primary + 2 glass
 * buttons), then the episodes band (title bar + 208x117 cards). Black without a backdrop.
 */
@Composable
internal fun DetailLoadingState(backdropUrl: String?, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            MarqueeBackdropImage(url = backdropUrl, focal = DETAIL_FOCAL, modifier = Modifier.fillMaxSize())
            MarqueeScrims(
                leftStops = marquee.scrimLeftDetailStops,
                bottomStops = marquee.scrimBottomDetailStops,
                drawTopScrim = false
            )
        }
        DetailSkeleton()
    }
}

@Composable
private fun DetailSkeleton() {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val styles = NuvioTheme.textStyles
    val start = NuvioTheme.layout.tvSafeHorizontal
    val density = LocalDensity.current
    val eyebrowLine = with(density) { styles.eyebrow.lineHeight.toDp() }
    val titleLine = with(density) { styles.displayHero.lineHeight.toDp() }
    val metaLine = with(density) { styles.meta.lineHeight.toDp() }
    val bodyLine = with(density) { styles.bodyDetail.lineHeight.toDp() }
    val primaryHeight = marquee.primaryButton.height
    val glassSize = marquee.glassButton.size
    val cardGap = marquee.home.cardGap
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val fill = marquee.skeletonFill
                fun textBar(lineTop: Dp, lineHeight: Dp, bar: DpSize) =
                    marqueeSkeletonTextBar(fill, start, lineTop, lineHeight, bar, BAR_RADIUS)

                // Info column, top-down with the column gap.
                var y = detail.infoTop
                textBar(y, eyebrowLine, EYEBROW_BAR)
                y += eyebrowLine + detail.infoGap
                textBar(y, titleLine, TITLE_BAR)
                y += titleLine + detail.infoGap
                textBar(y, metaLine, META_BAR)
                y += metaLine + detail.infoGap
                textBar(y, detail.sourceLineHeight, SOURCE_BAR)
                y += detail.sourceLineHeight + detail.infoGap
                DESCRIPTION_BARS.forEach { line ->
                    textBar(y, bodyLine, line)
                    y += bodyLine
                }
                y += detail.infoGap
                textBar(y, metaLine, CREDIT_BAR)
                y += metaLine + detail.infoGap + detail.actionsExtraTop
                marqueeSkeletonBar(fill, start, y, PRIMARY_BAR_WIDTH, primaryHeight, primaryHeight / 2)
                var x = start + PRIMARY_BAR_WIDTH
                repeat(GLASS_BARS) {
                    x += detail.actionGap
                    marqueeSkeletonBar(fill, x, y + (primaryHeight - glassSize) / 2, glassSize, glassSize, glassSize / 2)
                    x += glassSize
                }

                // Episodes band peeking below the fold: a bar in the chip row, then the cards.
                val bandTop = detail.bandFirstFrameTop
                textBar(bandTop, detail.chipHeight, BAND_TITLE_BAR)
                val cardsTop = bandTop + detail.chipHeight + detail.chipsToCards
                x = start
                while (x.toPx() < size.width) {
                    marqueeSkeletonBar(fill, x, cardsTop, detail.cardWidth, detail.cardHeight, marquee.cardRadius)
                    x += detail.cardWidth + cardGap
                }
            }
    )
}

/**
 * The loaded page fading in over [DetailLoadingState], which stays composed under it until the
 * fade ends, so the handed-over backdrop never blinks and the skeleton dissolves into the page.
 * [animate] false (the screen re-entered with its content already loaded, e.g. back from the
 * player) shows the page at once.
 */
@Composable
internal fun DetailContentEnter(
    animate: Boolean,
    backdropUrl: String?,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(if (animate) 0f else 1f) }
    val entering by remember { derivedStateOf { alpha.value < 1f } }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(CONTENT_ENTER_MS)) }
    if (entering) DetailLoadingState(backdropUrl = backdropUrl)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        content()
    }
}

/**
 * Detail error state: Home's message block with the "DETAILS" eyebrow, the error message as the
 * body and one "Retry" button, focused on entry. Back leaves the screen (the screen's handler).
 */
@Composable
internal fun DetailErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    MarqueeMessage(
        eyebrow = stringResource(R.string.detail_state_eyebrow),
        title = stringResource(R.string.detail_error_title),
        body = errorStateText(message),
        actionLabel = stringResource(R.string.action_retry),
        actionIcon = Icons.Default.Refresh,
        onAction = onRetry,
        focusRequester = focusRequester,
        modifier = modifier
    )
}

// Lock disc (sandbox spec-64-k2-rating-cap stages 7-8, `.lockic`), dp.
private val LOCK_DISC_SIZE = 44.dp
private val LOCK_ICON_SIZE = 22.dp
private val LOCK_DISC_BOTTOM = 4.dp
private const val LOCK_DISC_FILL_ALPHA = 0.10f

/**
 * Spec 64 D13 block, instead of the page for a title outside the active profile's rating limit:
 * Home's message block on black (no artwork) under a lock disc, the title's name as the eyebrow,
 * why it is out (its rating against the limit's ceiling, or unrated), Back focused, and Switch
 * profile to Who's watching (the PIN stays the way out).
 */
@Composable
internal fun DetailRatingBlockState(
    block: DetailRatingBlock,
    onBack: () -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val name = block.limit.profileName ?: stringResource(R.string.rating_limit_profile_fallback)
    val body = if (block.rating != null) {
        val (movieCeiling, tvCeiling) = block.limit.cap.ceiling()
        stringResource(R.string.detail_rating_block_rated, block.rating, name, movieCeiling, tvCeiling)
    } else {
        stringResource(R.string.detail_rating_block_unrated, name)
    }
    MarqueeMessage(
        eyebrow = block.title,
        title = stringResource(R.string.detail_rating_block_title, name),
        body = AnnotatedString(body),
        actionLabel = stringResource(R.string.action_back),
        actionIcon = Icons.AutoMirrored.Filled.ArrowBack,
        onAction = onBack,
        focusRequester = focusRequester,
        secondaryLabel = stringResource(R.string.detail_rating_block_switch_profile),
        onSecondary = onSwitchProfile,
        leading = { RatingLockDisc() },
        modifier = modifier
    )
}

@Composable
private fun RatingLockDisc() {
    Box(
        modifier = Modifier
            .padding(bottom = LOCK_DISC_BOTTOM)
            .size(LOCK_DISC_SIZE)
            .background(NuvioPrimitives.white.copy(alpha = LOCK_DISC_FILL_ALPHA), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = NuvioPrimitives.warning,
            modifier = Modifier.size(LOCK_ICON_SIZE)
        )
    }
}
