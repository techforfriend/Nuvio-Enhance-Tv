package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.ChannelEnricher
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.data.EpgMatcher
import com.nuvio.tv.livetv.data.EpgSource
import com.nuvio.tv.livetv.data.IptvMetadata
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A Live TV list filter (Spec 34 D16, Spec 38 Q1). [id] is persisted (the last filter, L8-D3): an
 * id never changes meaning, and the retired pills' ids open as [Browse] values ([fromId]).
 */
sealed interface LiveTvFilter {
    val id: String

    /** The profile's favorites, lost channels included (L8-D4/D5). */
    data object Favorites : LiveTvFilter {
        override val id: String get() = "favorites"
    }

    /** The profile's recently watched channels, newest first, lost channels included (L8-D2/D4). */
    data object Recents : LiveTvFilter {
        override val id: String get() = "recents"
    }

    /**
     * The Country, Category and Language pickers combined (AND); null = All. Values are
     * [ChannelTags] keys: a lower-case country code or [NO_COUNTRY], a lower-case category or
     * [OTHER], an ISO 639-3 language code.
     */
    data class Browse(val country: String? = null, val category: String? = null, val language: String? = null) : LiveTvFilter {
        override val id: String
            get() = if (country == null && category == null && language == null) {
                ALL_ID
            } else {
                BROWSE_PREFIX + listOf(country, category, language).joinToString(SEPARATOR) { v ->
                    v?.let { URLEncoder.encode(it, UTF_8) }.orEmpty()
                }
            }
    }

    companion object {
        /** The Country value of channels without one ("No country", listed last). */
        const val NO_COUNTRY = "none"

        /** The Category value of channels without one (blank or "Undefined" groups; "Other", listed last). */
        const val OTHER = "other"

        /** Every listable channel (the retired All countries pill's id). */
        val ALL = Browse()

        /** All channels (Spec 49 D27 F6; was English): [opening]'s fallback and the filter shown while loading. */
        val initial: Browse = ALL

        private const val ALL_ID = "all"
        private const val BROWSE_PREFIX = "browse:"
        private const val SEPARATOR = "|"
        private const val UTF_8 = "UTF-8"

        /** The fixed pills Spec 38 retired, as the picker values they open as. */
        private val RETIRED = mapOf(
            "sports" to Browse(category = "sports"),
            "english" to Browse(language = "eng"),
            "bengali" to Browse(language = "ben"),
            "canada" to Browse(country = "ca")
        )

        fun fromId(id: String?): LiveTvFilter? = when {
            id == null -> null
            id == Favorites.id -> Favorites
            id == Recents.id -> Recents
            id == ALL_ID -> ALL
            id.startsWith(BROWSE_PREFIX) -> parseBrowse(id.removePrefix(BROWSE_PREFIX))
            else -> RETIRED[id]
        }

        private fun parseBrowse(body: String): Browse? {
            val parts = body.split(SEPARATOR)
            if (parts.size != 3) return null
            val values = parts.map { part ->
                if (part.isEmpty()) null else runCatching { URLDecoder.decode(part, UTF_8) }.getOrNull() ?: return null
            }
            return Browse(values[0], values[1], values[2])
        }

        /** The full-screen list's chips (Spec 38 stage 7): Favorites, Recents, the pickers' [browse]. */
        fun chips(browse: Browse): List<LiveTvFilter> = listOf(Favorites, Recents, browse)

        /** Q1 (L8-D3): the saved filter, else Favorites when one is listed, else [initial] (All). */
        fun opening(savedId: String?, hasFavorites: Boolean): LiveTvFilter =
            fromId(savedId) ?: if (hasFavorites) Favorites else initial
    }
}

/**
 * How Live TV lays out the shown filter (Spec 67 D7 Q1, Q8): the channel list with the preview, or the
 * guide grid at 44 dp ([GUIDE]) or 32 dp rows ([COMPACT]). [id] is persisted per profile and never
 * changes meaning; an unknown or missing id opens [LIST].
 */
enum class LiveTvView(val id: String) {
    LIST("list"),
    GUIDE("guide"),
    COMPACT("compact");

