package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.WatchProgress

// Continue Watching artwork chain + image cache keys (Home's Marquee cards and their
// prefetchers must agree), the process-wide dead-URL blacklist (CW art + folder covers), and
// the long-press options dialog with its Mark as watched gate and completed-progress builder
// (Spec 62 H4). The CW card itself lives with Home's row band
// (MarqueeHomeRows) since wave U slice U1c.

/**
 * URLs that failed to load — skip them immediately on next recomposition.
 * Entries expire after [ttlMs] so a transient CDN failure cannot keep a poster
 * blacklisted for the rest of the process, and the map is purged of expired
 * entries once it grows past [maxEntries].
 */
internal class ExpiringUrlBlacklist(
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val addedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    operator fun contains(url: String?): Boolean {
        val at = url?.let { addedAtMs[it] } ?: return false
        if (nowMs() - at > ttlMs) {
            addedAtMs.remove(url)
            return false
        }
        return true
    }

    fun add(url: String) {
        val now = nowMs()
        addedAtMs[url] = now
        if (addedAtMs.size > maxEntries) {
            val cutoff = now - ttlMs
            addedAtMs.values.removeIf { it < cutoff }
        }
    }

    /** Test affordance: resets the blacklist between cases. */
    fun clear() = addedAtMs.clear()
}

// Shared process-wide dead-URL fact, consumed by TWO families: the CW artwork
// chain (continueWatchingImageModel) and every collection-folder cover surface
// (collectionFolderCardImageUrl / FolderCoverFallback). Clearing or re-tuning
// it affects both.
internal val brokenImageUrls = ExpiringUrlBlacklist(
    ttlMs = java.util.concurrent.TimeUnit.HOURS.toMillis(1),
    maxEntries = 512
)

// Poster cards always show poster art, so the episode thumbnail setting does not apply to them.
internal fun continueWatchingUsesEpisodeThumbnails(
    cardStyle: ContinueWatchingCardStyle,
    useEpisodeThumbnails: Boolean
): Boolean = useEpisodeThumbnails && cardStyle != ContinueWatchingCardStyle.POSTER

