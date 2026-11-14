package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource

/**
 * Types to try, in preference order, when routing a stream or subtitle request
 * to an addon. Some anime metadata addons type items `anime.movie` /
 * `anime.series`, which stream and subtitle addons declare as `movie` /
 * `series` (or `anime`); an exact match on the raw type always wins, and the
 * matched candidate is also the type sent in the request URL.
 *
 * A plain `anime` item (AIOMetadata) also tries `series` for an episode [videoId]
 * and `movie` for any other, as AIOStreams declares neither `anime` nor a dotted
 * type (Spec 84 A3; NuvioMedia/NuvioTV#2983). With no [videoId] it stays alone.
 */
internal fun addonRequestTypeCandidates(type: String, videoId: String? = null): List<String> =
    when (type.trim().lowercase()) {
        "anime.movie" -> listOf(type, "movie", "anime")
        "anime.series" -> listOf(type, "series", "anime")
        "anime" -> listOfNotNull(type, videoId?.let { if (addonMetaIdOf(it) != it) "series" else "movie" })
        else -> listOf(type)
    }

/**
 * The meta id a video id belongs to: trailing numeric segments (season, episode)
 * dropped, never below `prefix:id` for a prefixed id or the bare id for an IMDb or
 * numeric one. `tt1234567:1:5` -> `tt1234567`, `mal:63375:1:5` -> `mal:63375`,
 * `kitsu:12345:2` -> `kitsu:12345`; a meta id comes back unchanged.
 */
internal fun addonMetaIdOf(videoId: String): String {
    val parts = videoId.split(":")
    if (parts.size <= 1) return videoId
    val trailingNumericCount = parts.reversed().takeWhile { it.toIntOrNull() != null }.size
    val firstSegment = parts.first()
    val minSegments = if (firstSegment.startsWith("tt") || firstSegment.toIntOrNull() != null) 1 else 2
    val segmentsToDrop = trailingNumericCount.coerceAtMost((parts.size - minSegments).coerceAtLeast(0))
    return if (segmentsToDrop > 0) parts.dropLast(segmentsToDrop).joinToString(":") else videoId
}

/**
 * The type to request streams with from this addon for the given type and
 * video id, or null when it has no matching stream resource. Tries the
 * [addonRequestTypeCandidates] in order (exact type first). Respects the
 * resource-level idPrefixes declared in the addon manifest, falling back to
 * the top-level addon idPrefixes if the resource doesn't declare its own.
 * One home for stream-addon eligibility: StreamRepositoryImpl routes requests
 * with it and the stream/player source chips use it, so a chip exists exactly
 * for the addons that are queried.
 */
internal fun Addon.streamRequestType(type: String, videoId: String): String? =
    addonRequestTypeCandidates(type, videoId).firstOrNull { candidate ->
        resources.any { resource ->
            resource.name == "stream" &&
                (resource.types.isEmpty() || resource.types.contains(candidate)) &&
                run {
                    val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                        ?: idPrefixes.takeIf { it.isNotEmpty() }
                    prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
                }
        }
    }

/** A meta resource serves [type], whatever its id prefixes. */
internal fun Addon.supportsMetaType(type: String): Boolean {
    val target = type.trim()
    if (target.isBlank()) return false
    return resources.any { resource -> resource.name == "meta" && resource.supportsType(target) }
}

/**
 * A meta resource serves [type] and its id prefixes accept [id]. One home for meta
 * eligibility: the meta lookup's candidates and the stream fan-out's inline-meta
 * fallback (Spec 84 A8) both ask it.
 */
internal fun Addon.servesMeta(type: String, id: String): Boolean {
    val target = type.trim()
    if (target.isBlank()) return false
    return resources.any { resource ->
        resource.name == "meta" && resource.supportsType(target) && acceptsMetaId(resource, id)
    }
}

/** The resource's own idPrefixes, else the addon's; none declared accepts every id. */
private fun Addon.acceptsMetaId(resource: AddonResource, id: String): Boolean {
    val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() } ?: idPrefixes
    return prefixes.isEmpty() || prefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
}

/** No declared types serves every type. */
private fun AddonResource.supportsType(type: String): Boolean {
    if (types.isEmpty()) return true
    return types.any { it.equals(type, ignoreCase = true) }
}
