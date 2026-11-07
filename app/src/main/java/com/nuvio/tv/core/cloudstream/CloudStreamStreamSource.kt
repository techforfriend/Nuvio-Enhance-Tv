package com.nuvio.tv.core.cloudstream

import android.util.Log
import com.nuvio.tv.domain.model.AddonStreams
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Resolves TV streams from installed CloudStream providers.
 *
 * Flow per provider: IMDb id → `loadByExternalId` → pick the episode matching the
 * requested season/episode (movies and live use the title itself) → `loadLinks` →
 * TV [com.nuvio.tv.domain.model.Stream] items. Providers run concurrently; one
 * provider failing never fails the others. The caller ([StreamRepositoryImpl]) applies
 * the per-source deadline.
 *
 * Home CS3 cards play directly instead: their video id is
 * `cs3:<pluginId>|<routeData>` and resolves to exactly one provider (see
 * [fetchStreamsForDirectRoute]).
 */
@Singleton
class CloudStreamStreamSource @Inject constructor() {
    private val tag = "CloudStreamStreams"

    /** IMDb id from a TV video id (`tt1234567`, series `tt1234567:1:5`), or null. */
    fun imdbIdFromVideoId(videoId: String): String? {
        val first = videoId.trim().substringBefore(':').trim()
        return first.takeIf { it.matches(IMDB_ID_REGEX) }
    }

    suspend fun fetchStreams(
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<AddonStreams> {
        // Direct CS3 play from Home: "cs3:<pluginId>|<routeData>". The route data is
        // the title (movies/live) or the picked episode (series); season/episode arrive
        // as explicit Stream-route args because the video id does not carry them.
        val trimmedVideoId = videoId.trim()
        if (trimmedVideoId.startsWith(CS3_VIDEO_ID_PREFIX)) {
            return fetchStreamsForDirectRoute(trimmedVideoId, season, episode)
        }
        val imdbId = imdbIdFromVideoId(videoId) ?: return emptyList()
        val parts = videoId.trim().split(":")
        val requestedSeason = season ?: parts.getOrNull(1)?.toIntOrNull()
        val requestedEpisode = episode ?: parts.getOrNull(2)?.toIntOrNull()
        val plugins = CloudStreamRepository.runnablePlugins()
        if (plugins.isEmpty()) return emptyList()

        return coroutineScope {
            plugins.map { plugin ->
                async {
                    runCatching {
                        val providerId = plugin.metadata.id.value
                        val loaded = CloudStreamRepository.loadByExternalId(providerId, imdbId)
                            .getOrNull() ?: return@runCatching null
                        val targetData = selectTargetData(loaded, requestedSeason, requestedEpisode)
                            ?: return@runCatching null
                        val links = CloudStreamRepository.loadLinks(providerId, targetData)
                            .getOrNull().orEmpty()
                        if (links.isEmpty()) return@runCatching null
                        val providerName = plugin.metadata.name
                        AddonStreams(
                            addonName = providerName,
                            addonLogo = plugin.metadata.iconUrl,
                            streams = links.map { it.toTvStream(providerName, plugin.metadata.iconUrl) },
                        )
                    }.onFailure { error ->
                        Log.w(tag, "CloudStream streams failed plugin=${plugin.metadata.name}: ${error.message}")
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }
        }
    }

    /** One provider, one title: the Home section's direct play path. */
    private suspend fun fetchStreamsForDirectRoute(
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<AddonStreams> {
        val rest = videoId.removePrefix(CS3_VIDEO_ID_PREFIX)
        val providerId = rest.substringBefore('|').trim()
        val data = rest.substringAfter('|', "").trim()
        if (providerId.isBlank() || data.isBlank()) return emptyList()
        val plugin = CloudStreamRepository.runnablePlugins()
            .firstOrNull { it.metadata.id.value == providerId } ?: return emptyList()
        val loaded = runCatching {
            CloudStreamRepository.load(providerId, data).getOrNull()
        }.getOrNull() ?: return emptyList()
        val targetData = selectTargetData(loaded, season, episode) ?: return emptyList()
        val links = CloudStreamRepository.loadLinks(providerId, targetData)
            .getOrNull().orEmpty()
        if (links.isEmpty()) return emptyList()
        val providerName = plugin.metadata.name
        return listOf(
            AddonStreams(
                addonName = providerName,
                addonLogo = plugin.metadata.iconUrl,
                streams = links.map { it.toTvStream(providerName, plugin.metadata.iconUrl) },
            )
        )
    }

    /** Prefix for video ids that name a CS3 title directly instead of an IMDb id. */
    fun cs3VideoId(providerId: String, data: String): String =
        "$CS3_VIDEO_ID_PREFIX$providerId|$data"

    /**
     * For series, the episode matching [season]/[episode]; without a requested episode
     * the first episode. Movies, live and torrents resolve to the title itself.
     */
    private fun selectTargetData(
        loaded: CloudStreamLoadItem,
        season: Int?,
        episode: Int?,
    ): String? {
        val episodes = sortCloudStreamEpisodes(loaded.episodes)
        if (episodes.isEmpty()) return loaded.data
        if (season == null && episode == null) return episodes.firstOrNull()?.data
        return episodes.firstOrNull { item ->
            (season == null || item.season == season) &&
                (episode == null || item.episode == episode)
        }?.data
    }

    companion object {
        private val IMDB_ID_REGEX = Regex("^tt\\d{5,12}$", RegexOption.IGNORE_CASE)
        const val CS3_VIDEO_ID_PREFIX = "cs3:"
    }
}
