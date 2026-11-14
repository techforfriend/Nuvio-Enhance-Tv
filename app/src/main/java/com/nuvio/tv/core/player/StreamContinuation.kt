package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** The release facts a continuation step compares, read once per stream ([ContinuationTarget.factsOf]). */
data class ContinuationFacts(
    val resolution: ReleaseResolution,
    val languages: Set<String>,
    /** Case-folded, from a parsed source only ([ReleaseFactsParser.parsedReleaseGroupOf], D11); null when none. */
    val releaseGroup: String?
) {
    companion object {
        fun of(stream: Stream): ContinuationFacts {
            val facts = ReleaseFactsParser.factsFor(stream)
            return ContinuationFacts(
                resolution = facts.resolution,
                languages = facts.languages.toSet(),
                releaseGroup = ReleaseFactsParser.parsedReleaseGroupOf(stream)?.lowercase(Locale.ROOT)
            )
        }
    }
}

/**
 * What the playing stream was, for the next episode's pick (Spec 65 P7): built once per press (and
 * once per binge lookahead) from the stream that played ([of]).
 */
data class ContinuationTarget(
    /** The playing binge group, only when it names a release ([BingeGroups.namesRelease]). */
    val bingeGroup: String?,
    val addonName: String,
    /** The playing stream's own name, case-folded; null when it has none or it is only the addon's. */
    val name: String?,
    /**
     * False when the playing group exists but names no release (Spec 47 D2: an AIOStreams gateway's
     * unlabelled rows all share it). Such rows also share their name and resolution, so step 2
     * could not tell them apart either and is skipped.
     */
    val labelled: Boolean,
    val facts: ContinuationFacts
) {
    // Per target (so per press): a candidate's facts are read once across the scrape's emissions.
    // Not part of equality.
    private val memo: MutableMap<Stream, ContinuationFacts> =
        Collections.synchronizedMap(IdentityHashMap())

    internal fun factsOf(stream: Stream): ContinuationFacts = memo.getOrPut(stream) { ContinuationFacts.of(stream) }

    companion object {
        fun of(playing: Stream): ContinuationTarget {
            val group = StreamContinuation.groupOf(playing)
            return ContinuationTarget(
                bingeGroup = group?.takeIf(BingeGroups::namesRelease),
                addonName = playing.addonName,
                name = StreamContinuation.nameOf(playing),
                labelled = StreamContinuation.labelled(playing),
                facts = ContinuationFacts.of(playing)
            )
        }
    }
}

/**
 * Spec 65 P7 (D9, D10): the next episode keeps the source that was playing. Replaces the plain
 * binge-group match on the player's Next / Previous press and its binge lookahead, under the same
 * setting (Prefer binge group), and counts as that match (it plays in MANUAL mode too). Steps,
 * first hit wins:
 * 1. the playing binge group; among its members the same audio languages first (a group can hold
 *    the original and a dub; the old plain match took the group's first member), else its first
 *    member: the addon named the group. A group with no member listed falls through to step 2;
 * 2. the same release (D10, D11): same addon, same parsed release group (both known), same audio
 *    languages, and the candidate itself labelled (not a gateway's bare-group row); among those the
 *    same stream name first, else the same resolution.
 * Step 2 is anchored on the release group because languages cannot tell a dub apart on plain
 * names: the text path reads only MULTI/DUAL, so FRENCH or iTA read as none, like the original
 * (red team `9a795a2d`); and the group only from a parsed source, since a group read from display
 * text matched pack and episode titles (`f2cd86b6`). It needs a labelled playing stream
 * ([ContinuationTarget.labelled]).
 * None = null, and the configured mode decides. [candidates] come in the selector's order
 * (playable, demoted last: recently failed or reported uncached), so a step takes its first in that order; step 1's language
 * preference comes before that recency.
 *
 * After WalliceCariasPerussio/NuvioDesktop `StreamSourceRanking.kt` `selectContinuation()`
 * (`17a5436c3`, GPL-3.0).
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object StreamContinuation {

    fun pick(candidates: List<Stream>, target: ContinuationTarget): Stream? {
        val wanted = target.facts
        target.bingeGroup?.let { group ->
            val members = candidates.filter { groupOf(it) == group }
            if (members.isNotEmpty()) {
                return members.firstOrNull { target.factsOf(it).languages == wanted.languages } ?: members.first()
            }
        }
        if (!target.labelled || target.addonName.isBlank()) return null
        val release = wanted.releaseGroup ?: return null
        val sameRelease = candidates.filter { candidate ->
            candidate.addonName == target.addonName && labelled(candidate) &&
                target.factsOf(candidate).let { it.releaseGroup == release && it.languages == wanted.languages }
        }
        target.name?.let { name -> sameRelease.firstOrNull { nameOf(it) == name }?.let { return it } }
        if (wanted.resolution == ReleaseResolution.UNKNOWN) return null
        return sameRelease.firstOrNull { target.factsOf(it).resolution == wanted.resolution }
    }

    /** No binge group, or one naming a release: not one of a gateway's unlabelled rows (Spec 47 D2). */
    internal fun labelled(stream: Stream): Boolean = groupOf(stream)?.let(BingeGroups::namesRelease) ?: true

    /** [stream]'s binge group, trimmed; null when blank. */
    internal fun groupOf(stream: Stream): String? = stream.behaviorHints?.bingeGroup?.trim()?.takeIf { it.isNotEmpty() }

    /** [stream]'s name, trimmed and case-folded; null when blank or only its addon's name. */
    internal fun nameOf(stream: Stream): String? {
        val name = stream.name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return name.takeIf { it != stream.addonName.trim().lowercase(Locale.ROOT) }
    }
}
