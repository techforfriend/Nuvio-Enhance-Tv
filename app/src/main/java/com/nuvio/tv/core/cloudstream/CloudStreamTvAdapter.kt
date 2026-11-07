package com.nuvio.tv.core.cloudstream

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Subtitle

/**
 * Maps CloudStream playback results onto the TV app's stream model.
 *
 * Mirrors Enhanced-2's `CloudStreamNuvioAdapter.toStreamItem`, retargeted at TV's
 * [Stream]: the playable URL, quality label, request headers (as proxy headers, so
 * the player sends them) and per-stream subtitles. Magnet/torrent URLs pass through
 * untouched — [Stream] already knows how to surface them as torrent sources.
 */
fun CloudStreamPlaybackSource.toTvStream(providerName: String, providerLogo: String?): Stream {
    val parsedUrl = parseCloudStreamPlaybackUrl(url)
    val requestHeaders = buildMap {
        putAll(parsedUrl.headers)
        putAll(headers)
        referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
    }
    val qualityLabel = quality?.cloudStreamQualityLabel()
    return Stream(
        name = listOfNotNull(name.takeIf { it.isNotBlank() }, qualityLabel).joinToString(" · "),
        title = name,
        description = null,
        url = parsedUrl.url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = requestHeaders.isNotEmpty(),
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = requestHeaders.takeIf { it.isNotEmpty() }?.let { ProxyHeaders(request = it, response = null) },
        ),
        addonName = providerName,
        addonLogo = providerLogo,
        quality = qualityLabel,
        qualityValue = quality ?: -1,
        subtitles = subtitles.map { subtitle ->
            Subtitle(
                id = "cloudstream:${subtitle.url.hashCode()}:${subtitle.language}",
                url = subtitle.url,
                lang = subtitle.language,
                addonName = providerName,
                addonLogo = providerLogo,
                headers = subtitle.headers.takeIf { it.isNotEmpty() },
            )
        },
    )
}

private fun Int.cloudStreamQualityLabel(): String? =
    takeIf { it in knownCloudStreamQualities || (it in 144..4320 && it % 10 == 0) }?.let { "${it}p" }

private val knownCloudStreamQualities = setOf(144, 240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

private data class ParsedCloudStreamPlaybackUrl(
    val url: String,
    val headers: Map<String, String>,
)

/** CloudStream links can carry headers after `|`, e.g. `https://…|Referer=…&User-Agent=…`. */
private fun parseCloudStreamPlaybackUrl(rawUrl: String): ParsedCloudStreamPlaybackUrl {
    val trimmed = rawUrl.trim()
    val url = trimmed.substringBefore('|').trim()
    val headerPayload = trimmed.substringAfter('|', missingDelimiterValue = "")
    if (headerPayload.isBlank()) return ParsedCloudStreamPlaybackUrl(url = url, headers = emptyMap())

    val headers = headerPayload
        .split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=', missingDelimiterValue = "").trim()
            val value = entry.substringAfter('=', missingDelimiterValue = "").trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
    return ParsedCloudStreamPlaybackUrl(url = url, headers = headers)
}
