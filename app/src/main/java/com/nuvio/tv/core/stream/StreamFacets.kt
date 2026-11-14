package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.ReleaseAudioTag
import com.nuvio.tv.domain.model.ReleaseEncode
import com.nuvio.tv.domain.model.ReleaseQuality
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import kotlinx.serialization.Serializable

/*
 * The Stream screen's filters (Spec 65 P1, D12, D17-D18): five groups read from the release facts
 * the parser already gives every row, OR within a group, AND across groups. View-only: the list
 * and its counts narrow, auto-play never reads a filter (D18).
 */

/** A filter group, in the sheet's order. Its names are stored by saved filters (SavedStreamFilters). */
@Serializable
enum class FacetGroup { RESOLUTION, HDR, AUDIO, SOURCE, CODEC }

/**
 * One value of a group, by key: the parsed enum's name (ReleaseResolution, ReleaseQuality,
 * ReleaseAudioTag, ReleaseEncode), for HDR one of [FacetKeys.DV] / [FacetKeys.HDR] /
 * [FacetKeys.SDR], or [FacetKeys.UNKNOWN] when the row's text does not say (D17). Keys are strings
 * so one filter type spans five enums and the HDR keys.
 */
data class FacetValue(val group: FacetGroup, val key: String)

object FacetKeys {
    const val UNKNOWN = "UNKNOWN"
    const val DV = "DV"
    /** Any HDR format the parser names (HDR10, HDR10+, HLG, plain HDR), its HDR_FAMILY. */
    const val HDR = "HDR"
    const val SDR = "SDR"
}

/** A row's keys per group; every group has at least one key ([FacetKeys.UNKNOWN] when unparsed). */
typealias StreamFacetValues = Map<FacetGroup, Set<String>>

/**
 * [stream]'s keys per group, from [ReleaseFactsParser.factsFor]. A row naming no HDR format is SDR
 * (release names state HDR, never SDR), so HDR has no unknown key.
 */
fun streamFacetValues(stream: Stream): StreamFacetValues {
    val facts = ReleaseFactsParser.factsFor(stream)
    fun <E : Enum<E>> keysOf(values: Collection<E>, unknown: E): Set<String> =
        values.filter { it != unknown }.mapTo(LinkedHashSet()) { it.name }.ifEmpty { setOf(FacetKeys.UNKNOWN) }
    val hdr = buildSet {
        if (facts.visualTags.any { it in ReleaseFactsParser.DV_FAMILY }) add(FacetKeys.DV)
        if (facts.visualTags.any { it in ReleaseFactsParser.HDR_FAMILY }) add(FacetKeys.HDR)
    }.ifEmpty { setOf(FacetKeys.SDR) }
    return mapOf(
        FacetGroup.RESOLUTION to keysOf(listOf(facts.resolution), ReleaseResolution.UNKNOWN),
        FacetGroup.HDR to hdr,
        FacetGroup.AUDIO to keysOf(facts.audioTags, ReleaseAudioTag.UNKNOWN),
        FacetGroup.SOURCE to keysOf(listOf(facts.quality), ReleaseQuality.UNKNOWN),
        FacetGroup.CODEC to keysOf(listOf(facts.encode), ReleaseEncode.UNKNOWN)
    )
}

/**
 * [streamFacetValues] kept across derives (Spec 65 S7): under a filter every list update reads
 * each row's values again, and so does the filters sheet at each switch. Keyed by the stream
 * itself, so a row that a badge pass rewrites is read again; bounded, least recently used out
 * first. Read from the main thread (a derive) and a worker (the sheet).
 */
object StreamFacetMemo {
    private const val CAPACITY = 2000
    private val values = object : LinkedHashMap<Stream, StreamFacetValues>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stream, StreamFacetValues>): Boolean =
            size > CAPACITY
    }

    @Synchronized
    fun valuesOf(stream: Stream): StreamFacetValues =
        values[stream] ?: streamFacetValues(stream).also { values[stream] = it }
}

/**
 * How the sheet and the eyebrow name [value]: the parsed enum's label, "4K" for 2160p, "Remux",
 * "Dolby Vision"; null for [FacetKeys.UNKNOWN] and a key no enum has (the caller's "Unknown").
 */
