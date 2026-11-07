package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.computeAirDateBadgeText
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.formatContinueWatchingProgressLabel
import com.nuvio.tv.ui.components.brokenImageUrls
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableSet
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.extractYearOrRange
import com.nuvio.tv.ui.util.extractYearText

internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 450L
// Focus changes closer together than this are rapid D-pad travel and use the rapid settle delay.
private const val RAPID_NAV_THRESHOLD_MS = 130L
private const val RAPID_NAV_SETTLE_MS = 400L
internal const val MODERN_CONTINUE_WATCHING_ROW_KEY = "continue_watching"
internal const val MODERN_UPCOMING_ROW_KEY = "upcoming_section"
internal const val MODERN_FEATURED_ROW_KEY = "featured"

@Immutable
data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    /** Continue Watching series only: "S1 E3 · Episode title", leads the hero meta row. */
    val episodeLabel: String?,
    val isSeries: Boolean = false,
    val yearText: String?,
    val runtimeText: String? = null,
    /** Continue Watching status beside the hero progress bar ("8 min left", "Next Up"). */
    val secondaryHighlightText: String? = null,
    val imdbText: String?,
    /** TMDB-sourced items' TMDB score; shown only without [imdbText] (26/B2). */
    val tmdbText: String? = null,
    val genres: StableList<String>,
    // Catalog titles only (sandbox data-additions-marquee st1): the type eyebrow ("Series · 2
    // seasons · Ended"), the age rating after the year and the credit line. Season count, status,
    // rating and credits mostly arrive with Home's meta enrichment; without them the eyebrow is
    // the type alone.
    val apiType: String? = null,
    val seasonCount: Int? = null,
    val status: String? = null,
    val ageRating: String? = null,
    val directors: StableList<String> = StableList(),
    val writers: StableList<String> = StableList(),
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?,
    /** Snapshot of the first real landscape backdrop (background or landscape poster, never the
     *  poster fallback). Survives cache rebuilds so landscape cards keep their original art
     *  even after navigation away and back. */
    val frozenBackdropUrl: String? = null,
    /** Same idea for the logo URL. */
    val frozenLogoUrl: String? = null
)

@Immutable
sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
    data class CollectionFolder(
        val focusKey: String,
        val collectionId: String,
        val collectionTitle: String,
        val folderId: String,
        val focusGlowEnabled: Boolean,
        val focusGifEnabled: Boolean,
        val focusGifUrl: String?,
        val heroBackdropUrl: String?,
        val heroVideoUrl: String?,
        val titleLogoUrl: String?,
        val coverEmoji: String? = null,
        /** The folder's own Tile shape (Spec 41 D5), the shape its tile draws in. */
        val tileShape: PosterShape = PosterShape.POSTER
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val focusKey: String,
    val payload: ModernPayload
)

@Immutable
data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null,
    val cornerLabel: String? = null,
    val progressFraction: Float? = null
)

@Immutable
data class HeroCarouselRow(
    val key: String,
    val title: String,
    /** Small text after the title (the addon name when that setting is on). */
    val titleSuffix: String? = null,
    val globalRowIndex: Int,
    val items: StableList<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    /**
     * Optional focusable pill rendered beside the row title (CloudStream's provider
     * selector). Only the first row of a section carries one.
     */
    val headerAction: MarqueeRowHeaderAction? = null
)

/** Label + click of a pill rendered beside a row title. */
data class MarqueeRowHeaderAction(
    val label: String,
    val onClick: () -> Unit
)

@Immutable
data class CarouselRowLookups(
    val rowIndexByKey: StableMap<String, Int>,
    val rowByKey: StableMap<String, HeroCarouselRow>,
    val rowKeyByGlobalRowIndex: StableMap<Int, String>,
    val activeRowKeys: StableSet<String>,
    val activeCatalogItemIds: StableSet<String>
)

@Immutable
data class ModernHomePresentationState(
    val rows: StableList<HeroCarouselRow> = StableList(),
    val lookups: CarouselRowLookups = CarouselRowLookups(
        rowIndexByKey = StableMap(),
        rowByKey = StableMap(),
        rowKeyByGlobalRowIndex = StableMap(),
        activeRowKeys = StableSet(),
        activeCatalogItemIds = StableSet()
    )
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showAddonName: Boolean,
    val localeTag: String,
    val mappedRow: HeroCarouselRow
)

internal data class ModernCollectionRowBuildCacheEntry(
    val source: Collection,
    val mappedRow: HeroCarouselRow
)
/** Everything a Continue Watching / Upcoming row is built from; equal inputs reuse the row. */
internal data class ContinueWatchingRowInputs(
    val items: List<ContinueWatchingItem>,
    val title: String,
    val airsDateTemplate: String,
    val upcomingLabel: String,
    val useLandscapePosters: Boolean
)

