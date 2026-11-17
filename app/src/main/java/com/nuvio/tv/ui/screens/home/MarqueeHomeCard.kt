@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import coil3.size.Scale
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.WatchedMarker
import com.nuvio.tv.ui.components.brokenImageUrls
import com.nuvio.tv.ui.components.catalogPosterImageCacheKey
import com.nuvio.tv.ui.components.marquee.MarqueeCard
import com.nuvio.tv.ui.components.marquee.MarqueeCardCaption
import com.nuvio.tv.ui.components.marquee.MarqueeCardChip
import com.nuvio.tv.ui.components.marquee.MarqueeCardProgress
import com.nuvio.tv.ui.components.marquee.MarqueeCardTitle
import com.nuvio.tv.ui.components.continueWatchingImageCacheKey
import com.nuvio.tv.ui.components.continueWatchingImageModel
import com.nuvio.tv.ui.components.continueWatchingShouldBlur
import com.nuvio.tv.ui.components.continueWatchingUsesEpisodeThumbnails
import com.nuvio.tv.ui.components.rememberFolderCoverFallback
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreview
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreviewEnabled
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewAnchor
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCandidate
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewStage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.nuvio.tv.ui.theme.NuvioMarqueeHomeTokens
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import kotlinx.coroutines.delay
import com.nuvio.tv.domain.model.isPlaceholderItemId

// Marquee row-band card (Marquee spec §Components "Landscape card"): size, artwork request recipe
// shared with the row prefetchers, and the focusable card itself. Rows/band live in MarqueeHomeRows.

private const val HORIZONTAL_FOCUS_DEBOUNCE_MS = 140L

// An unfocused card under dimUntilFocused (u7 stage 8 `.hcard.dim`).
private const val DIMMED_CARD_ALPHA = 0.55f

private val CARD_BACKING = NuvioPrimitives.neutral900
private val CARD_BACKING_PAINTER = ColorPainter(CARD_BACKING)

/**
 * Card size on the Marquee row band (Spec 41 D2, u41-addon-card-shapes v2): every card in a
 * [landscapeRow] ([HeroCarouselRow.usesLandscapeCards]) is 228x128; in any other row each card keeps
 * its own [cardShape] at the poster height (poster 112, square 168, landscape 299 wide). Continue
 * Watching follows its card style.
 */
internal fun ModernCarouselItem.marqueeCardSize(
    landscapeRow: Boolean,
    continueWatchingPoster: Boolean,
    home: NuvioMarqueeHomeTokens
): ModernCatalogCardMetrics {
    if (payload is ModernPayload.ContinueWatching) return marqueeSlotSize(!continueWatchingPoster, home)
    if (landscapeRow) return marqueeSlotSize(landscape = true, home)
    val width = when (cardShape()) {
        PosterShape.POSTER -> home.portraitCardWidth
        PosterShape.SQUARE -> home.squareCardWidth
        PosterShape.LANDSCAPE -> home.wideCardWidth
    }
    return ModernCatalogCardMetrics(width, home.portraitCardHeight)
}

/** A row's plain slot: the landscape card, or the poster (row tiles such as Load more, skeletons). */
internal fun marqueeSlotSize(landscape: Boolean, home: NuvioMarqueeHomeTokens): ModernCatalogCardMetrics =
    if (landscape) ModernCatalogCardMetrics(home.landscapeCardWidth, home.landscapeCardHeight)
    else ModernCatalogCardMetrics(home.portraitCardWidth, home.portraitCardHeight)

/**
 * A row's plain slot for a row of form [landscapeRow]: Continue Watching's card, else
 * [marqueeSlotSize]. Every card in the row has the slot's height (only widths vary, Spec 41 D2);
 * the trailing tile takes the whole slot.
 */
internal fun HeroCarouselRow.marqueeRowSlotSize(
    landscapeRow: Boolean,
    continueWatchingPoster: Boolean,
    home: NuvioMarqueeHomeTokens
): ModernCatalogCardMetrics {
    val first = items.list.firstOrNull()
    return if (first?.payload is ModernPayload.ContinueWatching) {
        first.marqueeCardSize(landscapeRow, continueWatchingPoster, home)
    } else {
        marqueeSlotSize(landscapeRow, home)
    }
}