    val isGrid: Boolean get() = this != LIST

    companion object {
        fun fromId(id: String?): LiveTvView = entries.firstOrNull { it.id == id } ?: LIST
    }
}

/**
 * A channel's picker values (Spec 38 Q1): its [country] ([LiveTvFilter.NO_COUNTRY] when none), its
 * [categories] (iptv-org's, else the playlist's groups; [LiveTvFilter.OTHER] when none), its
 * [languages] (iptv-org's; may be empty: such a channel is listed under All languages only).
 */
data class ChannelTags(val country: String, val categories: Set<String>, val languages: Set<String>)

/** One picker option: [key] null = All; [count] = the channels it lists under the other pickers. */
data class PickerValue(val key: String?, val count: Int)

/**
 * One picker's options: [pinned] = the values of the profile's Favorites and Recents ("Your
 * channels", first seen first), then [values]: All, the rest by count, the fold value (No country,
 * Other) last. Values with no channel under the other pickers are left out.
 */
data class Picker(val pinned: List<PickerValue>, val values: List<PickerValue>)

data class Pickers(val country: Picker, val category: Picker, val language: Picker)

/**
 * The listable channels in number order ([channels]), the same plus lost channels ([withLost],
 * number order; Favorites and Recents list those, L8-D4), every channel's number (Q6), the picker
 * values of every [withLost] channel by key ([tags]), and each category key's label as first
 * written ([categoryLabels]: an iptv-org id capitalised, a playlist group as written).
 */
data class Lineup(
    val channels: List<Channel>,
    val numbers: Map<String, String>,
    val withLost: List<Channel>,
    val tags: Map<String, ChannelTags>,
    val categoryLabels: Map<String, String>
) {
    /** Channel [key], listable or lost, if any (linear: per focus move, not per row). */
    fun channel(key: String): Channel? = withLost.firstOrNull { it.key == key }
}

/** The profile's lists resolved against a lineup (L8-D5): Favorites in number order, Recents newest first. */
data class UserLists(val favorites: List<Channel>, val recents: List<Channel>) {
    val favoriteKeys: Set<String> = favorites.mapTo(HashSet()) { it.key }

    companion object {
        val EMPTY = UserLists(emptyList(), emptyList())
    }
}

/**
 * One list row (D16): [hasGuide] false = "No guide for this channel"; now/next null when the guide
 * is silent now. [progress] (0..1) is how far [now] has run. Without a current programme the row
 * reads "[category] · country" ([LiveTvModel.facts] words both).
 */
data class LiveRow(
    val channel: Channel,
    val number: String,
    val now: EpgProgramme?,
    val next: EpgProgramme?,
    val hasGuide: Boolean,
    val progress: Float?,
    val favorite: Boolean = false
) {
    /** The LIVE badge: the current programme is broadcast live (XMLTV `<live/>`). */
    val live: Boolean get() = now?.live == true

    /** Its source stopped listing it (D16 "Source lost", L8-D4): shown dimmed, never tuned. */
    val lost: Boolean get() = channel.lost

    /**
     * Right opens its day schedule (Spec 67 D3 Q1): a guide covers it and has something on now or
     * next, so the schedule never opens empty. Never on a lost row.
     */
    val hasSchedule: Boolean get() = hasGuide && !lost && (now != null || next != null)

    /** The first category as worded ([LiveTvModel.categoryNames]: iptv-org's, else the playlist's). */
    val category: String? get() = LiveTvModel.categoryNames(channel).firstOrNull()
}

/** The name rows, preview, info and OSD show ([LiveTvModel.displayName]); matching keeps [Channel.name]. */
internal val Channel.shownName: String get() = LiveTvModel.displayName(name)

/** Pure Live TV screen rules (Spec 34 L7a): numbering, filters, guide matching, rows. */
object LiveTvModel {