internal class ContinueWatchingRowCacheSlot {
    var inputs: ContinueWatchingRowInputs? = null
    var row: HeroCarouselRow? = null
}

@Stable
class ModernCarouselRowBuildCache {
    internal val continueWatching = ContinueWatchingRowCacheSlot()
    internal val upcoming = ContinueWatchingRowCacheSlot()
    internal val catalogRows = java.util.concurrent.ConcurrentHashMap<String, ModernCatalogRowBuildCacheEntry>()
    internal val collectionRows = java.util.concurrent.ConcurrentHashMap<String, ModernCollectionRowBuildCacheEntry>()
    // per-item cache: rowKey -> (itemId -> cached carousel item + source MetaPreview)
    internal val catalogItemCache = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, CachedCarouselItem>>()
}

internal data class CachedCarouselItem(
    val source: MetaPreview,
    val useLandscapePosters: Boolean,
    val showFullReleaseDate: Boolean,
    val carouselItem: ModernCarouselItem
)

@Immutable
internal data class ModernCatalogCardMetrics(
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp
)

/**
 * A card's own shape (Spec 41 D2/D5, sandbox u41-addon-card-shapes v2): a folder's Tile shape, else
 * the shape its addon asks for (Stremio posterShape; none = poster). Continue Watching cards follow
 * their own card style instead.
 */
internal fun ModernCarouselItem.cardShape(): PosterShape = when (val payload = payload) {
    is ModernPayload.CollectionFolder -> payload.tileShape
    else -> metaPreview?.posterShape ?: PosterShape.POSTER
}

/**
 * Whether a row takes the landscape row form (228x128, Spec 41 D1/D2): the Card shape setting is
 * Landscape ([useLandscapePosters], a full override), or every real card in it is landscape.
 * Otherwise each card keeps its own shape at the poster row's height.
 */
internal fun HeroCarouselRow.usesLandscapeCards(useLandscapePosters: Boolean): Boolean =
    items.list.usesLandscapeCards(useLandscapePosters)

/** The same rule for a grid's cards. */
internal fun List<ModernCarouselItem>.usesLandscapeCards(useLandscapePosters: Boolean): Boolean =
    useLandscapePosters || allCardsShaped(PosterShape.LANDSCAPE)

/** At least one real card, and every real card has [shape]; placeholder cards (loading) don't count. */
internal fun List<ModernCarouselItem>.allCardsShaped(shape: PosterShape): Boolean {
    var anyReal = false
    for (item in this) {
        if (item.isPlaceholderCard()) continue
        if (item.cardShape() != shape) return false
        anyReal = true
    }
    return anyReal
}

