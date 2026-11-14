package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.M3uPlaylist
import java.text.Normalizer
import java.util.Locale

/**
 * Channel identity rules (Spec 34 D9): stable keys and the normalisers the EPG match chain uses.
 * The name normaliser is the one the 2026-10-17 derisk match study measured (`match.py`):
 * NFKC, lower case, bracketed parts dropped, `&`/`+` spelled out, quality tokens dropped, then
 * every non-letter/digit removed (spaces too: "Star Jalsha HD" and "STAR JALSHA" both give
 * "starjalsha").
 */
object ChannelIdentity {

    /** tvg-id normalised for matching and keys: trimmed, `@feed` dropped, lower case; null when blank. */
    fun normalizeTvgId(raw: String?): String? =
        raw?.trim()?.substringBefore('@')?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

    /**
     * The ids a tvg-id matches guide ids by, most specific first (Spec 55 S3): the stem joined to
     * its feed when it has a country tag and a letters-or-digits feed (`9Gem.au@Sydney` ->
     * `9gemsydney.au`, open-epg / epgshare01 key regional feeds that way), then [normalizeTvgId].
     */
    fun tvgIdKeys(raw: String?): List<String> {
        val plain = normalizeTvgId(raw) ?: return emptyList()
        val feed = raw.orEmpty().substringAfter('@', "").trim()
        val tagged = FEED_STEM.matchEntire(plain)
        if (tagged == null || !FEED.matches(feed)) return listOf(plain)
        val (stem, country) = tagged.destructured
        return listOf("$stem${feed.lowercase(Locale.ROOT)}.$country", plain)
    }

    /**
     * An id's loose form (Spec 55 S3; guide ids and tvg-id keys alike): the name-normalised stem,
     * a dot, then the country tag with a trailing digit dropped, so epgshare01's `BBC.Two.HD.uk` /
     * `CNN.HD.us2` meet iptv-org's `BBCTwo.uk` / `CNN.us`. Bracketed words stay (only the brackets
     * go), so `HBO.(Pacific).us2` keys as `hbopacific.us`, apart from `HBO.us2`. Null without a
     * country tag or when the stem normalises to nothing. The country stays in the key, so `NTV.bd`
     * never meets `NTV.in`.
     */
    fun looseId(raw: String?): String? {
        val m = LOOSE_ID.matchEntire(normalizeTvgId(raw) ?: return null) ?: return null
        val stem = normalizeName(BRACKET_CHARS.replace(m.groupValues[1], " ")).ifEmpty { return null }
        return "$stem.${m.groupValues[2]}"
    }

    fun normalizeName(raw: String?): String = nameWords(raw).joinToString("")

    /** [normalizeName]'s words before they are joined ("Al Jazeera English" -> al, jazeera, english). */
    fun nameWords(raw: String?): List<String> {
        var s = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        s = BRACKETED.replace(s, "")
        s = s.replace("&", " and ").replace("+", " plus ")
        s = QUALITY.replace(s, " ")
        return s.split(NON_WORD).filter { it.isNotEmpty() }
    }

    /** Country code from an iptv-org style tvg-id (`StarJalsha.in@SD` -> `in`); null otherwise. */
    fun countryOfTvgId(raw: String?): String? =
        normalizeCountry(normalizeTvgId(raw)?.substringAfterLast('.', ""))

