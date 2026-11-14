package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.util.parseRuntimeMinutes
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaLinkDto
import com.nuvio.tv.data.remote.dto.VideoDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video

fun MetaDto.toDomain(episodeLabel: String = "Episode"): Meta {
    val directorMembers = mapPeople(appExtras?.directors, roleFallback = "Director", forceRole = true)
    val writerMembers = mapPeople(appExtras?.writers, roleFallback = "Writer", forceRole = true)
    val castMembers = mapPeople(appExtras?.cast)
    val directors = coerceStringList(director).ifEmpty { directorMembers.map { it.name } }
    val writersList = coerceStringList(writer).ifEmpty { coerceStringList(writers) }
        .ifEmpty { writerMembers.map { it.name } }
    val castList = coerceStringList(cast).ifEmpty { castMembers.map { it.name } }
    val trailersList = mapTrailers(trailers, trailerStreams)
    val certification = appExtras?.certification?.trim()?.takeIf { it.isNotBlank() }
    val releaseDatesList = mapReleaseDates(appExtras?.releaseDates)

    return Meta(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        imdbId = imdbId,
        slug = slug,
        released = released,
        landscapePoster = landscapePoster,
        description = description,
        releaseInfo = releaseInfo,
        status = status?.trim()?.takeIf { it.isNotBlank() },
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: emptyList(),
        runtime = runtime,
        director = directors,
        writer = writersList,
        cast = castList,
        castMembers = directorMembers + writerMembers + castMembers,
        videos = videos?.mapNotNull { it.toDomainOrNull(episodeLabel) } ?: emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = certification,
        ageRatingDescriptors = certificationDescriptors(certification, releaseDatesList),
        country = country,
        awards = awards,
        language = language,
        links = links?.mapNotNull { it.toDomain() } ?: emptyList(),
        trailerYtIds = trailersList.mapNotNull { it.ytId }.distinct(),
        rawPosterUrl = rawPosterUrl,
        behaviorHints = mapBehaviorHints(behaviorHints),
        trailers = trailersList,
        releaseDates = releaseDatesList,
        hasPoster = hasPoster,
        hasBackground = hasBackground,
        hasLandscapePoster = hasLandscapePoster,
        hasLogo = hasLogo,
        hasLinks = hasLinks,
        hasVideos = hasVideos
    )
}

/** Null for an entry without an id (malformed addon element; dropped, not fatal). */
fun VideoDto.toDomainOrNull(episodeLabel: String = "Episode"): Video? {
    val resolvedId = id ?: return null
    return Video(
        id = resolvedId,
        title = name ?: title ?: "$episodeLabel ${episode ?: number ?: 0}",
        released = released,
        thumbnail = thumbnail,
        streams = streams?.toListedStreams(addonName = "Embedded Streams", addonLogo = null) ?: emptyList(),
        season = season,
        episode = episode ?: number,
        overview = overview ?: description,
        runtime = parseRuntimeMinutes(runtime),
        rating = rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 },
        available = available
    )
}

/** Null when the link lacks a url, name or category (malformed addon element). */
fun MetaLinkDto.toDomain(): MetaLink? {
    return MetaLink(
        name = name ?: return null,
        category = category ?: return null,
        url = url ?: return null
    )
}