/** The row band's card height under the Card shape setting [useLandscapePosters]. */
internal fun HeroCarouselRow.marqueeCardHeight(
    useLandscapePosters: Boolean,
    continueWatchingPoster: Boolean,
    home: NuvioMarqueeHomeTokens
): Dp = if (items.list.isEmpty()) {
    home.landscapeCardHeight
} else {
    marqueeRowSlotSize(usesLandscapeCards(useLandscapePosters), continueWatchingPoster, home).height
}

/** A band row's card height; [row] null (no rows yet) = the Card shape setting's card. */
internal fun marqueeBandCardHeight(
    row: HeroCarouselRow?,
    useLandscapePosters: Boolean,
    continueWatchingPoster: Boolean,
    home: NuvioMarqueeHomeTokens
): Dp = row?.marqueeCardHeight(useLandscapePosters, continueWatchingPoster, home)
    ?: marqueeSlotSize(useLandscapePosters, home).height

internal fun ModernCarouselItem.isPlaceholderCard(): Boolean =
    (payload as? ModernPayload.Catalog)?.itemId.isPlaceholderItemId()

// Spec 96 row form (PD3): the playing card's 16:9 size, widening over ~250 ms (sandbox s96-home-1).
private val PREVIEW_CARD_WIDTH = 320.dp
private val PREVIEW_CARD_HEIGHT = 180.dp
private const val PREVIEW_WIDEN_MS = 250

/**
 * The card's trailer preview candidate (Spec 96 PD3): a catalog card with a real title; never Continue Watching,
 * folders or placeholders. Its click opens Details, which stage 2's OK reuses.
 */
internal fun ModernCarouselItem.trailerPreviewCandidate(): TrailerPreviewCandidate? {
    val catalog = payload as? ModernPayload.Catalog ?: return null
    val meta = metaPreview ?: return null
    if (isPlaceholderCard()) return null
    return TrailerPreviewCandidate(
        key = catalog.focusKey,
        itemId = meta.id,
        title = meta.name,
        releaseInfo = meta.releaseInfo,
        apiType = meta.apiType,
        fallbackYtId = meta.trailerYtIds.firstOrNull(),
        genre = meta.genres.firstOrNull(),
        runtime = meta.runtime,
        imdbRating = meta.imdbRating
    )
}

/** The artwork a catalog/folder card shows (CW cards resolve theirs from the CW settings); also
 *  the collection editor's preview tiles (Spec 28 stage 10). */
internal fun ModernCarouselItem.marqueeImageUrl(landscapeRow: Boolean): String? {
    val folder = payload as? ModernPayload.CollectionFolder
    return when {
        folder != null && !folder.coverEmoji.isNullOrBlank() -> imageUrl
        folder != null -> imageUrl ?: heroPreview.poster ?: heroPreview.backdrop
        // A landscape-shaped item's poster is its addon's own landscape art (Spec 41); the
        // catalog's poster, so enrichment never swaps it.
        cardShape() == PosterShape.LANDSCAPE ->
            metaPreview?.poster?.takeUnless { it.isPlaceholder() } ?: landscapeBackdrop()
        // Landscape cards keep their first real landscape backdrop so enrichment never swaps it;
        // until one exists they show the poster, cropped (T31, P40).
        landscapeRow -> landscapeBackdrop() ?: heroPreview.poster
        else -> imageUrl ?: heroPreview.poster ?: heroPreview.backdrop
    }
}

private fun ModernCarouselItem.landscapeBackdrop(): String? =
    (heroPreview.frozenBackdropUrl ?: heroPreview.backdrop)?.takeUnless { it.isPlaceholder() }

/**
 * One image request recipe shared by the card and both prefetchers ([request]: identical cache
 * keys and decode). The art always covers the card, centre-cropped (Spec 41 D4).
 */
internal data class MarqueeImageSpec(
    val url: String,
    val width: Dp,
    val height: Dp,
    val blur: Boolean,
    val continueWatching: Boolean
) {
    fun cacheKey(widthPx: Int, heightPx: Int): String =
        if (continueWatching) continueWatchingImageCacheKey(url, widthPx, heightPx, blur)
        else catalogPosterImageCacheKey(url, widthPx, heightPx)

    /** Decoded to fill [widthPx]x[heightPx] (the card's Crop): a prefetch puts exactly the bitmap
     *  the card reads under the same key, not a FIT decode too small to crop. */
    fun request(context: android.content.Context, widthPx: Int, heightPx: Int): ImageRequest.Builder =
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(cacheKey(widthPx, heightPx))
            .size(width = widthPx, height = heightPx)
            .scale(Scale.FILL)
            .apply { if (blur) transformations(BlurTransformation()) }
}

