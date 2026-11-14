package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StreamResponseDto(
    @SerialName("streams") val streams: List<StreamDto>? = null
)

@Serializable
data class StreamDto(
    @SerialName("name") val name: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("ytId") val ytId: String? = null,
    @SerialName("infoHash") val infoHash: String? = null,
    @SerialName("fileIdx") val fileIdx: Int? = null,
    @SerialName("externalUrl") val externalUrl: String? = null,
    @SerialName("behaviorHints") val behaviorHints: BehaviorHintsDto? = null,
    @SerialName("sources") val sources: List<String>? = null,
    @SerialName("subtitles") val subtitles: List<SubtitleDto>? = null,
    @SerialName("clientResolve") val clientResolve: StreamClientResolveDto? = null,
    /**
     * Usenet and archive targets (Stremio SDK stream object), which the app cannot play: read only
     * to recognise such rows ([com.nuvio.tv.data.mapper.isUnplayable], Spec 84 A4). Raw, so a
     * shape the app does not expect never fails the response.
     */
    @SerialName("nzbUrl") val nzbUrl: JsonElement? = null,
    @SerialName("rarUrls") val rarUrls: JsonElement? = null,
    @SerialName("zipUrls") val zipUrls: JsonElement? = null,
    @SerialName("7zipUrls") val sevenZipUrls: JsonElement? = null,
    @SerialName("tgzUrls") val tgzUrls: JsonElement? = null,
    @SerialName("tarUrls") val tarUrls: JsonElement? = null,
    /**
     * AIOStreams' structured release data (X1). Kept raw and decoded per stream into
     * [AioStreamDataDto] by the mapper, so a schema drift in one stream drops only its data.
     */
    @SerialName("streamData") val streamData: JsonElement? = null
)

/**
 * The part of AIOStreams' `streamData` the app reads (sent only to `AIO*` user agents;
 * shape: AIOStreams packages/core/src/db/schemas.ts ParsedFileSchema). `addon` is the upstream
 * addon's name, `type` the stream kind (debrid, http, p2p, ...; v2.36.0 constants.ts).
 */
@Serializable
data class AioStreamDataDto(
    @SerialName("type") val type: String? = null,
    @SerialName("addon") val addon: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("filename") val filename: String? = null,
    /**
     * The file's own length, ms (schema `z.number()`, so possibly fractional; 0 when unknown).
     * Raw, so a value of another type is dropped alone, not with the rest of this streamData.
     */
    @SerialName("duration") val duration: JsonElement? = null,
    @SerialName("service") val service: AioStreamServiceDto? = null,
    @SerialName("parsedFile") val parsedFile: AioParsedFileDto? = null
)

@Serializable
data class AioStreamServiceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("cached") val cached: Boolean? = null
)

@Serializable
data class AioParsedFileDto(
    @SerialName("resolution") val resolution: String? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("encode") val encode: String? = null,
    @SerialName("releaseGroup") val releaseGroup: String? = null,
    @SerialName("visualTags") val visualTags: List<String>? = null,
    @SerialName("audioTags") val audioTags: List<String>? = null,
    @SerialName("audioChannels") val audioChannels: List<String>? = null,
    @SerialName("languages") val languages: List<String>? = null,
    @SerialName("editions") val editions: List<String>? = null
)

@Serializable
data class StreamClientResolveDto(
    @SerialName("type") val type: String? = null,
    @SerialName("infoHash") val infoHash: String? = null,
    @SerialName("fileIdx") val fileIdx: Int? = null,
    @SerialName("magnetUri") val magnetUri: String? = null,
    @SerialName("sources") val sources: List<String>? = null,
    @SerialName("torrentName") val torrentName: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("mediaId") val mediaId: String? = null,
    @SerialName("mediaOnlyId") val mediaOnlyId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("service") val service: String? = null,
    @SerialName("serviceIndex") val serviceIndex: Int? = null,
    @SerialName("serviceExtension") val serviceExtension: String? = null,
    @SerialName("isCached") val isCached: Boolean? = null,
    @SerialName("stream") val stream: StreamClientResolveStreamDto? = null
)

@Serializable
data class StreamClientResolveStreamDto(
    @SerialName("raw") val raw: StreamClientResolveRawDto? = null
)

@Serializable
data class StreamClientResolveRawDto(
    @SerialName("torrentName") val torrentName: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("folderSize") val folderSize: Long? = null,
    @SerialName("tracker") val tracker: String? = null,
    @SerialName("indexer") val indexer: String? = null,
    @SerialName("network") val network: String? = null,
    @SerialName("parsed") val parsed: StreamClientResolveParsedDto? = null
)

@Serializable
data class StreamClientResolveParsedDto(
    @SerialName("raw_title") val rawTitle: String? = null,
    @SerialName("parsed_title") val parsedTitle: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("resolution") val resolution: String? = null,
    @SerialName("seasons") val seasons: List<Int>? = null,
    @SerialName("episodes") val episodes: List<Int>? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("hdr") val hdr: List<String>? = null,
    @SerialName("codec") val codec: String? = null,
    @SerialName("audio") val audio: List<String>? = null,
    @SerialName("channels") val channels: List<String>? = null,
    @SerialName("languages") val languages: List<String>? = null,
    @SerialName("group") val group: String? = null,
    @SerialName("network") val network: String? = null,
    @SerialName("edition") val edition: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("bit_depth") val bitDepth: String? = null,
    @SerialName("extended") val extended: Boolean? = null,
    @SerialName("theatrical") val theatrical: Boolean? = null,
    @SerialName("remastered") val remastered: Boolean? = null,
    @SerialName("unrated") val unrated: Boolean? = null
)

@Serializable
data class BehaviorHintsDto(
    @SerialName("notWebReady") val notWebReady: Boolean? = null,
    @SerialName("bingeGroup") val bingeGroup: String? = null,
    @SerialName("countryWhitelist") val countryWhitelist: List<String>? = null,
    @SerialName("proxyHeaders") val proxyHeaders: ProxyHeadersDto? = null,
    @SerialName("videoHash") val videoHash: String? = null,
    @SerialName("videoSize") val videoSize: Long? = null,
    @SerialName("filename") val filename: String? = null
)

@Serializable
data class ProxyHeadersDto(
    @SerialName("request") val request: Map<String, String>? = null,
    @SerialName("response") val response: Map<String, String>? = null
)

@Serializable
data class SubtitleDto(
    @SerialName("id") val id: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("lang") val lang: String? = null,
    // OpenSubtitles v3 release fields (Spec 26/26l smoke): shown on the Subtitles panel row.
    @SerialName("movieReleaseName") val movieReleaseName: String? = null,
    @SerialName("subtitleFileName") val subtitleFileName: String? = null,
    @SerialName("releaseGroup") val releaseGroup: String? = null
)
