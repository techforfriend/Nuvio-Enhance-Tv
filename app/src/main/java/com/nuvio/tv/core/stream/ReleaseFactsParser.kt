package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.ReleaseAudioChannel
import com.nuvio.tv.domain.model.ReleaseAudioTag
import com.nuvio.tv.domain.model.ReleaseEncode
import com.nuvio.tv.domain.model.ReleaseRankPreferences
import com.nuvio.tv.domain.model.ReleaseQuality
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.ReleaseVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses release facts (resolution, source quality, HDR/DV, audio, encode,
 * release group, size, editions, languages) from an addon stream's advertised
 * text and structured clientResolve parse. AIOStreams' own parse
 * (`streamData.parsedFile`, X1) wins field by field where it maps; the text
 * path fills the rest. Shared by source badges, the stream-card facts line,
 * and auto-play QUALITY_RANK.
 */
object ReleaseFactsParser {
    /** [hasToken] patterns keyed by the (lowercase literal) token; built once per token. */
    private val tokenRegexCache = ConcurrentHashMap<String, Regex>()
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]")
    private val NON_ALNUM_PLUS_REGEX = Regex("[^a-z0-9+]")

    /**
     * Resolution passes, each in ladder order; first match wins. "NNNp" heights come first:
     * "UHD BluRay 1080p" is a 1080p encode of a UHD source (B10). Then the named aliases, then
     * bare numbers (a bare "720" may be a seeder count, so it must not beat "4K"), and "hd"/"sd"
     * last ("hd" is also the HD of "DTS-HD", so it must not beat a bare "1080").
     */
    private val RESOLUTION_HEIGHTS: List<Pair<String, ReleaseResolution>> = listOf(
        "2160" to ReleaseResolution.P2160,
        "1440" to ReleaseResolution.P1440,
        "1080" to ReleaseResolution.P1080,
        "720" to ReleaseResolution.P720,
        "576" to ReleaseResolution.P576,
        "480" to ReleaseResolution.P480,
        "360" to ReleaseResolution.P360
    )
    private val RESOLUTION_HEIGHT_PATTERNS: List<Pair<Regex, ReleaseResolution>> =
        RESOLUTION_HEIGHTS.map { (height, resolution) -> tokenRegex("${height}p") to resolution }
    private val RESOLUTION_BARE_HEIGHT_PATTERNS: List<Pair<Regex, ReleaseResolution>> =
        RESOLUTION_HEIGHTS.map { (height, resolution) -> tokenRegex(height) to resolution }
    private val RESOLUTION_ALIAS_PATTERNS: List<Pair<Regex, ReleaseResolution>> = listOf(
        tokenRegex("4k", "uhd") to ReleaseResolution.P2160,
        tokenRegex("2k") to ReleaseResolution.P1440,
        tokenRegex("fhd") to ReleaseResolution.P1080
    )
    private val RESOLUTION_WEAK_ALIAS_PATTERNS: List<Pair<Regex, ReleaseResolution>> = listOf(
        tokenRegex("hd") to ReleaseResolution.P720,
        tokenRegex("sd") to ReleaseResolution.P480
    )
    private val DV_TEXT_REGEX = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)")

    /** 3D layout tokens (half/full side-by-side, over-under, top-and-bottom). */
    private const val THREE_D_LAYOUTS = "half(-?sbs|-?ou)?|h-?sbs|h-?ou|sbs|ou|tab"

    /**
     * 3D is read case-sensitively ("3D", as release names write it) or beside a 3D layout
     * token: a lowercase "3d" alone is also a formatter's age ("· 3d" = 3 days old), and
     * 3D is a default exclusion, so misreading it dropped those streams from auto-play (B9).
     */
    private val THREE_D_TEXT_REGEX = Regex("(^|[^A-Za-z0-9])3D([^A-Za-z0-9]|$)")
    private val THREE_D_LAYOUT_REGEX = Regex(
        "(^|[^a-z0-9])(3d[ ._-]*($THREE_D_LAYOUTS)|($THREE_D_LAYOUTS)[ ._-]*3d)([^a-z0-9]|$)"
    )

    /**
     * Dolby Digital Plus spellings, and plain Dolby Digital that is none of them: the "dd" of
     * "dd+"/"ddp", the "ac3" of "eac3"/"e-ac3" and "dolby digital plus" are not DD (B11).
     */
    private val DD_PLUS_TEXT_REGEX = Regex("dd\\+|ddp|dolby digital ?(plus|\\+)|e-?ac-?3")
    private val DD_TEXT_REGEX = Regex("(?<![a-z0-9])dd(?![a-z+])|(?<!e)(?<!e-)ac3|dolby digital(?! ?(plus|\\+))")
    private val HDR_TEXT_REGEX = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|$)")
    private const val CONTAINER_EXTENSIONS = "mkv|mp4|m4v|avi|ts|m2ts|webm|mov"
    private val CONTAINER_EXTENSION_REGEX = Regex("\\.($CONTAINER_EXTENSIONS)$", RegexOption.IGNORE_CASE)
    private val LEADING_GROUP_TOKEN_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._]{0,23}")
    private val BRACKET_GROUP_REGEX = Regex("^\\[([A-Za-z0-9][A-Za-z0-9._-]{1,23})\\]")
    private val TERMINAL_GROUP_REGEX = Regex("-([A-Za-z0-9][A-Za-z0-9_]{1,23})$")
    private val SEASON_EPISODE_TOKEN_REGEX = Regex("(?i)(s\\d{1,4}(e\\d{1,4})?|e\\d{1,4})")
    private val CHANNEL_TOKEN_REGEX = Regex("^\\d\\.\\d$")

    /**
     * Fake-quality spellings (Spec 65 P4; VortX `StreamRanking.kt` `junkClass`, GPL-3.0): CAM, TS, TC,
     * screener, R5, workprint and upscaled releases. Bare cam/ts/tc/scr are [ReleaseQuality] already
     * (default exclusions, so not repeated here). Read on lowercase text with [JUNK_BLANK_REGEX]'s
     * matches blanked: container extensions ("HD.ts" is not a telesync) and negated upscale phrases
     * ("Not.AI.Upscaled"). An apostrophe before "s print" is a possessive ("director's print"); the
     * dotted spelling "Director.s.Print" still reads as S-Print (indistinguishable from the tag).
     */
    private val JUNK_TEXT_REGEX = Regex(
        "(?<![a-z0-9])(" +
            "h[dq][ ._-]?cam(rip)?|cam[ ._-]?rip|(?<!['\u2019])s[ ._-]+print|" +
            "telesynch?|hd[ ._-]?ts(rip)?|ts[ ._-]?rip|telecine|hd[ ._-]?tc|" +
            "screener|(dvd|bd|br|web|hd)[ ._-]?scr|p(re)?dvd(rip)?|workprint|r5|" +
            "upscaled?|up[ ._-]?rez|ai[ ._-]?(upscaled?|enhanced?)" +
            ")(?![a-z0-9])"
    )
    private val JUNK_BLANK_REGEX = Regex(
        "\\.($CONTAINER_EXTENSIONS)(?![a-z0-9])|" +
            "(?<![a-z0-9])(not|non|no)[ ._-]+(ai[ ._-]?)?(upscaled?|up[ ._-]?rez|enhanced?)(?![a-z0-9])"
    )
    private const val MIB = 1024L * 1024L

    /** Hyphen-suffixed tokens in release names that are never the group. */
    private val NON_GROUP_TOKENS = setOf(
        "dl", "rip", "hd", "uhd", "sd", "web", "bluray", "remux", "hdr", "hdr10", "sdr",
        "dv", "dovi", "ma", "es", "x", "atmos", "truehd", "dts", "aac", "ac3", "eac3",
        "flac", "opus", "avc", "hevc", "av1", "x264", "x265", "h264", "h265", "vc1",
        "10bit", "8bit", "hi10p", "imax", "proper", "repack", "extended", "remastered",
        "unrated", "multi", "dual", "sub", "subs", "dubbed", "hc", "cam", "ts", "tc", "scr",
        "2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "4k", "2k",
        // Same on unrelated releases, so never a group (Spec 65 P7 red team b19e4557): rip and
        // source words, codecs, service tags, dub/audio, Usenet repost suffixes.
        "webrip", "webdl", "hdrip", "bdrip", "brrip", "dvdrip", "hdtv", "xvid", "divx", "amzn", "nf",
        "dub", "audio", "obfuscated", "postbot", "xpost", "rakuv", "asrequested", "scrambled"
    )

    /** Ladder/exclusion names containing '-' or '.' that last-hyphen splitting would mangle. */
    private val COMPOUND_GROUP_REGEX = Regex(
        "(?<![A-Za-z0-9])(VISIONPLUSHDR\\-X|BR\\-GuyZo|Pahe\\.in|Pahe\\.ph|D\\-Z0N3|YTS\\.LT|YTS\\.MX|YTS\\.AG|C\\.A\\.A)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )

    /**
     * AIOStreams labels to the app's enums. The enums carry AIOStreams' own vocabulary
     * (constants.ts RESOLUTIONS, QUALITIES, VISUAL_TAGS, AUDIO_TAGS, AUDIO_CHANNELS, ENCODES),
     * so a label the app has no enum for ("240p", "Upscaled", "PCM", "VC-1") maps to nothing.
     */
    private val AIO_RESOLUTIONS = labelMap(ReleaseResolution.entries, ReleaseResolution.UNKNOWN) { it.label }
    private val AIO_QUALITIES = labelMap(ReleaseQuality.entries, ReleaseQuality.UNKNOWN) { it.label } +
        ("dvd remux" to ReleaseQuality.DVDRIP) // the text path would read its "remux" as BLURAY_REMUX
    private val AIO_VISUAL_TAGS = labelMap(ReleaseVisualTag.entries, ReleaseVisualTag.UNKNOWN) { it.label }
    private val AIO_AUDIO_TAGS = labelMap(ReleaseAudioTag.entries, ReleaseAudioTag.UNKNOWN) { it.label }
    private val AIO_AUDIO_CHANNELS = labelMap(ReleaseAudioChannel.entries, ReleaseAudioChannel.UNKNOWN) { it.label }
    private val AIO_ENCODES = labelMap(ReleaseEncode.entries, ReleaseEncode.UNKNOWN) { it.label }

    /** Tags naming Dolby Vision / any HDR format; also the filters' HDR keys (StreamFacets). */
    internal val DV_FAMILY = setOf(ReleaseVisualTag.DV, ReleaseVisualTag.DV_ONLY, ReleaseVisualTag.HDR_DV)
    internal val HDR_FAMILY = setOf(
        ReleaseVisualTag.HDR, ReleaseVisualTag.HDR10, ReleaseVisualTag.HDR10_PLUS, ReleaseVisualTag.HLG,
        ReleaseVisualTag.HDR_ONLY, ReleaseVisualTag.HDR_DV
    )
    private val DV_HDR_COMBINATIONS = setOf(ReleaseVisualTag.HDR_DV, ReleaseVisualTag.DV_ONLY, ReleaseVisualTag.HDR_ONLY)
    private val DTS_FAMILY = setOf(
        ReleaseAudioTag.DTS_X, ReleaseAudioTag.DTS_HD_MA, ReleaseAudioTag.DTS_HD, ReleaseAudioTag.DTS_ES
    )

    /** Excluded-list checks shared with auto-select ranking across all sources. */
    private fun ReleaseFacts.passesExclusions(preferences: ReleaseRankPreferences): Boolean {
        if (resolution in preferences.excludedResolutions) return false
        if (quality in preferences.excludedQualities) return false
        if (visualTags.any { it in preferences.excludedVisualTags }) return false
        if (audioTags.any { it in preferences.excludedAudioTags }) return false
        if (audioChannels.any { it in preferences.excludedAudioChannels }) return false
        if (encode in preferences.excludedEncodes) return false
        if (preferences.excludedReleaseGroups.any { releaseGroup.equals(it, ignoreCase = true) }) return false
        return true
    }

    fun passesExclusionFilters(
        facts: ReleaseFacts,
        preferences: ReleaseRankPreferences = ReleaseRankPreferences.DEFAULT
    ): Boolean = facts.passesExclusions(preferences)

    fun factsFor(
        stream: Stream,
        preferences: ReleaseRankPreferences = ReleaseRankPreferences.DEFAULT
    ): ReleaseFacts = streamFacts(stream, preferences)

    /**
     * Only the resolution of [stream], as [factsFor] reads it (Spec 58 F3's resolution limit): the
     * stream's search text is built only when the AIOStreams parse names no resolution, so a list
     * check stays far cheaper than full facts.
     */
    fun resolutionOf(stream: Stream): ReleaseResolution =
        resolutionFrom(stream, stream.streamData?.parsedFile, stream.clientResolve?.stream?.raw?.parsed) {
            streamSearchText(stream).lowercase()
        }

    /**
     * The one resolution rule: AIOStreams' parse, else the clientResolve parse and the stream's
     * quality, else the stream's text. [searchText] runs only for that last step (a vararg argument
     * would build it every time).
     */
    private inline fun resolutionFrom(
        stream: Stream,
        aio: AioParsedFile?,
        parsed: StreamClientResolveParsed?,
        searchText: () -> String
    ): ReleaseResolution = aio?.resolution.mappedBy(AIO_RESOLUTIONS)
        ?: streamResolution(parsed?.resolution, parsed?.quality, stream.quality).takeIf { it != ReleaseResolution.UNKNOWN }
        ?: streamResolution(searchText())

    /**
     * With an AIOStreams parse, its visual tags and release group are final (empty means
     * none): the formatter text is what misread an age as 3D (B9) and dotted names as a group
     * (B12). Every other field falls back to the text path when the parse lacks or cannot
     * map it.
     */
    private fun streamFacts(stream: Stream, preferences: ReleaseRankPreferences): ReleaseFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val aio = stream.streamData?.parsedFile
        val rawSearchText = streamSearchText(stream)
        val searchText = rawSearchText.lowercase()
        val resolution = resolutionFrom(stream, aio, parsed) { searchText }
        val quality = aio?.quality.mappedBy(AIO_QUALITIES) ?: streamQuality(parsed?.quality, searchText)
        val visualTags = aio?.let(::aioVisualTags) ?: streamVisualTags(parsed?.hdr.orEmpty(), rawSearchText)
        val audioTags = aio?.let(::aioAudioTagsOrNull) ?: streamAudioTags(parsed?.audio.orEmpty(), searchText)
        val audioChannels = aio?.let(::aioAudioChannelsOrNull)
            ?: streamAudioChannels(parsed?.channels.orEmpty(), searchText)
        val encode = aio?.encode.mappedBy(AIO_ENCODES) ?: streamEncode(parsed?.codec, searchText)
        val releaseGroup = if (aio != null) aio.releaseGroup?.trim().orEmpty() else releaseGroupOf(stream)
        val size = streamSize(stream)
        return ReleaseFacts(
            resolution = resolution,
            quality = quality,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = audioChannels,
            encode = encode,
            releaseGroup = releaseGroup,
            size = size,
            resolutionRank = rank(resolution, preferences.preferredResolutions),
            qualityRank = rank(quality, preferences.preferredQualities),
            visualRank = rankAny(visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(encode, preferences.preferredEncodes),
            groupRank = releaseGroupRank(releaseGroup, preferences.preferredReleaseGroups),
            editions = streamEditions(aio, parsed, searchText),
            languages = streamLanguages(aio, parsed, searchText),
            suspect = isSuspect(aio, resolution, size, searchText)
        )
    }

    /** [ReleaseFacts.suspect]: a fake-quality parse or spelling, or an implausible size (Spec 65 P4). */
    private fun isSuspect(
        aio: AioParsedFile?,
        resolution: ReleaseResolution,
        size: Long?,
        searchText: String
    ): Boolean =
        aio?.quality?.trim().equals("upscaled", ignoreCase = true) ||
            JUNK_TEXT_REGEX.containsMatchIn(JUNK_BLANK_REGEX.replace(searchText, " ")) ||
            implausibleSize(resolution, size)

    /**
     * A known size far too small for the claimed resolution even for an episode (VortX
     * `implausibleForResolution` episode floors). Release text cannot tell a film from an episode
     * (Spec 65 S1 red team: 1x04, Ep05, date-named shows, seeder counts read as years), so the
     * film floors apply only through [belowFilmSizeFloor], when the caller knows (D4).
     */
    private fun implausibleSize(resolution: ReleaseResolution, size: Long?): Boolean =
        belowSizeFloor(resolution, size, film = false)

    /** [facts]' known size is under the film floor for its resolution (Spec 65 D4: caller-known films only). */
    fun belowFilmSizeFloor(facts: ReleaseFacts): Boolean = belowSizeFloor(facts.resolution, facts.size, film = true)

    /**
     * VortX floors: 2160p film 1800 / episode 700 MiB, 1080p 600 / 150. Unknown size, or a resolution
     * other than 2160p/1080p, passes; so does a reading under 1 MiB, a misparsed bitrate ("640kb").
     */
    private fun belowSizeFloor(resolution: ReleaseResolution, size: Long?, film: Boolean): Boolean {
        if (size == null || size < MIB) return false
        val floorMib = when (resolution) {
            ReleaseResolution.P2160 -> if (film) 1800 else 700
            ReleaseResolution.P1080 -> if (film) 600 else 150
            else -> return false
        }
        return size < floorMib * MIB
    }

    private fun <E> labelMap(entries: List<E>, unknown: E, label: (E) -> String): Map<String, E> =
        entries.filter { it != unknown }.associateBy { label(it).lowercase() }

    private fun <E> String?.mappedBy(labels: Map<String, E>): E? = this?.let { labels[it.trim().lowercase()] }

    /** One home for the combined DV/HDR tag both paths derive (AIOStreams derives the same set). */
    private fun dvHdrCombination(hasDv: Boolean, hasHdr: Boolean): ReleaseVisualTag? = when {
        hasDv && hasHdr -> ReleaseVisualTag.HDR_DV
        hasDv -> ReleaseVisualTag.DV_ONLY
        hasHdr -> ReleaseVisualTag.HDR_ONLY
        else -> null
    }

    /**
     * Never null and never falls back: an empty parse is [ReleaseVisualTag.UNKNOWN] (B9, see
     * [streamFacts]). Same hierarchy as the text path: HDR10+ also reads as HDR10, DV/HDR
     * families add DV/HDR.
     */
    private fun aioVisualTags(aio: AioParsedFile): List<ReleaseVisualTag> {
        val tags = aio.visualTags.mapNotNullTo(mutableSetOf()) { it.mappedBy(AIO_VISUAL_TAGS) }
        if (aio.editions.any(ReleaseEditions::isImax)) tags += ReleaseVisualTag.IMAX
        if (ReleaseVisualTag.HDR10_PLUS in tags) tags += ReleaseVisualTag.HDR10
        val hasDv = tags.any { it in DV_FAMILY }
        val hasHdr = tags.any { it in HDR_FAMILY }
        // Re-derived, not trusted: a sent "DV Only" beside HDR10 must still end as one HDR_DV.
        tags -= DV_HDR_COMBINATIONS
        dvHdrCombination(hasDv, hasHdr)?.let { tags += it }
        if (hasDv) tags += ReleaseVisualTag.DV
        if (hasHdr) tags += ReleaseVisualTag.HDR
        return tags.sortedBy { it.ordinal }.ifEmpty { listOf(ReleaseVisualTag.UNKNOWN) }
    }

    /**
     * Null when nothing maps, so the text path fills in. Same hierarchy as the text path:
     * DTS-HD MA also reads as DTS-HD, every DTS variant as DTS.
     */
    private fun aioAudioTagsOrNull(aio: AioParsedFile): List<ReleaseAudioTag>? {
        val tags = aio.audioTags.mapNotNullTo(mutableSetOf()) { it.mappedBy(AIO_AUDIO_TAGS) }
        if (tags.isEmpty()) return null
        if (ReleaseAudioTag.DTS_HD_MA in tags) tags += ReleaseAudioTag.DTS_HD
        if (tags.any { it in DTS_FAMILY }) tags += ReleaseAudioTag.DTS
        return tags.sortedBy { it.ordinal }
    }

    /** Null when nothing maps, so the text path fills in; text-path order (7.1 first). */
    private fun aioAudioChannelsOrNull(aio: AioParsedFile): List<ReleaseAudioChannel>? =
        aio.audioChannels.mapNotNullTo(mutableSetOf()) { it.mappedBy(AIO_AUDIO_CHANNELS) }
            .sortedByDescending { it.ordinal }
            .ifEmpty { null }

    /** Edition display tokens (P6), first source that has any: AIOStreams, resolver, text. */
    private fun streamEditions(
        aio: AioParsedFile?,
        parsed: StreamClientResolveParsed?,
        searchText: String
    ): List<String> =
        aio?.editions?.let(ReleaseEditions::tokensOf)?.ifEmpty { null }
            ?: parsed?.let(ReleaseEditions::fromResolver)?.ifEmpty { null }
            ?: ReleaseEditions.fromText(searchText)

    /** Language tokens (P7), first source that has any: AIOStreams, resolver, multi/dual text. */
    private fun streamLanguages(
        aio: AioParsedFile?,
        parsed: StreamClientResolveParsed?,
        searchText: String
    ): List<String> =
        aio?.languages?.let(ReleaseLanguages::tokensOf)?.ifEmpty { null }
            ?: parsed?.languages?.let(ReleaseLanguages::tokensOf)?.ifEmpty { null }
            ?: ReleaseLanguages.multiFromText(searchText)

    private fun streamResolution(vararg values: String?): ReleaseResolution {
        return values.firstNotNullOfOrNull { resolutionValue(it) } ?: ReleaseResolution.UNKNOWN
    }

    private fun resolutionValue(value: String?): ReleaseResolution? {
        val normalized = value?.lowercase().orEmpty()
        return RESOLUTION_HEIGHT_PATTERNS.firstMatch(normalized)
            ?: RESOLUTION_ALIAS_PATTERNS.firstMatch(normalized)
            ?: RESOLUTION_BARE_HEIGHT_PATTERNS.firstMatch(normalized)
            ?: RESOLUTION_WEAK_ALIAS_PATTERNS.firstMatch(normalized)
    }

    private fun List<Pair<Regex, ReleaseResolution>>.firstMatch(text: String): ReleaseResolution? =
        firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second

    private fun streamQuality(parsedQuality: String?, searchText: String): ReleaseQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> ReleaseQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> ReleaseQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> ReleaseQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> ReleaseQuality.WEBRIP
            text.contains("hdrip") -> ReleaseQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> ReleaseQuality.HD_RIP
            text.contains("dvdrip") -> ReleaseQuality.DVDRIP
            text.contains("hdtv") -> ReleaseQuality.HDTV
            text.hasToken("cam") -> ReleaseQuality.CAM
            text.hasToken("ts") -> ReleaseQuality.TS
            text.hasToken("tc") -> ReleaseQuality.TC
            text.hasToken("scr") -> ReleaseQuality.SCR
            else -> ReleaseQuality.UNKNOWN
        }
    }

    /** Takes the original-case text: the 3D check reads case (B9), everything else lowercase. */
    private fun streamVisualTags(parsedHdr: List<String>, rawSearchText: String): List<ReleaseVisualTag> {
        val searchText = rawSearchText.lowercase()
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<ReleaseVisualTag>()
        val hasDv = parsedHdr.any { it.isDolbyVisionToken() } || DV_TEXT_REGEX.containsMatchIn(searchText)
        val hasHdr = parsedHdr.any { it.isHdrToken() } || HDR_TEXT_REGEX.containsMatchIn(searchText)
        dvHdrCombination(hasDv, hasHdr)?.let { tags += it }
        if (text.contains("hdr10+") || text.contains("hdr10plus")) tags += ReleaseVisualTag.HDR10_PLUS
        if (text.contains("hdr10")) tags += ReleaseVisualTag.HDR10
        if (hasDv) tags += ReleaseVisualTag.DV
        if (hasHdr) tags += ReleaseVisualTag.HDR
        if (text.hasToken("hlg")) tags += ReleaseVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += ReleaseVisualTag.TEN_BIT
        val isThreeD = parsedHdr.any { it.equals("3d", ignoreCase = true) } ||
            THREE_D_TEXT_REGEX.containsMatchIn(rawSearchText) ||
            THREE_D_LAYOUT_REGEX.containsMatchIn(text)
        if (isThreeD) tags += ReleaseVisualTag.THREE_D
        if (text.hasToken("imax")) tags += ReleaseVisualTag.IMAX
        if (text.hasToken("ai")) tags += ReleaseVisualTag.AI
        if (text.hasToken("sdr")) tags += ReleaseVisualTag.SDR
        if (text.contains("h-ou")) tags += ReleaseVisualTag.H_OU
        if (text.contains("h-sbs")) tags += ReleaseVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(ReleaseVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<ReleaseAudioTag> {
        val text = (parsedAudio + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<ReleaseAudioTag>()
        if (text.hasToken("atmos")) tags += ReleaseAudioTag.ATMOS
        if (DD_PLUS_TEXT_REGEX.containsMatchIn(text)) tags += ReleaseAudioTag.DD_PLUS
        if (DD_TEXT_REGEX.containsMatchIn(text)) tags += ReleaseAudioTag.DD
        if (text.contains("dts:x") || text.contains("dtsx")) tags += ReleaseAudioTag.DTS_X
        if (text.contains("dts-hd ma") || text.contains("dtshd ma")) tags += ReleaseAudioTag.DTS_HD_MA
        if (text.contains("dts-hd") || text.contains("dtshd")) tags += ReleaseAudioTag.DTS_HD
        if (text.contains("dts-es") || text.contains("dtses")) tags += ReleaseAudioTag.DTS_ES
        if (text.hasToken("dts")) tags += ReleaseAudioTag.DTS
        if (text.contains("truehd") || text.contains("true hd")) tags += ReleaseAudioTag.TRUEHD
        if (text.hasToken("opus")) tags += ReleaseAudioTag.OPUS
        if (text.hasToken("flac")) tags += ReleaseAudioTag.FLAC
        if (text.hasToken("aac")) tags += ReleaseAudioTag.AAC
        return tags.distinct().ifEmpty { listOf(ReleaseAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<ReleaseAudioChannel> {
        val text = (parsedChannels + searchText).joinToString(" ").lowercase()
        val channels = mutableListOf<ReleaseAudioChannel>()
        if (text.hasToken("7.1")) channels += ReleaseAudioChannel.CH_7_1
        if (text.hasToken("6.1")) channels += ReleaseAudioChannel.CH_6_1
        if (text.hasToken("5.1") || text.hasToken("6ch")) channels += ReleaseAudioChannel.CH_5_1
        if (text.hasToken("2.0")) channels += ReleaseAudioChannel.CH_2_0
        return channels.distinct().ifEmpty { listOf(ReleaseAudioChannel.UNKNOWN) }
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): ReleaseEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> ReleaseEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> ReleaseEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> ReleaseEncode.AVC
            text.hasToken("xvid") -> ReleaseEncode.XVID
            text.hasToken("divx") -> ReleaseEncode.DIVX
            else -> ReleaseEncode.UNKNOWN
        }
    }

    /**
     * Spec 65 P7 (D11): the release group only from a source that names one as such: the
     * AIOStreams parse (final, as in [factsFor]), the resolver's parsed group, else a terminal
     * `-GROUP` or leading `[GROUP]` token of a filename or torrent name ([terminalReleaseGroup]). Never the display name,
     * title or description, and never a mid-line hyphen word: those read pack titles ("S01-S05
     * Complete") and episode titles ("- The Return (...)") as groups (red team `f2cd86b6`).
     * Null when none qualifies.
     */
    fun parsedReleaseGroupOf(stream: Stream): String? {
        stream.streamData?.parsedFile?.let { aio -> return aio.releaseGroup?.trim()?.takeIf { it.isNotEmpty() } }
        stream.clientResolve?.stream?.raw?.parsed?.group?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return releaseNamesOf(stream).firstNotNullOfOrNull(::terminalReleaseGroup)
    }

    /** The stream's release-name fields, most reliable first (filenames, then torrent names). */
    private fun releaseNamesOf(stream: Stream): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        return listOfNotNull(
            stream.behaviorHints?.filename,
            resolve?.filename,
            raw?.filename,
            resolve?.torrentName,
            raw?.torrentName
        )
    }

    /**
     * A one-line release name's terminal `-GROUP` (extension stripped), else its leading `[GROUP]`
     * without a dot (a dotted bracket is a site tag), else null.
     */
    private fun terminalReleaseGroup(name: String): String? {
        val line = name.trim().takeIf { it.isNotEmpty() && '\n' !in it } ?: return null
        val stripped = CONTAINER_EXTENSION_REGEX.replace(line, "")
        TERMINAL_GROUP_REGEX.find(stripped)?.groupValues?.get(1)?.takeIf(::isGroupToken)?.let { return it }
        return BRACKET_GROUP_REGEX.find(line)?.groupValues?.get(1)?.takeIf { '.' !in it && isGroupToken(it) }
    }

    private fun isGroupToken(token: String): Boolean =
        token.lowercase() !in NON_GROUP_TOKENS && !token.all { it.isDigit() } && !SEASON_EPISODE_TOKEN_REGEX.matches(token)

    /**
     * Parses the release group from a stream's advertised fields, tried
     * most-reliable-first: resolver-parsed group, then filenames, torrent
     * names, and finally display text. URLs are deliberately not candidates.
     */
    private fun releaseGroupOf(stream: Stream): String {
        val parsedGroup = stream.clientResolve?.stream?.raw?.parsed?.group
        if (!parsedGroup.isNullOrBlank()) return parsedGroup.trim()
        val candidates = releaseNamesOf(stream) + listOfNotNull(stream.name, stream.title, stream.description)
        for (candidate in candidates) {
            val group = releaseGroupFromText(candidate)
            if (group.isNotEmpty()) return group
        }
        return ""
    }

    /**
     * Extracts the group as the token after the LAST hyphen of a line once
     * the container extension is stripped, rejecting codec/source/resolution
     * tokens. Known compound names containing '-' or '.' (e.g. D-Z0N3) are
     * matched explicitly first, and leading [Group] prefixes are honoured.
     * Single-character groups are only accepted when terminal on the line.
     */
    internal fun releaseGroupFromText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        COMPOUND_GROUP_REGEX.find(trimmed)?.let { return it.value }
        BRACKET_GROUP_REGEX.find(trimmed)?.let { match ->
            val candidate = match.groupValues[1]
            if (candidate.lowercase() !in NON_GROUP_TOKENS) return candidate
        }
        for (line in trimmed.lineSequence()) {
            val stripped = CONTAINER_EXTENSION_REGEX.replace(line.trim(), "")
            val hyphen = stripped.lastIndexOf('-')
            if (hyphen <= 0 || hyphen >= stripped.length - 1) continue
            val tail = stripped.substring(hyphen + 1).trim()
            var token = LEADING_GROUP_TOKEN_REGEX.find(tail)?.value ?: continue
            token = CONTAINER_EXTENSION_REGEX.replace(token, "")
            if (token.isEmpty()) continue
            val lower = token.lowercase()
            if (lower in NON_GROUP_TOKENS) continue
            if (token.all { it.isDigit() }) continue
            if (CHANNEL_TOKEN_REGEX.matches(token)) continue
            if (token.length == 1 && tail != token) continue
            return token
        }
        return ""
    }

    private fun releaseGroupRank(group: String, preferred: List<String>): Int {
        if (group.isEmpty()) return Int.MAX_VALUE
        val index = preferred.indexOfFirst { it.equals(group, ignoreCase = true) }
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int {
        return values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE
    }

    /** Whole-token match of lowercase patterns (not escaped, unlike [hasToken]). */
    internal fun tokenRegex(vararg patterns: String): Regex =
        Regex("(^|[^a-z0-9])(${patterns.joinToString("|")})([^a-z0-9]|\$)")

    /**
     * Receiver must already be lowercase (every caller lowercases its joined
     * text once); [token] is a lowercase literal.
     */
    private fun String.hasToken(token: String): Boolean {
        val regex = tokenRegexCache.getOrPut(token) {
            Regex("(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|\$)")
        }
        return regex.containsMatchIn(this)
    }

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(NON_ALNUM_REGEX, "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(NON_ALNUM_PLUS_REGEX, "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSize(stream: Stream): Long? =
        StreamTextSizeParser.effectiveSizeBytes(stream)

    /** Original case; callers lowercase it (only the 3D check reads the original). */
    private fun streamSearchText(stream: Stream): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.quality,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" ")
        ).joinToString(" ")
    }
}

/**
 * Release facts parsed from one stream; `*Rank` fields index the preference ladders.
 * [editions] and [languages] are display tokens ("EXTENDED"; "EN", "MULTI"), not ranked.
 * [suspect]: fake quality (CAM/TS/TC/SCR/R5/workprint/upscaled) or a size implausible for the
 * resolution (Spec 65 P4); auto-play ranks it below every other stream, nothing displays it.
 */
data class ReleaseFacts(
    val resolution: ReleaseResolution,
    val quality: ReleaseQuality,
    val visualTags: List<ReleaseVisualTag>,
    val audioTags: List<ReleaseAudioTag>,
    val audioChannels: List<ReleaseAudioChannel>,
    val encode: ReleaseEncode,
    val releaseGroup: String,
    val size: Long?,
    val resolutionRank: Int,
    val qualityRank: Int,
    val visualRank: Int,
    val audioRank: Int,
    val channelRank: Int,
    val encodeRank: Int,
    val groupRank: Int,
    val editions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val suspect: Boolean = false
)