    /**
     * Q6: a channel's number is its `tvg-chno` when set, else its position over all channels
     * (broadcaster preset first, then each source in source order, the source's own order within),
     * so numbers do not change with the filter, hiding or NSFW. The listable channels (enriched,
     * not lost, not NSFW, not hidden) keep that order, and so do lost ones ([Lineup.withLost]).
     */
    fun lineup(
        all: List<Channel>,
        sourceIds: List<String>,
        meta: IptvMetadata?,
        hidden: Set<String>
    ): Lineup {
        val sourceOrder = (listOf(LiveSourceManager.PRESET_ID) + sourceIds)
            .distinct()
            .withIndex()
            .associate { it.value to it.index }
        val ordered = all.sortedBy { sourceOrder[it.sourceId] ?: Int.MAX_VALUE }
        val numbers = HashMap<String, String>(ordered.size * 2)
        ordered.forEachIndexed { i, ch ->
            numbers[ch.key] = ch.channelNumber?.trim()?.ifEmpty { null } ?: (i + 1).toString()
        }
        val withLost = ChannelEnricher.enrich(ordered, meta).filter { !it.nsfw && it.key !in hidden }
        val listable = ChannelEnricher.listable(withLost)
        val tagsByKey = HashMap<String, ChannelTags>(withLost.size * 2)
        val labels = HashMap<String, String>()
        for (ch in withLost) {
            val names = categoryNames(ch)
            tagsByKey[ch.key] = tags(ch, names)
            for (c in names) labels.putIfAbsent(c.lowercase(Locale.ROOT), c)
        }
        return Lineup(listable, numbers, withLost, tagsByKey, labels)
    }

    /** [ch]'s picker values (enriched input); [names] = its [categoryNames]. */
    fun tags(ch: Channel, names: List<String> = categoryNames(ch)): ChannelTags = ChannelTags(
        country = ChannelIdentity.normalizeCountry(ch.country) ?: LiveTvFilter.NO_COUNTRY,
        categories = names.mapTo(LinkedHashSet()) { it.lowercase(Locale.ROOT) }
            .ifEmpty { setOf(LiveTvFilter.OTHER) },
        languages = ch.languages.mapTo(LinkedHashSet()) { it.lowercase(Locale.ROOT) }
    )

    /**
     * [ch]'s categories as worded: iptv-org's (`news` -> "News"), else its playlist groups (already
     * split on `;`) minus blank and "Undefined". One home for rows ([LiveRow.category]) and pickers.
     */
    fun categoryNames(ch: Channel): List<String> =
        if (ch.categories.isNotEmpty()) {
            ch.categories.map { c -> c.replaceFirstChar(Char::uppercaseChar) }
        } else {
            ch.groups.map(String::trim).filter { it.isNotEmpty() && !it.equals(UNDEFINED_GROUP, ignoreCase = true) }
        }

    private const val UNDEFINED_GROUP = "Undefined"

    fun matches(browse: LiveTvFilter.Browse, tags: ChannelTags): Boolean =
        (browse.country == null || browse.country == tags.country) &&
            (browse.category == null || browse.category in tags.categories) &&
            (browse.language == null || browse.language in tags.languages)

    /** One picker's dimension: its values on a channel and the [LiveTvFilter.Browse] without it. */
    private enum class Facet(val fold: String?) {
        COUNTRY(LiveTvFilter.NO_COUNTRY), CATEGORY(LiveTvFilter.OTHER), LANGUAGE(null);

        fun values(tags: ChannelTags): Collection<String> = when (this) {
            COUNTRY -> listOf(tags.country)
            CATEGORY -> tags.categories
            LANGUAGE -> tags.languages
        }

        fun without(browse: LiveTvFilter.Browse): LiveTvFilter.Browse = when (this) {
            COUNTRY -> browse.copy(country = null)
            CATEGORY -> browse.copy(category = null)
            LANGUAGE -> browse.copy(language = null)
        }
    }