internal fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean,
    airsDateTemplate: String,
    upcomingLabel: String,
    context: android.content.Context
): ModernCarouselItem {
    val secondaryHighlightText = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val progress = item.progress
            formatContinueWatchingProgressLabel(
                progress = progress,
                resumeLabel = context.getString(R.string.cw_resume),
                percentWatchedLabel = context.getString(R.string.cw_percent_watched),
                hoursMinLeftLabel = context.getString(R.string.cw_hours_min_left),
                minLeftLabel = context.getString(R.string.cw_min_left)
            )
        }
        is ContinueWatchingItem.NextUp -> {
            if (item.info.isReleaseAlert) {
                if (item.info.isNewSeasonRelease) {
                    context.getString(R.string.cw_new_season)
                } else {
                    context.getString(R.string.cw_new_episode)
                }
            } else if (!item.info.hasAired) {
                computeAirDateBadgeText(context, item.info.released, item.info.airDateLabel)
                    ?: context.getString(R.string.cw_upcoming)
            } else {
                context.getString(R.string.cw_next_up)
            }
        }
    }

    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val isSeries = isSeriesType(item.progress.contentType)
            val s = item.progress.season
            val e = item.progress.episode
            val episodeCode = if (s != null && e != null) {
                context.getString(R.string.season_episode_format, s, e)
            } else {
                null
            }
            val episodeTitle = item.progress.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
            val episodeLabel = when {
                isSeries && episodeCode != null && episodeTitle != null -> "$episodeCode · $episodeTitle"
                isSeries && episodeCode != null -> episodeCode
                isSeries && episodeTitle != null -> episodeTitle
                else -> null
            }
            HeroPreview(
                title = item.progress.name,
                logo = item.progress.logo,
                description = item.episodeDescription ?: item.progress.episodeTitle?.localizeEpisodeTitle(context),
                episodeLabel = episodeLabel,
                isSeries = isSeries,
                yearText = extractYearOrRange(item.releaseInfo),
                secondaryHighlightText = secondaryHighlightText,
                imdbText = formatRatingScore(item.episodeImdbRating),
                genres = item.genres.asStable(),
                poster = item.progress.poster,
                backdrop = item.progress.backdrop,
                imageUrl = if (useLandscapePosters) {
                    item.progress.backdrop ?: item.progress.poster
                } else {
                    item.progress.poster ?: item.progress.backdrop
                }
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = context.getString(
                R.string.season_episode_format,
                item.info.season,
                item.info.episode
            )
            val episodeTitle = item.info.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            HeroPreview(
                title = item.info.name,
                logo = item.info.logo,
                description = item.info.episodeDescription
                    ?: item.info.episodeTitle?.localizeEpisodeTitle(context)
                    ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
                episodeLabel = episodeLabel,
                isSeries = true,
                yearText = extractYearOrRange(item.info.releaseInfo),
                secondaryHighlightText = secondaryHighlightText,
                imdbText = formatRatingScore(item.info.imdbRating),
                genres = item.info.genres.asStable(),
                poster = item.info.poster,
                backdrop = item.info.backdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
                } else {
                    firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
                }
            )
        }
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(item.episodeThumbnail, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.backdrop, item.progress.poster)
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(heroPreview.poster, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.poster, item.progress.backdrop)
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(item.info.thumbnail, item.info.poster, item.info.backdrop)
            } else {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            }
        } else {
            firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
        }
    }

    // Card chip (Marquee): the episode code for a series in progress, "<status> · code" for
    // Next Up; movies in progress carry none.
    val cornerLabel = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val s = item.progress.season
            val e = item.progress.episode
            if (isSeriesType(item.progress.contentType) && s != null && e != null) {
                context.getString(R.string.season_episode_format, s, e)
            } else {
                null
            }
        }
        is ContinueWatchingItem.NextUp -> "$secondaryHighlightText · " + context.getString(
            R.string.season_episode_format,
            item.info.season,
            item.info.episode
        )
    }?.uppercase()

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        cornerLabel = cornerLabel,
        progressFraction = (item as? ContinueWatchingItem.InProgress)?.progress?.progressPercentage,
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> {
                val ps = item.progress.season
                val pe = item.progress.episode
                if (ps != null && pe != null) {
                    context.getString(R.string.season_episode_format, ps, pe)
                } else {
                    item.progress.episodeTitle
                }
            }
            is ContinueWatchingItem.NextUp -> {
                val code = context.getString(
                    R.string.season_episode_format,
                    item.info.season,
                    item.info.episode
                )
                if (item.info.hasAired) {
                    code
                } else {
                    item.info.airDateLabel?.let { "$code • ${airsDateTemplate.format(it)}" } ?: "$code • $upcomingLabel"
                }
            }
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

/**
 * Hero settle delay for a row-band focus change at [nowMs]; [lastNavAtMs] is the previous change
 * (0 = none yet).
 */
internal fun heroSettleDelayMs(lastNavAtMs: Long, nowMs: Long): Long {
    val sinceLast = nowMs - lastNavAtMs
    return if (lastNavAtMs != 0L && sinceLast in 1 until RAPID_NAV_THRESHOLD_MS) {
        RAPID_NAV_SETTLE_MS
    } else {
        MODERN_HERO_FOCUS_DEBOUNCE_MS
    }
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int,
    showFullReleaseDate: Boolean = true,
    previousCachedItem: ModernCarouselItem? = null
): ModernCarouselItem = catalogCarouselItem(
    item = item,
    rowKey = row.key(),
    addonBaseUrl = row.addonBaseUrl,
    useLandscapePosters = useLandscapePosters,
    occurrence = occurrence,
    showFullReleaseDate = showFullReleaseDate,
    previousCachedItem = previousCachedItem
)