internal fun ModernCarouselItem.marqueeImageRequestSpec(
    landscapeRow: Boolean,
    continueWatchingPoster: Boolean,
    useEpisodeThumbnails: Boolean,
    blurUnwatchedEpisodes: Boolean,
    home: NuvioMarqueeHomeTokens
): MarqueeImageSpec? {
    val size = marqueeCardSize(landscapeRow, continueWatchingPoster, home)
    val cw = payload as? ModernPayload.ContinueWatching
    if (cw != null) {
        val cwStyle = if (continueWatchingPoster) ContinueWatchingCardStyle.POSTER else ContinueWatchingCardStyle.CARD
        val usesThumbs = continueWatchingUsesEpisodeThumbnails(cwStyle, useEpisodeThumbnails)
        val url = continueWatchingImageModel(cw.item, usesThumbs, continueWatchingPoster) ?: return null
        val blur = continueWatchingShouldBlur(cw.item, blurUnwatchedEpisodes, usesThumbs, continueWatchingPoster)
        return MarqueeImageSpec(url, size.width, size.height, blur, continueWatching = true)
    }
    if (isPlaceholderCard()) return null
    val url = marqueeImageUrl(landscapeRow)?.takeUnless { it.isPlaceholder() } ?: return null
    return MarqueeImageSpec(url, size.width, size.height, blur = false, continueWatching = false)
}

/** Enqueues [spec] unless its exact cache entry is already warm (prefetchers only). */
internal fun enqueueMarqueeImage(
    imageLoader: coil3.ImageLoader,
    context: android.content.Context,
    density: androidx.compose.ui.unit.Density,
    spec: MarqueeImageSpec
) {
    val widthPx = with(density) { spec.width.roundToPx() }
    val heightPx = with(density) { spec.height.roundToPx() }
    if (widthPx <= 0 || heightPx <= 0) return
    if (imageLoader.memoryCache?.get(MemoryCache.Key(spec.cacheKey(widthPx, heightPx))) != null) return
    imageLoader.enqueue(spec.request(context, widthPx, heightPx).build())
}

