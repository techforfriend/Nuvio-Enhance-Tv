package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.dto.AioParsedFileDto
import com.nuvio.tv.data.remote.dto.AioStreamDataDto
import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.data.remote.dto.ProxyHeadersDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveParsedDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveRawDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveStreamDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.SubtitleDto
import com.nuvio.tv.data.remote.dto.SubtitleItemDto
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

fun StreamDto.toDomain(addonName: String, addonLogo: String?): Stream = Stream(
    name = name,
    title = title,
    description = description,
    url = url,
    ytId = ytId,
    infoHash = infoHash,
    fileIdx = fileIdx,
    externalUrl = externalUrl,
    behaviorHints = behaviorHints?.toDomain(),
    addonName = addonName,
    addonLogo = addonLogo,
    sources = sources,
    clientResolve = clientResolve?.toDomain(),
    subtitles = subtitles.toStreamSubtitles(addonName, addonLogo),
    streamData = streamData.toAioStreamDataOrNull()
)

private val streamDataJson = NetworkModule.provideWireJson()

/**
 * Decodes one stream's AIOStreams `streamData`. Untrusted addon JSON: a shape the app does
 * not expect drops this stream's data only (the stream keeps its text facts); nothing is
 * logged, as decode messages embed the payload (KT-JSON-04).
 */