/** One catalog card for [item] in the row keyed [rowKey]; Discover's grid rows have no [CatalogRow]. */
internal fun catalogCarouselItem(
    item: MetaPreview,
    rowKey: String,
    addonBaseUrl: String,
    useLandscapePosters: Boolean,
    occurrence: Int,
    showFullReleaseDate: Boolean = true,
    previousCachedItem: ModernCarouselItem? = null
): ModernCarouselItem {
    // Carry forward the frozen URLs from the previous cache entry so that
    // external-meta enrichment never changes the image shown on landscape cards,
    // even after the composable remember-state is lost (e.g. navigation).
    val carriedBackdrop = previousCachedItem?.heroPreview?.frozenBackdropUrl
    val carriedLogo = previousCachedItem?.heroPreview?.frozenLogoUrl

    // Only true landscape art freezes (T31, P40): `backdropUrl` falls back to the portrait poster,
    // which would stay in the landscape slot after enrichment brings a real backdrop.
    val currentBackdrop = item.landscapeBackdropUrl
    val currentLogo = item.logo

    // First non-blank value wins and is never replaced.
    val frozenBackdrop = carriedBackdrop?.takeIf { it.isNotBlank() }
        ?: currentBackdrop
    val frozenLogo = carriedLogo?.takeIf { it.isNotBlank() }
        ?: currentLogo

    val heroPreview = HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        episodeLabel = null,
        isSeries = isSeriesType(item.apiType),
        yearText = extractYearText(item.type, item.releaseInfo, item.released, showFullReleaseDate),
        runtimeText = formatHeroRuntime(item.runtime),
        imdbText = formatRatingScore(item.imdbRating),
        tmdbText = formatRatingScore(item.tmdbRating),
        genres = item.genres.take(3).asStable(),
        apiType = item.apiType,
        seasonCount = item.seasonCount,
        status = item.status,
        ageRating = item.ageRating,
        directors = item.director.asStable(),
        writers = item.writer.asStable(),
        poster = item.poster,
        backdrop = item.backdropUrl,
        imageUrl = if (useLandscapePosters) {
            item.backdropUrl ?: item.poster
        } else {
            item.poster ?: item.backdropUrl
        },
        frozenBackdropUrl = frozenBackdrop,
        frozenLogoUrl = frozenLogo
    )

    return ModernCarouselItem(
        key = "catalog_${rowKey}_${item.id}_${occurrence}",
        title = item.name,
        subtitle = item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            item.backdropUrl ?: item.poster
        } else {
            item.poster ?: item.backdropUrl
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${rowKey}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = addonBaseUrl,
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}

internal fun buildCollectionFolderItem(
    collection: Collection,
    folder: CollectionFolder,
    occurrence: Int = 0
): ModernCarouselItem {
    val title = if (!folder.coverEmoji.isNullOrBlank()) {
        "${folder.coverEmoji}  ${folder.title}"
    } else {
        folder.title
    }
    // Cover image takes priority over emoji. Emoji is only used as fallback
    // when no cover image is available.
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // Don't use it as a static poster — it would still animate via Coil's GIF decoder.
    // A dead (blacklisted) folder cover degrades down the chain to the
    // collection backdrop instead of re-requesting on every carousel recycle.
    val coverUrl = firstNonBlank(folder.coverImageUrl)?.takeUnless { it in brokenImageUrls }
    val imageUrl = firstNonBlank(coverUrl, collection.backdropImageUrl)
    val heroBackdrop = firstNonBlank(folder.heroBackdropUrl, folder.coverImageUrl, collection.backdropImageUrl)

    return ModernCarouselItem(
        key = "collection_${collection.id}_${folder.id}_$occurrence",
        title = if (folder.hideTitle) "" else folder.title,
        subtitle = if (folder.hideTitle) null else collection.title,
        imageUrl = imageUrl,
        heroPreview = HeroPreview(
            title = if (folder.hideTitle) "" else title,
            logo = folder.titleLogoUrl,
            description = null,
            episodeLabel = null,
            yearText = null,
            imdbText = null,
            genres = emptyList<String>().asStable(),
            poster = imageUrl,
            backdrop = heroBackdrop,
            imageUrl = imageUrl
        ),
        payload = ModernPayload.CollectionFolder(
            focusKey = "collection_${collection.id}::${folder.id}",
            collectionId = collection.id,
            collectionTitle = collection.title,
            folderId = folder.id,
            focusGlowEnabled = collection.focusGlowEnabled,
            focusGifEnabled = folder.focusGifEnabled,
            focusGifUrl = folder.focusGifUrl,
            heroBackdropUrl = folder.heroBackdropUrl,
            heroVideoUrl = folder.heroVideoUrl,
            titleLogoUrl = folder.titleLogoUrl,
            coverEmoji = folder.coverEmoji,
            tileShape = folder.tileShape
        )
    )
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.season}_${item.info.episode}"
    }
}

/** Localized type label ("Movie"/"Series"), falling back to the capitalized api type. */
internal fun catalogTypeLabel(apiType: String, strTypeMovie: String, strTypeSeries: String): String {
    val fallback = apiType.replaceFirstChar { it.uppercase() }
    return when (apiType.lowercase()) {
        "movie" -> strTypeMovie.ifBlank { fallback }
        "series" -> strTypeSeries.ifBlank { fallback }
        else -> fallback
    }
}