fun facetValueLabel(value: FacetValue): String? {
    val key = value.key
    if (key == FacetKeys.UNKNOWN) return null
    return when (value.group) {
        FacetGroup.RESOLUTION -> ReleaseResolution.entries.firstOrNull { it.name == key }?.let(::resolutionLabel)
        FacetGroup.HDR -> when (key) {
            FacetKeys.DV -> "Dolby Vision"
            FacetKeys.HDR -> "HDR"
            FacetKeys.SDR -> "SDR"
            else -> null
        }
        FacetGroup.AUDIO -> ReleaseAudioTag.entries.firstOrNull { it.name == key }?.label
        FacetGroup.SOURCE -> when (key) {
            ReleaseQuality.BLURAY_REMUX.name -> "Remux"
            else -> ReleaseQuality.entries.firstOrNull { it.name == key }?.label
        }
        FacetGroup.CODEC -> ReleaseEncode.entries.firstOrNull { it.name == key }?.label
    }
}

/** The values switched on, per group. Empty = no filter. */
data class FacetFilter(val on: Map<FacetGroup, Set<String>> = emptyMap()) {

    val isEmpty: Boolean get() = on.values.all { it.isEmpty() }

    /** Values switched on, the filters circle's badge. */
    val count: Int get() = on.values.sumOf { it.size }

    /** OR within a group, AND across groups. */
    fun matches(values: StreamFacetValues): Boolean =
        on.all { (group, keys) -> keys.isEmpty() || values[group].orEmpty().any { it in keys } }

    /** This filter with [value] switched (on to off, off to on). */
    fun toggled(value: FacetValue): FacetFilter {
        val keys = on[value.group].orEmpty()
        val next = if (value.key in keys) keys - value.key else keys + value.key
        return FacetFilter((on - value.group).let { if (next.isEmpty()) it else it + (value.group to next) })
    }

    /** The values on, in the sheet's order (groups, then each group's own order). */
    val values: List<FacetValue>
        get() = FacetGroup.entries.flatMap { group ->
            on[group].orEmpty().sortedBy { sheetRank(group, it) }.map { FacetValue(group, it) }
        }

    /** This filter without [group]'s choices: what a value's count in that group is taken under. */
    fun without(group: FacetGroup): FacetFilter = FacetFilter(on - group)

    companion object {
        val NONE = FacetFilter()
    }
}

/** A value the sheet offers: its key, how many rows it would list, and whether it is on. */
data class FacetOption(val value: FacetValue, val count: Int, val on: Boolean)

/** Sheet order inside a group; HDR's three keys first-to-last. */
private val ORDER: Map<FacetGroup, List<String>> = mapOf(
    FacetGroup.RESOLUTION to ReleaseResolution.defaultOrder.map { it.name },
    FacetGroup.HDR to listOf(FacetKeys.DV, FacetKeys.HDR, FacetKeys.SDR),
    FacetGroup.AUDIO to listOf(ReleaseAudioTag.ATMOS).plus(ReleaseAudioTag.defaultOrder - ReleaseAudioTag.ATMOS).map { it.name },
    FacetGroup.SOURCE to ReleaseQuality.defaultOrder.map { it.name },
    FacetGroup.CODEC to ReleaseEncode.defaultOrder.map { it.name }
)

/** A key's place in its group: the known order, then keys outside it, then [FacetKeys.UNKNOWN] last. */
private fun sheetRank(group: FacetGroup, key: String): Int {
    if (key == FacetKeys.UNKNOWN) return Int.MAX_VALUE
    val order = ORDER.getValue(group)
    return order.indexOf(key).takeIf { it >= 0 } ?: order.size
}

/**
 * The sheet's values per group over [rows] (one view's rows before the filter): every key some row
 * carries, plus any key [filter] has on (so it can be switched off when no row has it any more). A
 * value's count is the rows it would list under the other groups' choices, the usual facet count:
 * a dimmed 0 means adding it lists nothing. A group offering a single value and none on is left
 * out: it would filter nothing (every row 1080p, or no row naming a codec).
 */
fun facetOptions(
    rows: List<Stream>,
    filter: FacetFilter,
    facetsOf: (Stream) -> StreamFacetValues
): Map<FacetGroup, List<FacetOption>> {
    val values = rows.map(facetsOf)
    return FacetGroup.entries.associateWith { group ->
        val others = filter.without(group)
        val underOthers = values.filter(others::matches)
        val counts = HashMap<String, Int>()
        underOthers.forEach { row -> row[group].orEmpty().forEach { counts.merge(it, 1, Int::plus) } }
        val keys = values.flatMapTo(LinkedHashSet()) { it[group].orEmpty() } + filter.on[group].orEmpty()
        keys.sortedBy { sheetRank(group, it) }
            .map { key -> FacetOption(FacetValue(group, key), counts[key] ?: 0, key in filter.on[group].orEmpty()) }
    }.filterValues { options -> options.size > 1 || options.any { it.on } }
}