    /**
     * The three pickers' options for [browse] (Spec 38 Q1): each picker counts the listable channels
     * its two neighbours let through (faceted), so any value it lists gives a non-empty list.
     */
    fun pickers(lineup: Lineup, browse: LiveTvFilter.Browse, user: UserLists): Pickers {
        val listed = lineup.channels.mapNotNull { lineup.tags[it.key] }
        val mine = (user.favorites + user.recents).mapNotNull { lineup.tags[it.key] }
        fun picker(facet: Facet): Picker {
            val scope = facet.without(browse)
            val counts = HashMap<String, Int>()
            var all = 0
            for (t in listed) {
                if (!matches(scope, t)) continue
                all++
                for (v in facet.values(t)) counts.merge(v, 1, Int::plus)
            }
            val pinned = mine.flatMap(facet::values).distinct().filter { it != facet.fold && it in counts }
            val rest = (counts.keys - pinned.toSet()).sortedWith(
                compareBy<String> { it == facet.fold }.thenByDescending { counts.getValue(it) }.thenBy { it }
            )
            return Picker(
                pinned = pinned.map { PickerValue(it, counts.getValue(it)) },
                values = listOf(PickerValue(null, all)) + rest.map { PickerValue(it, counts.getValue(it)) }
            )
        }
        return Pickers(picker(Facet.COUNTRY), picker(Facet.CATEGORY), picker(Facet.LANGUAGE))
    }

    /**
     * The profile's [favorites] and [recents] (keys, newest first) that [lineup] still holds, lost
     * channels included (hidden and pruned ones drop out; L8-D4/D5).
     */
    fun userLists(lineup: Lineup, favorites: Collection<String>, recents: List<String>): UserLists {
        val favoriteKeys = favorites.toHashSet()
        val byKey = lineup.withLost.associateBy { it.key }
        return UserLists(
            favorites = lineup.withLost.filter { it.key in favoriteKeys },
            recents = recents.distinct().mapNotNull(byKey::get)
        )
    }

    /** Guide sources in source order, id -> country: all [guideRefs] needs of the source list. */
    fun guideCountries(sources: List<LiveSource>): List<Pair<String, String?>> =
        sources.filter { it.kind == SourceKind.XMLTV }.map { it.id to it.country }

    /** The guide each listable channel uses (D9 chain over every cached guide source). */
    fun guideRefs(
        channels: List<Channel>,
        guideCountries: List<Pair<String, String?>>,
        guides: Map<String, EpgSnapshot>,
        overrides: Map<String, EpgOverride>
    ): Map<String, EpgRef> = EpgMatcher.match(channels, epgSources(guideCountries, guides), overrides)

    /** The cached guides as match input, in source order (a guide not cached yet is left out). */
    fun epgSources(guideCountries: List<Pair<String, String?>>, guides: Map<String, EpgSnapshot>): List<EpgSource> =
        guideCountries.mapNotNull { (id, country) -> guides[id]?.let { EpgSource(id, country, it) } }

    fun filtered(lineup: Lineup, filter: LiveTvFilter, user: UserLists): List<Channel> = when (filter) {
        LiveTvFilter.Favorites -> user.favorites
        LiveTvFilter.Recents -> user.recents
        LiveTvFilter.ALL -> lineup.channels
        is LiveTvFilter.Browse -> lineup.channels.filter { ch -> lineup.tags[ch.key]?.let { matches(filter, it) } == true }
    }

    /** The Favorites and Recents pills' counts (the pickers count in [pickers]). */
    fun counts(user: UserLists): Map<LiveTvFilter, Int> =
        mapOf(LiveTvFilter.Favorites to user.favorites.size, LiveTvFilter.Recents to user.recents.size)

    /**
     * A channel name as rows show it (Spec 38 Q2'): without iptv-org's bracketed labels
     * (`[Geo-blocked]`, `[Not 24/7]`) and quality tags (`(720p)`, `(1080i)`, `(HD)`); the raw name
     * stays for matching. A name that is nothing but tags is kept as written.
     */
    fun displayName(name: String): String =
        name.replace(NAME_TAGS, " ").replace(SPACES, " ").trim().ifEmpty { name }

