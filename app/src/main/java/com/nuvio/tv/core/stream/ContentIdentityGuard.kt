package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.Stream
import java.text.Normalizer
import kotlin.math.abs

/**
 * What auto-play was asked to play (Spec 65 P3). A null field is unknown and never rejects.
 * [film] is the caller's content type (a movie), never inferred from missing fields: it enables
 * the year check here and the film size floors in [com.nuvio.tv.core.player.StreamQualityRank].
 */
data class ContentIdentity(
    val title: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val film: Boolean
) {
    companion object {
        private val YEAR = Regex("(19|20)\\d{2}")

        /** Anime-database video ids, which number episodes per database entry (Spec 84 A9). */
        private val PER_ENTRY_EPISODE_PREFIXES = listOf("kitsu:", "mal:", "anilist:", "anidb:")

        /** The content type every auto-play site reads film from ("movie", Stremio's type). */
        fun isFilmType(type: String?): Boolean = type.equals("movie", ignoreCase = true)

        /**
         * The identity of a play request, built the same way at every auto-play site (Spec 65 S3).
         * [year] is the free-text year ("2021", "2019-2023"): its first year counts. A Kitsu,
         * MyAnimeList, AniList or AniDB id numbers episodes per database entry, not as releases
         * name them, so its season and episode are left unknown (Spec 65 D7, Spec 84 A9).
         */
        fun of(
            type: String?,
            title: String?,
            year: String?,
            season: Int?,
            episode: Int?,
            videoId: String?
        ): ContentIdentity {
            val perEntry = videoId != null &&
                PER_ENTRY_EPISODE_PREFIXES.any { videoId.startsWith(it, ignoreCase = true) }
            return ContentIdentity(
                title = title?.takeIf { it.isNotBlank() },
                year = year?.let { YEAR.find(it)?.value?.toInt() },
                season = season.takeUnless { perEntry },
                episode = episode.takeUnless { perEntry },
                film = isFilmType(type)
            )
        }
    }
}

/**
 * Rejects auto-play candidates only on a hard mismatch in their release name (Spec 65 P3, D6;
 * after Zokaper/NuvioZDesktop `ContentIdentityGuard.kt` `4e774b58a`, GPL-3.0). A missing or
 * unparseable name passes (Spec 65 D2), and manual picks never reach it.
 *
 * - Episode: the name's SxxEyy (ranges E01E02E03, E01-E03, E01-03 and S01E01-S03E13 packs count)
 *   names another season or episode.
 * - Film year ([ContentIdentity.film] only): the year straight after the requested title is more
 *   than one off ("Dune.1984" for Dune 2021). Anything else passes: a year after more words may
 *   be a re-release's ("Blade.Runner.The.Final.Cut.2007"), and episodes may carry the show's year.
 *
 * Not checked (D6): another title sharing the requested one's start (Daredevil vs
 * Daredevil.Born.Again); text cannot tell a spin-off from a season subtitle, so Spec 35's
 * after-start runtime check covers it.
 */
object ContentIdentityGuard {

    enum class Rejection { WRONG_SEASON, WRONG_EPISODE, WRONG_YEAR }

    /** A release is often tagged with its publication year, one off from the title's. */
    const val YEAR_TOLERANCE = 1

    /**
     * Groups: 1 season, 2 first episode, 3+4 a cross-season pack's end (S01E01-S03E13), 5 further
     * episodes (the last number ends the range; "+", "." and spaced dashes chain). A range end must
     * not run into letters other than a chained "e" or a "v2" revision, so "-720p", "-4k" and
     * "-10bit" are not range ends.
     */
    private val SEASON_EPISODE = Regex(
        "(?<![a-z0-9])s(\\d{1,4})[ ._-]?e(\\d{1,3})" +
            "(?:-s(\\d{1,4})e(\\d{1,3})(?![0-9a-uw-z])|((?:(?:[ ._+-]*e|-)\\d{1,3}(?![0-9a-df-uw-z]))*))(?![0-9])"
    )
    private val DIGITS = Regex("\\d+")
    private val YEAR_TOKEN = Regex("(19|20)\\d{2}")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val APOSTROPHES = Regex("['\u2019`]")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * The release name the guard reads: the file or torrent name, never formatter text (an
     * addon's display title/description is not a release name).
     */
    fun releaseNameOf(stream: Stream): String? {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        return listOf(
            stream.behaviorHints?.filename,
            resolve?.filename,
            raw?.filename,
            resolve?.torrentName,
            raw?.torrentName
        ).firstOrNull { !it.isNullOrBlank() }
    }

    /**
     * Each rejected candidate of [streams] with its reason; candidates absent from the map pass.
     * No "every candidate rejected" escape (Spec 65 D7): whether a list is complete is not
     * knowable at the select (a prefetch entry cut by its cap ends the flow early), and on a
     * partial list the escape plays the first addon's wrong episode. All rejected = the picker.
     */
    fun rejections(streams: List<Stream>, identity: ContentIdentity): Map<Stream, Rejection> {
        val titleTokens = identity.title?.let { tokens(normalize(it)) }.orEmpty()
        val rejected = LinkedHashMap<Stream, Rejection>()
        for (stream in streams) {
            evaluate(releaseNameOf(stream), identity, titleTokens)?.let { rejected[stream] = it }
        }
        return rejected
    }

    internal fun evaluate(
        releaseName: String?,
        identity: ContentIdentity,
        titleTokens: List<String> = identity.title?.let { tokens(normalize(it)) }.orEmpty()
    ): Rejection? {
        val name = releaseName?.takeIf { it.isNotBlank() }?.let(::normalize) ?: return null
        val season = identity.season
        val episode = identity.episode
        if (season != null && episode != null) return episodeRejection(name, season, episode)
        if (!identity.film) return null
        val requestedYear = identity.year ?: return null
        val nameTokens = tokens(name)
        if (titleTokens.isEmpty() || nameTokens.size <= titleTokens.size ||
            nameTokens.subList(0, titleTokens.size) != titleTokens
        ) return null
        val next = nameTokens[titleTokens.size]
        if (!YEAR_TOKEN.matches(next)) return null
        return if (abs(next.toInt() - requestedYear) > YEAR_TOLERANCE) Rejection.WRONG_YEAR else null
    }

    private fun episodeRejection(name: String, season: Int, episode: Int): Rejection? {
        val match = SEASON_EPISODE.find(name) ?: return null
        val first = match.groupValues[1].toInt()
        val lastSeason = match.groupValues[3].toIntOrNull()
        if (lastSeason != null && lastSeason != first) {
            // A cross-season pack: the episode lies inside it when its season does.
            return if (season in first..lastSeason) null else Rejection.WRONG_SEASON
        }
        if (first != season) return Rejection.WRONG_SEASON
        val firstEpisode = match.groupValues[2].toInt()
        val lastEpisode = (match.groupValues[4].toIntOrNull()
            ?: DIGITS.findAll(match.groupValues[5]).lastOrNull()?.value?.toInt())
            ?.takeIf { it >= firstEpisode } ?: firstEpisode
        return if (episode in firstEpisode..lastEpisode) null else Rejection.WRONG_EPISODE
    }

    /** Lowercase, accents folded ("Pokémon" = "pokemon"), apostrophes dropped ("Marvel's" = "marvels"). */
    private fun normalize(text: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .replace(APOSTROPHES, "")
            .lowercase()

    private fun tokens(normalized: String): List<String> = normalized.split(NON_ALNUM).filter { it.isNotEmpty() }
}