// Selects the primary Continue Watching image URL, shared by the card and the home-row prefetch so both request the same model.
internal fun continueWatchingImageModel(
    item: ContinueWatchingItem,
    useEpisodeThumbnails: Boolean,
    preferPosterArtwork: Boolean = false
): String? {
    // Poster art is already 2:3 so it wins here, and only an opted-in episode thumbnail outranks it.
    if (preferPosterArtwork) {
        val posterProgress = (item as? ContinueWatchingItem.InProgress)?.progress
        val posterNextUp = (item as? ContinueWatchingItem.NextUp)?.info
        val poster = posterNextUp?.poster ?: posterProgress?.poster
        val backdrop = posterNextUp?.backdrop ?: posterProgress?.backdrop
        val thumbnail = posterNextUp?.thumbnail
            ?: (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail
        // Movies carry no episode thumbnail, so they keep their poster without needing a content type check.
        // A thumbnail that is switched off leaves the chain entirely, because Trakt rows hydrate their artwork late and a demoted thumbnail would show until it lands.
        return if (useEpisodeThumbnails) {
            firstNonBroken(thumbnail, poster, backdrop)
        } else {
            firstNonBroken(poster, backdrop)
        }
    }
    val progress = (item as? ContinueWatchingItem.InProgress)?.progress
    val nextUp = (item as? ContinueWatchingItem.NextUp)?.info
    return when {
        nextUp != null && !nextUp.hasAired ->
            firstNonBroken(
                nextUp.backdrop,
                nextUp.poster,
                nextUp.thumbnail.takeIf { useEpisodeThumbnails }
            )
        nextUp != null && useEpisodeThumbnails ->
            firstNonBroken(nextUp.thumbnail, nextUp.backdrop, nextUp.poster)
        nextUp != null ->
            firstNonBroken(nextUp.backdrop, nextUp.poster)
        useEpisodeThumbnails -> firstNonBroken(
            (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail,
            progress?.backdrop,
            progress?.poster
        )
        else -> firstNonBroken(
            progress?.backdrop,
            progress?.poster
        )
    }
}

// Whether the unwatched-episode spoiler blur applies to this item.
internal fun continueWatchingShouldBlur(
    item: ContinueWatchingItem,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    preferPosterArtwork: Boolean = false
): Boolean {
    if (!blurUnwatchedEpisodes || !useEpisodeThumbnails) return false
    if (item is ContinueWatchingItem.InProgress && item.progress.isCompleted()) return false
    val thumbnail = when (item) {
        is ContinueWatchingItem.InProgress -> item.episodeThumbnail
        is ContinueWatchingItem.NextUp -> item.info.thumbnail
    }?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val resolved = continueWatchingImageModel(item, useEpisodeThumbnails, preferPosterArtwork)
    return resolved?.trim() == thumbnail
}

// Builds the Coil memory-cache key that the card and prefetch must share, including the blur suffix, so the prefetch is not wasted.
internal fun continueWatchingImageCacheKey(
    model: String?,
    widthPx: Int,
    heightPx: Int,
    shouldBlur: Boolean
): String = "${model}_${widthPx}x${heightPx}_blur${shouldBlur}"

// Single home for the catalog-poster cache-key recipe shared by Home's Marquee card and its
// two prefetchers: they must build byte-identical keys or every prefetched poster is orphaned
// under a key nothing reads.
internal fun catalogPosterImageCacheKey(
    url: String?,
    widthPx: Int,
    heightPx: Int
): String = "${url}_${widthPx}x${heightPx}"

/** Spec 62 H4: an unaired Next Up episode stays in the row but cannot be marked until it airs. */
internal fun ContinueWatchingItem.canMarkWatched(): Boolean = when (this) {
    is ContinueWatchingItem.InProgress -> true
    is ContinueWatchingItem.NextUp -> info.hasAired
}

/**
 * Spec 62 H4: the completed entry "Mark as watched" writes, in the shape the details screen
 * writes (position = duration, 100 %), so tracking sync takes the same path.
 */
internal fun ContinueWatchingItem.toCompletedProgress(
    nowMs: Long = System.currentTimeMillis()
): WatchProgress = when (this) {
    is ContinueWatchingItem.InProgress -> {
        val duration = progress.duration.coerceAtLeast(1L)
        WatchProgress(
            contentId = progress.contentId,
            contentType = progress.contentType,
            name = progress.name,
            poster = progress.poster,
            backdrop = progress.backdrop,
            logo = progress.logo,
            videoId = progress.videoId,
            season = progress.season,
            episode = progress.episode,
            episodeTitle = progress.episodeTitle,
            position = duration,
            duration = duration,
            lastWatched = nowMs,
            progressPercent = 100f
        )
    }
    is ContinueWatchingItem.NextUp -> WatchProgress(
        contentId = info.contentId,
        contentType = info.contentType,
        name = info.name,
        poster = info.poster,
        backdrop = info.thumbnail ?: info.backdrop,
        logo = info.logo,
        videoId = info.videoId,
        season = info.season,
        episode = info.episode,
        episodeTitle = info.episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = nowMs,
        progressPercent = 100f
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    showPlayManually: Boolean = false,
    onPlayManually: () -> Unit = {},
    onMarkWatched: () -> Unit = {}
) {
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }

    val detailsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        detailsFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle)
    ) {
        PanelActionRow(
            label = stringResource(R.string.cw_action_go_to_details),
            onClick = onDetails,
            focusRequester = detailsFocusRequester
        )

        if (showPlayManually) {
            PanelActionRow(
                label = stringResource(R.string.play_manually),
                onClick = onPlayManually
            )
        }

        if (item is ContinueWatchingItem.InProgress) {
            PanelActionRow(
                label = stringResource(R.string.cw_action_start_from_beginning),
                onClick = onStartFromBeginning
            )
        }

        // Spec 62 H4 (D4): above Remove, as upstream PR #4440 orders it.
        if (item.canMarkWatched()) {
            PanelActionRow(
                label = stringResource(R.string.hero_mark_watched),
                onClick = onMarkWatched
            )
        }

        PanelActionRow(
            label = stringResource(R.string.cw_action_remove),
            onClick = onRemove
        )
    }
}

private fun firstNonBroken(vararg candidates: String?): String? =
    candidates.firstOrNull { !it.isNullOrBlank() && it !in brokenImageUrls }?.trim()
