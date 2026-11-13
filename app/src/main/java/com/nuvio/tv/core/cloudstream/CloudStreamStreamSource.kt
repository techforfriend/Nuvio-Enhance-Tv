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
        title: String? = null,
        type: String? = null,
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
        val cleanTitle = title?.trim().takeIf { it.isNotEmpty() }
        // The request's type ("movie"/"series"/"channel"/"tv") as the search items
        // report it: "tv" requests are channels.
        val wantedType = when (type?.lowercase()) {
            "tv" -> "channel"
            else -> type?.lowercase()
        }

        return coroutineScope {
            plugins.map { plugin ->
                async {
                    runCatching {
                        val providerId = plugin.metadata.id.value
                        // Stage 1: the IMDb id, for the few providers that declare
                        // IMDb sync (supportedSyncNames + getLoadUrl). Most community
                        // providers declare neither, so this returns null for them.
                        val externalLoaded = CloudStreamRepository.loadByExternalId(providerId, imdbId)
                            .getOrNull()
                        // Stage 2: search by title, as Enhanced-2 does when the
                        // external-id path fails. Without this, a provider with no
                        // IMDb sync can never contribute links to a normal title.
                        val loaded = externalLoaded
                            ?.takeIf {
                                selectTargetData(it, requestedSeason, requestedEpisode) != null
                            }
                            ?: searchFallback(providerId, cleanTitle, wantedType)
                            ?: return@runCatching null
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

    /**
     * Search [providerId] for [title] and load the best match, or null when there is
     * no title, no search result, or no result matching the title and type. The
     * provider's own result order breaks ties; the year is not used yet (the stream
     * request carries no year), so a same-title remake can match the wrong year.
     */
    private suspend fun searchFallback(
        providerId: String,
        title: String?,
        type: String?,
    ): CloudStreamLoadItem? {
        val query = title?.trim().takeIf { it.isNotEmpty() } ?: return null
        val results = CloudStreamRepository.search(query, providerId)
            .firstOrNull()?.getOrNull().orEmpty()
        val match = bestSearchMatch(results, query, type) ?: return null
        return CloudStreamRepository.load(providerId, match.data).getOrNull()
    }

    private fun bestSearchMatch(
        items: List<CloudStreamSearchItem>,
        title: String,
        type: String?,
    ): CloudStreamSearchItem? {
        val wanted = normalizedTitle(title)
        if (wanted.isEmpty()) return null
        val typed = if (type.isNullOrBlank()) items
        else items.filter { it.type.tvTypeString == type }
        return typed.firstOrNull { normalizedTitle(it.name) == wanted }
            ?: typed.firstOrNull {
                val itemTitle = normalizedTitle(it.name)
                itemTitle.contains(wanted) || wanted.contains(itemTitle)
            }
    }

    private fun normalizedTitle(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

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