/** One focusable card: art, title/chips/progress, ring + scale + ambient glow when focused. */
@Composable
internal fun MarqueeRowItem(
    item: ModernCarouselItem,
    requester: FocusRequester,
    isTargetItem: Boolean,
    blockFocus: Boolean,
    landscapeRow: Boolean,
    continueWatchingPoster: Boolean,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    accent: () -> Color,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onFocused: () -> Unit,
    onSettled: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    // Layout size override (a grid cell, e.g. 207x116); the art is still requested at the card's
    // row size, so it shares the cache key of the same art in Home and Search rows.
    cardSize: ModernCatalogCardMetrics? = null,
    // Library's History cards (u7 stage 7): the title and the item's subtitle (when) under the
    // card instead of the title on the art.
    captioned: Boolean = false,
    // Dimmed to DIMMED_CARD_ALPHA while unfocused (Library's Hidden cards, u7 stage 8).
    dimUntilFocused: Boolean = false,
    // A white chip top-right on the focused card (Library's Restore).
    focusChip: String? = null
) {
    val home = NuvioTheme.marquee.home
    val marquee = NuvioTheme.marquee
    val context = LocalContext.current
    val density = LocalDensity.current
    var isFocused by remember { mutableStateOf(false) }
    var focusEventId by remember { mutableIntStateOf(0) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnSettled by rememberUpdatedState(onSettled)

    // Keyed on the focus key too: a placeholder card turning into real data keeps its index key,
    // and the real item must still report its selection (trailer, preload).
    val focusKey = when (val payload = item.payload) {
        is ModernPayload.Catalog -> payload.focusKey
        is ModernPayload.CollectionFolder -> payload.focusKey
        is ModernPayload.ContinueWatching -> item.key
    }
    LaunchedEffect(focusEventId, isFocused, focusKey) {
        if (focusEventId == 0 || !isFocused) return@LaunchedEffect
        // Claim focus state immediately; heavy "settled" work (trailers, preload) is debounced.
        latestOnFocused()
        val target = focusEventId
        delay(HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (isFocused && focusEventId == target) latestOnSettled()
    }
    LaunchedEffect(isTargetItem) {
        if (isTargetItem && !isFocused) runCatching { requester.requestFocus() }
    }

    val size = remember(item, landscapeRow, continueWatchingPoster, cardSize) {
        cardSize ?: item.marqueeCardSize(landscapeRow, continueWatchingPoster, home)
    }
    val folder = item.payload as? ModernPayload.CollectionFolder
    val isContinueWatching = item.payload is ModernPayload.ContinueWatching
    val isPlaceholder = item.isPlaceholderCard()
    // Bumped when CW art fails: the URL is blacklisted and the spec re-resolves to the next
    // candidate in continueWatchingImageModel's chain (as the deleted ContinueWatchingCard did).
    var continueWatchingArtFailures by remember(item.key) { mutableIntStateOf(0) }
    val spec = remember(item, landscapeRow, continueWatchingPoster, useEpisodeThumbnails, blurUnwatchedEpisodes, continueWatchingArtFailures) {
        item.marqueeImageRequestSpec(landscapeRow, continueWatchingPoster, useEpisodeThumbnails, blurUnwatchedEpisodes, home)
    }
    val folderCoverFallback = rememberFolderCoverFallback(if (folder != null) spec?.url else null)
    val showImage = spec != null && !(folder != null && folderCoverFallback.failed)
    val imageModel = remember(context, spec, density) {
        spec?.let {
            val widthPx = with(density) { it.width.roundToPx() }.coerceAtLeast(1)
            val heightPx = with(density) { it.height.roundToPx() }.coerceAtLeast(1)
            it.request(context, widthPx, heightPx).crossfade(false).build()
        }
    }
    val isLandscapeCard = size.width > size.height
    // Landscape cards and folder tiles carry their title (under the card when captioned); posters
    // show it in their art.
    val cardTitle = item.title.takeIf {
        !captioned && (isLandscapeCard || folder != null) && !isPlaceholder && it.isNotBlank()
    }
    val isWatched = item.metaPreview?.let(isCatalogItemWatched) ?: false
    val glowEnabled = folder?.focusGlowEnabled ?: true

    // Spec 96 trailer preview (PD3): Library's Hidden and History cards never preview.
    val preview = LocalTrailerPreview.current
    // Built only for the focused card; unfocused cards pay nothing while rows scroll.
    val previewCandidate = if (isFocused && preview != null && LocalTrailerPreviewEnabled.current && !captioned && !dimUntilFocused) {
        remember(item, imageModel, cardSize) { item.trailerPreviewCandidate()?.copy(poster = imageModel?.takeIf { cardSize != null }) }
    } else null
    // Only the focused card reads the host's state, so a preview recomposes one card.
    val previewState = if (preview != null && previewCandidate != null && isFocused) preview.state.value else null
    val previewPlaying = previewState != null && previewState.candidate.key == previewCandidate?.key &&
        previewState.stage != TrailerPreviewStage.LOADING
    val gridForm = cardSize != null
    val rowPreview = previewPlaying && !gridForm
    // Everything below lives only while the card is focused: unfocused cards compose no preview machinery.
    var widen: Animatable<Float, *>? = null
    var bringIntoView: BringIntoViewRequester? = null
    if (isFocused && preview != null && previewCandidate != null) {
        // Registering is a plain call (no snapshot write, PD3); blur and dispose both unregister. Keyed on the mode,
        // so a setting that loads late or changes registers again.
        // Keyed on the card's key, not the candidate: enrichment rewrites the meta of a focused card (genres,
        // runtime, rating, trailer ids) and must not close or restart its preview. The candidate registered is the
        // one at focus; the machine keeps the first candidate of a key by design (P3 round 4).
        val cardMode = preview.cardMode.value
        val latestOnClick by rememberUpdatedState(onClick)
        DisposableEffect(previewCandidate.key, cardMode) {
            val token = preview.register(previewCandidate) { latestOnClick() }
            onDispose { preview.unregister(token) }
        }
        if (!gridForm) {
            // Row form: the card takes 0 -> 1 of the way to 320 dp of the row while it plays; it snaps back on close.
            val rowWiden = remember { Animatable(0f) }
            val requester = remember { BringIntoViewRequester() }
            widen = rowWiden
            bringIntoView = requester
            if (rowPreview) {
                val bringRect = with(density) {
                    previewRowBringRect(PREVIEW_CARD_WIDTH.toPx(), size.height.toPx())
                }
                LaunchedEffect(Unit) {
                    // After the widened slot's first layout.
                    withFrameNanos {}
                    launch { requester.bringIntoView(bringRect) }
                    rowWiden.animateTo(1f, tween(PREVIEW_WIDEN_MS))
                }
            } else if (rowWiden.targetValue != 0f) { // targetValue: no per-frame read
                // Closed: the next play (a resume while still focused) widens from the rest width again.
                LaunchedEffect(Unit) { rowWiden.snapTo(0f) }
            }
        }
    }

    val fastScrollState = LocalFastScrollActive.current
    // Only the focused card shows chrome, so only it subscribes to fast-scroll changes.
    val isFastScrolling = isFocused && fastScrollState.value
    // Only the focused card reads the accent, so an accent change recomposes one card.
    val glowColor = if (isFocused && glowEnabled && !isFastScrolling) {
        accent().copy(alpha = marquee.glowCardAlpha)
    } else Color.Transparent
    val containerColor = when {
        isPlaceholder -> marquee.skeletonFill
        // Posters and Continue Watching art are opaque full-bleed and paint their own backing through
        // the placeholder; a solid container under them is a redundant overdraw pass. Folder covers
        // and square or landscape addon art keep it: they can be transparent logos.
        showImage && (isContinueWatching || (folder == null && item.cardShape() == PosterShape.POSTER)) ->
            Color.Transparent
        else -> CARD_BACKING
    }

    val card: @Composable () -> Unit = {
        MarqueeCard(
            onClick = onClick,
            onLongPress = onLongPress,
            width = if (rowPreview) PREVIEW_CARD_WIDTH else size.width,
            height = if (rowPreview) PREVIEW_CARD_HEIGHT else size.height,
            glowColor = glowColor,
            containerColor = containerColor,
            showFocusChrome = !isFastScrolling,
            // The playing card does not scale (PD3); the grid form also hides under its lifted copy (D10).
            focusedScale = if (previewPlaying) 1f else NuvioTheme.marquee.cardScale,
            modifier = Modifier
                .then(
                    // Before the slot, so its coordinates are the slot's (the rect in bringIntoView above).
                    bringIntoView?.takeIf { rowPreview }?.let { Modifier.bringIntoViewRequester(it) } ?: Modifier
                )
                .then(
                    widen?.takeIf { rowPreview }?.let { rowWiden ->
                        Modifier.previewRowSlot(
                            restWidth = size.width,
                            restHeight = size.height,
                            landscapeRow = landscapeRow,
                            progress = { rowWiden.value }
                        )
                    } ?: Modifier
                )
                .then(
                    // Playing: the overlay's anchor; loading (Spec 123 C6): the spinner's, with the scale it draws at. A grid
                    // card also pushes while its preview loads hidden (C2), for the Morph's start.
                    if (preview != null && previewCandidate != null && (previewPlaying ||
                            preview.loadingKey.value == previewCandidate.key ||
                            (gridForm && previewState?.candidate?.key == previewCandidate.key &&
                                previewState.stage == TrailerPreviewStage.LOADING))
                    ) {
                        val drawnScale = if (previewPlaying || isFastScrolling) 1f else NuvioTheme.marquee.cardScale
                        Modifier.onGloballyPositioned { coordinates ->
                            if (!coordinates.isAttached) return@onGloballyPositioned
                            val position = coordinates.positionInRoot()
                            val anchor = TrailerPreviewAnchor(
                                key = previewCandidate.key,
                                boundsInRoot = Rect(
                                    position.x,
                                    position.y,
                                    position.x + coordinates.size.width,
                                    position.y + coordinates.size.height
                                ),
                                grid = gridForm,
                                accent = accent(),
                                scale = drawnScale
                            )
                            if (previewPlaying) preview.pushAnchor(anchor) else preview.pushLoadingAnchor(anchor)
                        }
                    } else Modifier
                )
                .then(if (previewPlaying && gridForm) Modifier.graphicsLayer { alpha = 0f } else Modifier)
                .then(
                    if (dimUntilFocused) Modifier.graphicsLayer { alpha = if (isFocused) 1f else DIMMED_CARD_ALPHA }
                    else Modifier
                )
                .then(if (blockFocus) Modifier.focusProperties { canFocus = false } else Modifier)
                .focusRequester(requester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) focusEventId += 1
                }
        ) {
            when {
                isPlaceholder -> Unit
                showImage -> {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = CARD_BACKING_PAINTER,
                        error = CARD_BACKING_PAINTER,
                        // Every image covers its card, folder covers included (Spec 41 D4).
                        contentScale = ContentScale.Crop,
                        onError = when {
                            folder != null -> { _ -> folderCoverFallback.onCoverError(spec.url) }
                            isContinueWatching -> { _ ->
                                brokenImageUrls.add(spec.url)
                                continueWatchingArtFailures++
                            }
                            else -> null
                        }
                    )
                }
                !folder?.coverEmoji.isNullOrBlank() -> Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = folder.coverEmoji.orEmpty(), fontSize = 56.sp)
                }
                else -> MonochromePosterPlaceholder()
            }

            if (folder != null && folder.focusGifEnabled && isFocused && !folder.focusGifUrl.isNullOrBlank()) {
                FocusGifOverlay(url = folder.focusGifUrl, spec = spec)
            }

            if (cardTitle != null) MarqueeCardTitle(cardTitle)

            item.cornerLabel?.takeIf { it.isNotBlank() }?.let { MarqueeCardChip(it) }

            if (focusChip != null && isFocused) {
                MarqueeCardChip(
                    text = focusChip,
                    color = NuvioPrimitives.black,
                    fill = NuvioPrimitives.white,
                    alignment = Alignment.TopEnd,
                    inset = 8.dp
                )
            }

            // Continue Watching and Library's History set a progress on unfinished titles.
            item.progressFraction?.takeIf { it > 0f }?.let { fraction ->
                MarqueeCardProgress(
                    fraction = fraction,
                    fill = if (isFocused) accent() else NuvioTheme.colors.TextPrimary,
                    inset = if (isLandscapeCard) 12.dp else 10.dp
                )
            }

            if (isWatched) {
                WatchedMarker(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
                )
            }
        }
    }

    if (captioned) {
        Column(modifier = Modifier.width(size.width)) {
            card()
            MarqueeCardCaption(title = item.title, detail = item.subtitle, gap = marquee.library.cardCaptionGap)
        }
    } else {
        card()
    }
}