/** Catalog row title: the capitalized name, plus " - <type>" when the type suffix is on. */
internal fun catalogRowTitle(
    catalogName: String,
    apiType: String,
    showCatalogTypeSuffix: Boolean,
    strTypeMovie: String = "",
    strTypeSeries: String = ""
): String {
    val title = catalogName.replaceFirstChar { it.uppercase() }
    if (!showCatalogTypeSuffix) return title
    return "$title - ${catalogTypeLabel(apiType, strTypeMovie, strTypeSeries)}"
}

/** The small addon name after a catalog row title, when [showAddonName] is on and there is one. */
internal fun catalogRowTitleSuffix(addonName: String, showAddonName: Boolean): String? =
    addonName.takeIf { showAddonName && it.isNotBlank() }

internal fun CatalogRow.key(): String {
    return stableKey()
}

internal fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

/** The watched tick's rule: series-type titles by the fully watched series set, the rest by the movie set. */
internal fun isWatchedTitle(id: String, type: String?, watchedMovieIds: Set<String>, watchedSeriesIds: Set<String>): Boolean =
    if (isSeriesType(type)) id in watchedSeriesIds else id in watchedMovieIds

internal fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

/** The hero copy of [this] once external-meta enrichment has arrived ([enriched] null: unchanged). */
internal fun HeroPreview.withEnrichment(enriched: MetaPreview?): HeroPreview {
    if (enriched == null) return this
    return copy(
        title = enriched.name,
        logo = enriched.logo,
        description = enriched.description,
        isSeries = isSeriesType(enriched.apiType),
        yearText = extractYearText(enriched.type, enriched.releaseInfo, enriched.released) ?: yearText,
        runtimeText = formatHeroRuntime(enriched.runtime) ?: runtimeText,
        imdbText = formatRatingScore(enriched.imdbRating),
        tmdbText = formatRatingScore(enriched.tmdbRating) ?: tmdbText,
        genres = enriched.genres.take(3).asStable(),
        apiType = enriched.apiType,
        seasonCount = enriched.seasonCount ?: seasonCount,
        status = enriched.status ?: status,
        ageRating = enriched.ageRating ?: ageRating,
        directors = enriched.director.takeIf { it.isNotEmpty() }?.asStable() ?: directors,
        writers = enriched.writer.takeIf { it.isNotEmpty() }?.asStable() ?: writers,
        poster = enriched.poster,
        backdrop = enriched.backdropUrl
    )
}

/** The title's own hero art: backdrop, then card image, then poster; placeholders skipped. */
internal fun heroBackdropOf(preview: HeroPreview?): String? = firstNonBlank(
    *listOf(preview?.backdrop, preview?.imageUrl, preview?.poster)
        .filterNot { it.isPlaceholder() }
        .toTypedArray()
)

/**
 * The Detail hand-off art for a clicked card (P32): that title's own hero art, enriched when the
 * enrichment is known; null when no card in [rows] opens [itemId]. A catalog card wins over a
 * Continue Watching card (that row leads Home; its art may be an episode thumbnail).
 */
internal fun clickedTitleBackdrop(
    rows: List<HeroCarouselRow>,
    itemId: String,
    enrichedPreviews: Map<String, MetaPreview>
): String? {
    fun firstCard(opens: (ModernPayload) -> Boolean) =
        rows.firstNotNullOfOrNull { row -> row.items.list.firstOrNull { opens(it.payload) } }
    val item = firstCard { (it as? ModernPayload.Catalog)?.itemId == itemId }
        ?: firstCard { (it as? ModernPayload.ContinueWatching)?.item?.contentId() == itemId }
        ?: return null
    return heroBackdropOf(item.heroPreview.withEnrichment(item.metaPreview?.id?.let(enrichedPreviews::get)))
}

/**
 * Each row's focused card on return to Home (P49), by the item key saved for every row; rows or
 * cards no longer present, and rows saved without a card (blank key), are left out (they start
 * at their first card).
 */
internal fun savedRowFocusIndices(
    rowByKey: Map<String, HeroCarouselRow>,
    savedItemKeyByRow: Map<String, String>
): Map<String, Int> = buildMap {
    savedItemKeyByRow.forEach { (rowKey, itemKey) ->
        if (itemKey.isBlank()) return@forEach
        val index = rowByKey[rowKey]?.items?.list?.indexOfFirst { it.key == itemKey } ?: -1
        if (index >= 0) put(rowKey, index)
    }
}

internal fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

internal fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

internal fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.seedSeason
    }
}

internal fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.seedEpisode
    }
}