private fun JsonElement?.toAioStreamDataOrNull(): AioStreamData? {
    if (this !is JsonObject) return null
    val dto = try {
        streamDataJson.decodeFromJsonElement(AioStreamDataDto.serializer(), this)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return AioStreamData(
        size = dto.size,
        filename = dto.filename,
        cached = dto.service?.cached,
        parsedFile = dto.parsedFile?.toDomain(),
        upstream = dto.addon?.let { oneLineCapped(it, MAX_UPSTREAM_NAME) },
        type = dto.type,
        serviceId = dto.service?.id,
        durationMs = (dto.duration as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 1.0 }?.toLong()
    )
}

/** An upstream addon's name as display text (Spec 42 D6/D7). */
private const val MAX_UPSTREAM_NAME = 40

private fun AioParsedFileDto.toDomain(): AioParsedFile = AioParsedFile(
    resolution = resolution,
    quality = quality,
    encode = encode,
    releaseGroup = releaseGroup,
    visualTags = visualTags.orEmpty(),
    audioTags = audioTags.orEmpty(),
    audioChannels = audioChannels.orEmpty(),
    languages = languages.orEmpty(),
    editions = editions.orEmpty()
)

/**
 * Bounds what one stream row can push into the player's subtitle list and the
 * player route's `subtitles` arg that carries it (Screen.Player.createRoute).
 */
private const val MAX_STREAM_SUBTITLES = 100

/**
 * Bounds one subtitles-resource response. Far above a real addon list (all languages
 * of a popular title), so it only stops a hostile or broken addon from flooding the
 * player's subtitle menu.
 */
private const val MAX_ADDON_SUBTITLES = 500

/** Maps Stremio `stream.subtitles` to the addon-subtitle model (rules: [subtitleOrNull]). */
internal fun List<SubtitleDto>?.toStreamSubtitles(addonName: String, addonLogo: String?): List<Subtitle> =
    orEmpty().asSequence()
        .mapNotNull { dto ->
            subtitleOrNull(
                dto.id, dto.url, dto.lang, null, addonName, addonLogo,
                dto.movieReleaseName, dto.subtitleFileName, dto.releaseGroup
            )
        }
        .take(MAX_STREAM_SUBTITLES)
        .toList()

/**
 * Maps a subtitles-resource response (rules: [subtitleOrNull]). Some addons send
 * `language` instead of `lang`.
 */
internal fun List<SubtitleItemDto>?.toAddonSubtitles(addonName: String, addonLogo: String?): List<Subtitle> =
    orEmpty().asSequence()
        .mapNotNull { dto ->
            val lang = dto.lang?.takeIf { it.isNotBlank() } ?: dto.language
            subtitleOrNull(
                dto.id, dto.url, lang, dto.headers, addonName, addonLogo,
                dto.movieReleaseName, dto.subtitleFileName, dto.releaseGroup
            )
        }
        .take(MAX_ADDON_SUBTITLES)
        .toList()

/**
 * One home for untrusted addon subtitle entries, from stream rows and the subtitles
 * resource alike. Only http(s) urls survive (no file:, content:, data:, ... reaching
 * ExoPlayer); headers go through [sanitizeHeaderMap]; a blank lang falls back to
 * "und" and a blank id derives from lang and url. Release fields are display text only
 * (one line, capped).
 */
private fun subtitleOrNull(
    id: String?,
    url: String?,
    lang: String?,
    headers: Map<String, String>?,
    addonName: String,
    addonLogo: String?,
    movieReleaseName: String?,
    subtitleFileName: String?,
    releaseGroup: String?
): Subtitle? {
    val httpUrl = url?.trim()?.takeIf(::isHttpUrl) ?: return null
    val resolvedLang = lang?.takeIf { it.isNotBlank() } ?: "und"
    return Subtitle(
        id = id?.takeIf { it.isNotBlank() } ?: "$resolvedLang-${httpUrl.hashCode()}",
        url = httpUrl,
        lang = resolvedLang,
        addonName = addonName,
        addonLogo = addonLogo,
        headers = sanitizeHeaderMap(headers),
        releaseName = subtitleReleaseName(movieReleaseName, subtitleFileName),
        releaseGroup = releaseGroup?.let(::oneLineCapped)
    )
}

/**
 * A subtitle's release name: the addon's release field, else its file name without the
 * subtitle extension ("Tears.of.Steel.2012.BRRip-RemixHD.srt" -> "...-RemixHD"); one line, capped.
 */
internal fun subtitleReleaseName(movieReleaseName: String?, subtitleFileName: String?): String? {
    movieReleaseName?.let(::oneLineCapped)?.let { return it }
    return subtitleFileName?.let { oneLineCapped(dropFileExtension(it.replace(WHITESPACE_RUN, " ").trim())) }
}

/** [name] without a trailing ".srt" / ".mkv" / ".mp4"-style extension (2-4 letters or digits, one letter at least). */
internal fun dropFileExtension(name: String): String {
    val extension = name.substringAfterLast('.', "")
    val isExtension = extension.length in 2..4 && extension.all { it.isLetterOrDigit() } && extension.any { it.isLetter() }
    return if (isExtension) name.dropLast(extension.length + 1) else name
}

/**
 * Untrusted addon text as one display line of at most [max] chars: control and line/paragraph
 * separators read as spaces, invisible format chars (bidi overrides, zero-width) are dropped,
 * whitespace runs collapse, and a cut never leaves half a surrogate pair.
 */
private fun oneLineCapped(text: String, max: Int = MAX_RELEASE_TEXT): String? {
    val visible = buildString(text.length) {
        text.forEach { char ->
            when (Character.getType(char).toByte()) {
                Character.FORMAT -> Unit
                Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> append(' ')
                else -> append(char)
            }
        }
    }
    val capped = visible.replace(WHITESPACE_RUN, " ").trim().take(max)
    return capped.dropLastWhile { it.isHighSurrogate() }.trimEnd().ifEmpty { null }
}

private val WHITESPACE_RUN = Regex("\\s+")
private const val MAX_RELEASE_TEXT = 160

private fun isHttpUrl(url: String): Boolean =
    (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) &&
        url.length > url.indexOf("://") + 3

/** AIOStreams `streamData.type` values that mark a status pseudo-stream, not a source. */
private val GATEWAY_STATUS_TYPES = setOf("error", "statistic")

/**
 * True for an AIOStreams status pseudo-stream (Spec 42 P1): an upstream's error (`[❌] <addon>`,
 * appended when an upstream timed out or failed) or a statistics line. Both point their
 * externalUrl at the project page, so as rows they opened a browser. AIOStreams sets
 * `streamData.type` on them whatever the User-Agent (v2.36.0 `transformers/stremio.ts`
 * createErrorStream / toStatisticStream).
 */
internal fun StreamDto.isGatewayStatusRow(): Boolean = streamDataString("type") in GATEWAY_STATUS_TYPES

/**
 * The upstream an AIOStreams error row names (Spec 42 P6), else null. Its title is
 * `[❌] <upstream>` (v2.36.0 `streams/fetcher.ts` fetchFromAddon); the `name` carries the same text
 * when `streamData.error` is missing. The description (the raw reason) is not read.
 */
internal fun StreamDto.gatewayErrorUpstream(): String? {
    if (streamDataString("type") != "error") return null
    val error = (streamData as? JsonObject)?.get("error") as? JsonObject
    val title = (error?.get("title") as? JsonPrimitive)?.contentOrNull ?: name ?: return null
    return oneLineCapped(title.replace(ERROR_TITLE_MARK, ""), MAX_UPSTREAM_NAME)
}

private fun StreamDto.streamDataString(key: String): String? =
    ((streamData as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

/** AIOStreams' error-title prefix, `[❌] ` (repeated when an upstream is itself a gateway). */
private val ERROR_TITLE_MARK = Regex("^(\\s*\\[\\s*❌\\s*]\\s*)+")

/**
 * One home for turning an addon's stream rows into the streams the app lists: the stream
 * resource, the meta-inline fallback and a meta's own videos all come through here. Rows the
 * app cannot play are left out ([isUnplayable], [withoutResolverOnlyStreams]).
 */
internal fun List<StreamDto>.toListedStreams(addonName: String, addonLogo: String?): List<Stream> =
    filterNot { it.isUnplayable() }
        .map { it.toDomain(addonName, addonLogo) }
        .withoutResolverOnlyStreams()

/**
 * A row the app cannot play (Spec 84 A4, A11): its only targets are a YouTube id (the in-app
 * extractor's split video and audio fit the trailer player only), a Usenet NZB or archive URLs;
 * or it is a disc image (`.iso` url or filename), which neither engine opens. Rows with no
 * target at all (addon info rows) are kept.
 */
internal fun StreamDto.isUnplayable(): Boolean {
    val unsupportedOnly = url == null && externalUrl == null && infoHash == null &&
        (ytId != null || nzbUrl != null || rarUrls != null || zipUrls != null ||
            sevenZipUrls != null || tgzUrls != null || tarUrls != null)
    return unsupportedOnly || listOfNotNull(url?.substringBefore('?')?.substringBefore('#'), behaviorHints?.filename)
        .any { it.trimEnd().endsWith(".iso", ignoreCase = true) }
}

/**
 * Drops clientResolve-only entries (no HTTP url or torrent; an http externalUrl
 * counts as a url via getStreamUrl): they targeted the in-app debrid resolver this
 * app no longer has. Other target-less rows (addon info/status rows) are left as
 * they were.
 */
internal fun List<Stream>.withoutResolverOnlyStreams(): List<Stream> = filter { stream ->
    stream.clientResolve == null ||
        stream.getStreamUrl() != null ||
        stream.isTorrent()
}

fun StreamClientResolveDto.toDomain(): StreamClientResolve = StreamClientResolve(
    type = type,
    infoHash = infoHash,
    fileIdx = fileIdx,
    sources = sources,
    torrentName = torrentName,
    filename = filename,
    mediaType = mediaType,
    title = title,
    season = season,
    episode = episode,
    service = service,
    stream = stream?.toDomain()
)

fun StreamClientResolveStreamDto.toDomain(): StreamClientResolveStream = StreamClientResolveStream(
    raw = raw?.toDomain()
)

fun StreamClientResolveRawDto.toDomain(): StreamClientResolveRaw = StreamClientResolveRaw(
    torrentName = torrentName,
    filename = filename,
    size = size,
    network = network,
    parsed = parsed?.toDomain()
)

fun StreamClientResolveParsedDto.toDomain(): StreamClientResolveParsed = StreamClientResolveParsed(
    rawTitle = rawTitle,
    parsedTitle = parsedTitle,
    year = year,
    resolution = resolution,
    seasons = seasons,
    episodes = episodes,
    quality = quality,
    hdr = hdr,
    codec = codec,
    audio = audio,
    channels = channels,
    languages = languages,
    group = group,
    network = network,
    edition = edition,
    duration = duration,
    bitDepth = bitDepth,
    extended = extended,
    theatrical = theatrical,
    remastered = remastered,
    unrated = unrated
)

fun BehaviorHintsDto.toDomain(): StreamBehaviorHints = StreamBehaviorHints(
    notWebReady = notWebReady,
    bingeGroup = bingeGroup,
    countryWhitelist = countryWhitelist,
    proxyHeaders = proxyHeaders?.toDomain(),
    videoHash = videoHash,
    videoSize = videoSize,
    filename = filename
)

fun ProxyHeadersDto.toDomain(): ProxyHeaders = ProxyHeaders(
    request = sanitizeHeaderMap(request),
    response = sanitizeHeaderMap(response)
)

private fun sanitizeHeaderMap(headers: Map<String, String>?): Map<String, String>? {
    if (headers == null) return null
    val raw: Map<*, *> = headers
    if (raw.isEmpty()) return null

    val sanitized = LinkedHashMap<String, String>(raw.size)
    raw.forEach { (rawKey, rawValue) ->
        val key = (rawKey as? String)?.trim().orEmpty()
        val value = (rawValue as? String)?.trim().orEmpty()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        // 2026-08-26 review: control characters make OkHttp throw at request build,
        // killing that stream's playback; drop the entry instead.
        if (hasHeaderControlChars(key) || hasHeaderControlChars(value)) return@forEach
        sanitized[key] = value
    }
    return sanitized.takeIf { it.isNotEmpty() }
}

private fun hasHeaderControlChars(text: String): Boolean =
    text.any { it.code < 0x20 || it.code == 0x7F }