/** Collection folder focus GIF, faded in over the cover once loaded; covers the tile like it. */
@Composable
private fun FocusGifOverlay(url: String, spec: MarqueeImageSpec?) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = spec?.let { with(density) { it.width.roundToPx() } } ?: 0
    val heightPx = spec?.let { with(density) { it.height.roundToPx() } } ?: 0
    val model = remember(context, url, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (widthPx > 0 && heightPx > 0) {
                    memoryCacheKey("${url}_${widthPx}x$heightPx")
                    size(width = widthPx, height = heightPx)
                }
            }
            .build()
    }
    var loaded by remember(url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "folderGifFade"
    )
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
        contentScale = ContentScale.Crop,
        onSuccess = { loaded = true }
    )
}

/**
 * Spec 96 row form (PD3): measures the card at 320x180 but takes [restWidth] -> 320 dp of the row ([progress]) and
 * its rest height, so the row shifts aside (D6) without growing; poster-height rows (wide-shape cards included)
 * overflow evenly above and below, landscape rows downward (sandbox s96-home-1, s96-home-land).
 */
private fun Modifier.previewRowSlot(restWidth: Dp, restHeight: Dp, landscapeRow: Boolean, progress: () -> Float): Modifier =
    layout { measurable, _ ->
        val width = PREVIEW_CARD_WIDTH.roundToPx()
        val height = PREVIEW_CARD_HEIGHT.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        val slotHeight = restHeight.roundToPx()
        layout(previewRowSlotWidth(restWidth.roundToPx(), width, progress()), slotHeight) {
            placeable.place(0, previewRowCardY(slotHeight, height, landscapeRow))
        }
    }

/** The row slot's width at [progress] of the widen: the rest card's [rest] -> the preview card's [full]. */
internal fun previewRowSlotWidth(rest: Int, full: Int, progress: Float): Int = (rest + (full - rest) * progress).roundToInt()

/** Where the preview card sits in its slot: centred on a poster-height row (overflow above and below), top on a landscape row. */
internal fun previewRowCardY(slotHeight: Int, cardHeight: Int, landscapeRow: Boolean): Int =
    if (landscapeRow) 0 else (slotHeight - cardHeight) / 2

/**
 * The rect the widened card brings into view, in the slot's coordinates: the full 320 dp width at the row's own
 * height, so the row scrolls sideways and the Home band never moves vertically (PD3, D6).
 */
internal fun previewRowBringRect(fullWidth: Float, restHeight: Float): Rect = Rect(0f, 0f, fullWidth, restHeight)

