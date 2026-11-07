package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import com.nuvio.tv.ui.screens.cloudstream.CloudStreamHomeSection
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.asStable

/** Row keys for the Home CloudStream section (provider's main-page sections). */
internal fun cloudStreamHomeRowKey(providerId: String, sectionIndex: Int): String =
    "cs3_home_${providerId}_$sectionIndex"

/** "S1 E3 — Title" (parts omitted when the provider does not supply them). */
internal fun cloudStreamEpisodeLabel(episode: com.nuvio.tv.core.cloudstream.CloudStreamEpisode): String =
    buildString {
        if (episode.season != null) append("S${episode.season} ")
        if (episode.episode != null) append("E${episode.episode}")
        if (episode.name.isNotBlank()) {
            if (isNotEmpty()) append(" — ")
            append(episode.name)
        }
        if (isEmpty()) append("Episode")
    }

/**
 * Maps the Home ViewModel's provider sections onto the rows the Marquee list
 * renders, so CS3 posters look and focus like any other catalog row. Cards carry
 * a `cs3:` item id; [MarqueeHomeContent] intercepts those clicks instead of
 * opening Detail. The first row carries the provider selector pill.
 */
internal fun cloudStreamHomeRows(
    sections: List<CloudStreamHomeSection>,
    providerId: String,
    providerName: String,
    baseRowIndex: Int,
    onProviderClick: () -> Unit,
): StableList<HeroCarouselRow> {
    if (sections.isEmpty()) return StableList()
    val rows = sections.mapIndexed { index, section ->
        val items = section.items.map { item ->
            val cardId = "${CloudStreamStreamSource.CS3_VIDEO_ID_PREFIX}${item.providerId}|${item.data}"
            val contentType = item.type.tvTypeString
            ModernCarouselItem(
                key = cardId,
                title = item.name,
                subtitle = item.year?.toString(),
                imageUrl = item.posterUrl,
                heroPreview = HeroPreview(
                    title = item.name,
                    logo = null,
                    description = item.description,
                    episodeLabel = null,
                    isSeries = contentType == "series",
                    yearText = item.year?.toString(),
                    imdbText = null,
                    genres = StableList(),
                    poster = item.posterUrl,
                    backdrop = item.backgroundUrl ?: item.posterUrl,
                    imageUrl = item.posterUrl,
                ),
                payload = ModernPayload.Catalog(
                    focusKey = cardId,
                    itemId = cardId,
                    itemType = contentType,
                    addonBaseUrl = "",
                    trailerTitle = item.name,
                    trailerReleaseInfo = item.year?.toString(),
                    trailerApiType = "",
                ),
                metaPreview = null,
                cornerLabel = null,
            )
        }
        HeroCarouselRow(
            key = cloudStreamHomeRowKey(providerId, index),
            title = "CloudStream • ${section.title}",
            titleSuffix = providerName,
            globalRowIndex = baseRowIndex + index,
            items = items.asStable(),
            headerAction = if (index == 0) {
                MarqueeRowHeaderAction(label = providerName, onClick = onProviderClick)
            } else {
                null
            },
        )
    }
    return rows.asStable()
}
