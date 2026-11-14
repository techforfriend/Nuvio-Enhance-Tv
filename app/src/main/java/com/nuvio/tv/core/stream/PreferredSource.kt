package com.nuvio.tv.core.stream

import com.nuvio.tv.core.player.BingeGroups
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The source a viewer prefers for a show (Spec 65 P6, D16, D20), taken from the row they held OK
 * on ([of]) and kept per show by PreferredSourceDataStore. A row matches ([matches]) on the same
 * addon and resolution, and on the release when the preferred row named one: its binge group
 * ([bingeGroup], only one that names a release, Spec 47 D2) and its parsed release group
 * ([releaseGroup], D11: parsed sources only, never display text), each when stored (a binge group
 * may name only the quality, and the menu names the release group). A row that named neither is
 * preferred as "this addon at this resolution". A ranking bonus, never a lock: with no match the
 * list and auto-play rank as usual.
 *
 * The field names and [ReleaseResolution]'s names are the stored format.
 */
@Serializable
data class PreferredSource(
    val addonName: String,
    val resolution: ReleaseResolution,
    val bingeGroup: String? = null,
    /** Case-folded. */
    val releaseGroup: String? = null,
    /** The preferred row's audio languages, sorted: among several matches of one release these go first. */
    val languages: List<String> = emptyList()
) {
    /** The preference names a release, not only an addon and a resolution. */
    private val anchored: Boolean get() = bingeGroup != null || releaseGroup != null

    /** Cheapest checks first: a list derive asks this of every shown row. */
    fun matches(stream: Stream): Boolean {
        if (stream.addonName != addonName) return false
        if (bingeGroup != null && stream.behaviorHints?.bingeGroup?.trim() != bingeGroup) return false
        if (releaseGroup != null && releaseGroupOf(stream) != releaseGroup) return false
        return ReleaseFactsParser.resolutionOf(stream) == resolution
    }

    /**
     * The rows of [streams] that match, in the order the list shows them and auto-play takes its
     * first from: list order, and when the preference names a release, the rows with the
     * preferred row's languages before the others (one release can list the original and a dub).
     */
    fun matchesIn(streams: List<Stream>): List<Stream> = ordered(streams.filter(::matches))

    /** [streams] with the matching rows first ([matchesIn]'s order), the rest as they were; and how many match. */
    fun preferredFirst(streams: List<Stream>): Pair<List<Stream>, Int> {
        val (matching, rest) = streams.partition(::matches)
        return if (matching.isEmpty()) streams to 0 else (ordered(matching) + rest) to matching.size
    }

    private fun ordered(matching: List<Stream>): List<Stream> {
        if (matching.size < 2 || !anchored) return matching
        val (same, other) = matching.partition { languagesOf(it) == languages }
        return if (same.isEmpty() || other.isEmpty()) matching else same + other
    }

    companion object {
        /** What preferring [stream] stores. */
        fun of(stream: Stream): PreferredSource = PreferredSource(
            addonName = stream.addonName,
            resolution = ReleaseFactsParser.resolutionOf(stream),
            bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.takeIf(BingeGroups::namesRelease),
            releaseGroup = releaseGroupOf(stream),
            languages = languagesOf(stream)
        )

        private fun releaseGroupOf(stream: Stream): String? =
            ReleaseFactsParser.parsedReleaseGroupOf(stream)?.lowercase(Locale.ROOT)

        private fun languagesOf(stream: Stream): List<String> =
            ReleaseFactsParser.factsFor(stream).languages.distinct().sorted()
    }
}
