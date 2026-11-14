package com.nuvio.tv.core.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.data.mapper.toStreamSubtitles
import com.nuvio.tv.data.remote.dto.SubtitleDto
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal data class PlayerNavigationArgs(
    val streamUrl: String,
    val title: String,
    val streamName: String?,
    val year: String?,
    val headersJson: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val initialSeason: Int?,
    val initialEpisode: Int?,
    val initialEpisodeTitle: String?,
    val bingeGroup: String?,
    val filename: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val runtimeMinutes: Int?,
    val startFromBeginning: Boolean,
    val addonName: String?,
    val streamDescription: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val sourcesJson: String?,
    val contentLanguage: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?,
    val launchStartedAtMs: Long?,
    /** Spec 75: the Stream screen's scrape time for this launch (the Finding sources step), null when none. */
    val sourcesFoundMs: Long? = null,
    /** The playing stream's own subtitles (Stremio `stream.subtitles`), labelled with [addonName]. */
    val streamSubtitles: List<Subtitle> = emptyList(),
    /** Spec 80 D20: a changelog release video (Screen.Player.createRoute's releaseVideo). */
    val releaseVideo: Boolean = false
) {
    val torrentTrackers: List<String>
        get() {
            val json = sourcesJson ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length())
                    .mapNotNull { arr.optString(it) }
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
            } catch (_: Exception) {
                emptyList()
            }
        }

    companion object {
        fun from(savedStateHandle: SavedStateHandle): PlayerNavigationArgs {
            // Raw read: Screen.Player.createRoute encodes each arg once and NavController
            // decodes it once. A second URLDecoder pass turned '+' into a space and
            // corrupted %-escapes (titles like "Disney+", header values, poster and
            // sources urls).
            fun argOrNull(key: String): String? =
                savedStateHandle.get<String>(key)?.takeIf { it.isNotEmpty() }

            val addonName = argOrNull("addonName")
            val addonLogo = argOrNull("addonLogo")
            return PlayerNavigationArgs(
                streamUrl = savedStateHandle.get<String>("streamUrl") ?: "",
                title = argOrNull("title") ?: "",
                streamName = argOrNull("streamName"),
                year = argOrNull("year"),
                headersJson = argOrNull("headers"),
                contentId = argOrNull("contentId"),
                contentType = argOrNull("contentType"),
                contentName = argOrNull("contentName"),
                poster = argOrNull("poster"),
                backdrop = argOrNull("backdrop"),
                logo = argOrNull("logo"),
                videoId = argOrNull("videoId"),
                initialSeason = savedStateHandle.get<String>("season")?.toIntOrNull(),
                initialEpisode = savedStateHandle.get<String>("episode")?.toIntOrNull(),
                initialEpisodeTitle = argOrNull("episodeTitle"),
                bingeGroup = argOrNull("bingeGroup"),
                filename = argOrNull("filename"),
                videoHash = argOrNull("videoHash"),
                videoSize = savedStateHandle.get<String>("videoSize")?.toLongOrNull(),
                runtimeMinutes = savedStateHandle.get<String>("runtimeMinutes")?.toIntOrNull(),
                startFromBeginning = savedStateHandle.get<String>("startFromBeginning")?.toBooleanStrictOrNull() == true,
                addonName = addonName,
                streamDescription = argOrNull("streamDescription"),
                infoHash = argOrNull("infoHash"),
                fileIdx = savedStateHandle.get<String>("fileIdx")?.toIntOrNull(),
                sourcesJson = argOrNull("sources"),
                contentLanguage = argOrNull("contentLanguage"),
                rememberedAudioLanguage = argOrNull("rememberedAudioLanguage"),
                rememberedAudioName = argOrNull("rememberedAudioName"),
                launchStartedAtMs = savedStateHandle.get<String>("launchStartedAtMs")?.toLongOrNull(),
                sourcesFoundMs = savedStateHandle.get<String>("sourcesFoundMs")?.toLongOrNull()?.takeIf { it >= 0L },
                streamSubtitles = decodeStreamSubtitlesArg(
                    json = savedStateHandle.get<String>("subtitles"),
                    addonName = addonName,
                    addonLogo = addonLogo
                ),
                releaseVideo = savedStateHandle.get<String>("releaseVideo")?.toBooleanStrictOrNull() == true
            )
        }
    }
}

private val subtitleListSerializer = ListSerializer(SubtitleDto.serializer())

/**
 * Route codec for [PlayerNavigationArgs.streamSubtitles]. Uses the Stremio wire
 * shape (SubtitleDto) so decoding re-runs the mapper's http(s)/size filtering;
 * addon name/logo ride their own route args.
 */
internal fun encodeStreamSubtitlesArg(subtitles: List<Subtitle>): String =
    Json.encodeToString(subtitleListSerializer, subtitles.map {
        SubtitleDto(id = it.id, url = it.url, lang = it.lang, movieReleaseName = it.releaseName, releaseGroup = it.releaseGroup)
    })

internal fun decodeStreamSubtitlesArg(json: String?, addonName: String?, addonLogo: String?): List<Subtitle> {
    if (json.isNullOrBlank()) return emptyList()
    val dtos = try {
        Json.decodeFromString(subtitleListSerializer, json)
    } catch (_: SerializationException) {
        return emptyList()
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }
    return dtos.toStreamSubtitles(addonName.orEmpty(), addonLogo)
}