    // Every bracket escaped: Android's ICU regex is stricter than the JVM's (ke34 crash).
    private val NAME_TAGS = Regex("""\[[^\]]*\]|\((?:\d{3,4}[pi]|4K|UHD|FHD|HD|SD)\)""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    /**
     * Zapping (Q3) and Next channel: the row [step] (+1 down the list, -1 up) from [key], wrapping,
     * passing over rows [skip] rejects (D16: unavailable or cooling channels). [key] not listed:
     * the first row going down, the last going up. Null when no other row qualifies.
     */
    fun zapRow(rows: List<LiveRow>, key: String?, step: Int, skip: (Channel) -> Boolean = { false }): LiveRow? {
        val n = rows.size
        val from = rows.indexOfFirst { it.channel.key == key }
        // Not listed: start just outside the list, so the first step lands on its first (or last) row.
        val start = if (from >= 0) from else if (step > 0) -1 else n
        val candidates = if (from >= 0) n - 1 else n
        for (k in 1..candidates) {
            val row = rows[Math.floorMod(start + step * k, n)]
            if (!skip(row.channel)) return row
        }
        return null
    }

    /** The row's category worded (D16): an iptv-org category id (`news` -> "News"), a playlist group as written. */
    fun categoryLabel(row: LiveRow): String? = row.category

    /** The row's "category · country" facts (D16); the country code as its English name (iptv-org's `uk` = GB). */
    fun facts(row: LiveRow): List<String> = listOfNotNull(categoryLabel(row), row.channel.country?.let(::countryName))

    /**
     * The tile's monogram when there is no logo: the initials of up to three words that start with
     * a letter ("Al Jazeera English" -> "AJE"), else the first three letters of the one word, else
     * (no word starts with a letter) the name's first two characters.
     */
    fun monogram(name: String): String {
        val words = name.split(NON_WORD).filter { it.firstOrNull()?.isLetter() == true }
        val mono = when {
            words.size >= 2 -> words.take(3).joinToString("") { it.take(1) }
            words.size == 1 -> words[0].take(3)
            else -> name.trim().take(2)
        }
        return mono.uppercase(Locale.ROOT)
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** An iptv-org country code in English ("in" -> "India"; its `uk` is ISO `GB`); unknown codes as given, upper-cased. */
    fun countryName(code: String): String {
        val iso = if (code.equals("uk", ignoreCase = true)) "GB" else code.uppercase(Locale.ROOT)
        val locale = runCatching { Locale.Builder().setRegion(iso).build() }.getOrNull() ?: return iso
        return locale.getDisplayCountry(Locale.ENGLISH).ifBlank { iso }
    }

    /**
     * An ISO 639-3 code in English ("ben" -> "Bengali", "fra" -> "French"); unknown codes as given.
     * Goes through the two-letter code where one exists: the JVM names `fra` or `zho` only that way.
     */
    fun languageName(code: String): String {
        val lower = code.lowercase(Locale.ROOT)
        val tag = ISO3_TO_ISO2[lower] ?: lower
        DISPLAY_OVERRIDES[tag]?.let { return it }
        val locale = runCatching { Locale.Builder().setLanguage(tag).build() }.getOrNull() ?: return code
        return locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != tag } ?: code
    }

    // CLDR says "Bangla"; the approved mockup and the retired pill say Bengali.
    private val DISPLAY_OVERRIDES = mapOf("bn" to "Bengali")

    private val ISO3_TO_ISO2: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { two ->
            runCatching { Locale.Builder().setLanguage(two).build().isO3Language }.getOrNull()?.let { it to two }
        }.toMap()
    }

    /**
     * Rows for [channels] at [nowMs]; a ref to a guide no longer cached reads as no guide. Lost
     * channels are never matched ([guideRefs] takes the listable ones), so they carry no programme.
     */
    fun rows(
        channels: List<Channel>,
        numbers: Map<String, String>,
        refs: Map<String, EpgRef>,
        guides: Map<String, EpgSnapshot>,
        nowMs: Long,
        favorites: Set<String> = emptySet()
    ): List<LiveRow> = channels.map { ch ->
        val ref = refs[ch.key]
        val snapshot = ref?.let { guides[it.epgSourceId] }
        val (now, next) = snapshot?.nowNext(ref.epgChannelId, nowMs) ?: (null to null)
        val progress = now?.let { p ->
            ((nowMs - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        LiveRow(
            ch, numbers[ch.key].orEmpty(), now, next,
            hasGuide = snapshot != null, progress = progress, favorite = ch.key in favorites
        )
    }
}