    /** The one country form compared anywhere: two lower-case letters (`IN` -> `in`); null otherwise. */
    fun normalizeCountry(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { COUNTRY.matches(it) }

    /** `Star Jalsha HD.in` -> `Star Jalsha HD`, `CNN.HD.us2` -> `CNN.HD` (guide ids double as names). */
    fun stripCountrySuffix(raw: String): String = COUNTRY_SUFFIX.replace(raw, "")

    /**
     * The provider channel ids in [ch]'s stream URLs, lower case, in stream order: the jmp2.uk
     * redirector carries the id that i.mjh.nz's per-service XMLTV uses as its channel id (Pluto,
     * Spec 38 S1; Plex and Samsung TV Plus, Spec 55 S2). The shapes cannot collide across services.
     */
    fun providerIds(ch: Channel): List<String> =
        ch.streams.mapNotNull { stream -> providerId(stream.url)?.second }.distinct()

    /** The services whose jmp2.uk ids [ch]'s stream URLs carry. */
    fun services(ch: Channel): Set<GuideService> =
        ch.streams.mapNotNullTo(HashSet()) { stream -> providerId(stream.url)?.first }

    /**
     * A stream URL's service and lower-case provider id: `jmp2.uk/plu-<24 hex>`,
     * `jmp2.uk/plex-<24 hex>-<24 hex>`, `jmp2.uk/stvp-<id>` or `jmp2.uk/sam-<id>` (Samsung: 2
     * letters then 6-14 letters or digits); the id must end the path segment. Null for anything
     * else: playlist URLs are untrusted, so only these anchored, bounded shapes count.
     */
    internal fun providerId(url: String): Pair<GuideService, String>? {
        for ((service, pattern) in PROVIDER_URLS) {
            pattern.find(url)?.let { return service to it.groupValues[1].lowercase(Locale.ROOT) }
        }
        return null
    }

    /**
     * Stable key of an M3U item: the normalised tvg-id when it has one (feeds of one channel share
     * it), else the stream URL. Scoped by [sourceId] so two lists never collide.
     */
    fun m3uKey(sourceId: String, tvgId: String?, url: String): String =
        normalizeTvgId(tvgId)?.let { "$sourceId#id:$it" } ?: "$sourceId#url:$url"

    /** Stable key of a Stremio `tv` meta: id plus name, since addons reuse ids (RTVE `tve:RNE.TV`). */
    fun stremioKey(sourceId: String, id: String, name: String): String = "$sourceId#st:$id|${name.trim()}"

    /**
     * The channels of one playlist: items sharing a key become one channel whose streams keep
     * file order (duplicate URLs dropped); name, logo, groups, number and attributes come from the
     * first item. Empty maps are stored as null (no cache bytes for plain lists).
     */
    fun channelsFromM3u(sourceId: String, playlist: M3uPlaylist): List<Channel> {
        val byKey = LinkedHashMap<String, Channel>()
        for (e in playlist.entries) {
            val key = m3uKey(sourceId, e.tvgId, e.url)
            val stream = LiveStream(e.url, e.headers, e.drm, e.kodiProps.ifEmpty { null }, e.tags.ifEmpty { null })
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> Channel(
                    key = key,
                    sourceId = sourceId,
                    name = e.title,
                    tvgId = e.tvgId,
                    country = countryOfTvgId(e.tvgId),
                    logo = e.tvgLogo,
                    groups = e.groups,
                    channelNumber = e.channelNumber,
                    altNames = listOfNotNull(e.tvgName?.takeIf { it != e.title }),
                    streams = listOf(stream),
                    attrs = e.attrs.ifEmpty { null }
                )
                existing.streams.any { it.url == e.url } -> existing
                else -> existing.copy(streams = existing.streams + stream)
            }
        }
        return byKey.values.toList()
    }

    // The trailing `}` must stay escaped: Android 11's ICU-backed java.util.regex rejects a bare
    // `}` outside a class (PatternSyntaxException at first use, a crash on Add broadcasters);
    // the desktop JVM accepts both, so unit tests cannot catch a regression here.
    private val BRACKETED = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*\}""")
    private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|1080p|720p|576p|480p|360p)\b""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}\p{M}_]+""")
    private val COUNTRY = Regex("[a-z]{2}")
    private val COUNTRY_SUFFIX = Regex("""\.[a-zA-Z]{2}\d?$""")
    private val FEED_STEM = Regex("""(.+)\.([a-z]{2})""")
    private val FEED = Regex("""[A-Za-z0-9]{1,32}""")
    private val LOOSE_ID = Regex("""(.+)\.([a-z]{2})\d?""")
    private val BRACKET_CHARS = Regex("""[()\[\]\{\}]""")
    private val PROVIDER_URLS = listOf(
        GuideService.PLUTO to Regex("""^https?://jmp2\.uk/plu-([0-9a-f]{24})(?:[./?#]|$)""", RegexOption.IGNORE_CASE),
        GuideService.PLEX to
            Regex("""^https?://jmp2\.uk/plex-([0-9a-f]{24}-[0-9a-f]{24})(?:[./?#]|$)""", RegexOption.IGNORE_CASE),
        GuideService.SAMSUNG to
            Regex("""^https?://jmp2\.uk/(?:stvp|sam)-([a-z]{2}[a-z0-9]{6,14})(?:[./?#]|$)""", RegexOption.IGNORE_CASE)
    )
}

/** A FAST service whose i.mjh.nz guide keys channels by the id in its jmp2.uk stream URL. */
enum class GuideService { PLUTO, PLEX, SAMSUNG }
