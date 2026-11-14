package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ContentIdentityGuard
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.domain.model.Stream

/**
 * R2: rank during the prefetch instead of after the Play press.
 *
 * Measured 24 Jul 2026 (Xiaomi S905X5M, 4K DV, auto-play "Best quality"):
 * APPLY_SPLIT select= was 398/341/379/367 ms across four runs, 94% of it inside
 * ReleaseFactsParser.factsFor. StreamPrefetchCache already hands the
 * stream list over 2.6-7.6 s before the click and AutoPlaySelection.select is
 * pure, so the whole segment can leave the critical path rather than be shrunk.
 *
 * This file is the safety argument, and it is deliberately pure: no Android, no
 * coroutines, no ViewModel. The gate is the only thing standing between "the
 * pick is the same" and "the pick silently changed", so it is the one part that
 * must be covered by executable assertions rather than by a commit message.
 *
 * Why a snapshot rather than a re-check: the winner is decided by the settings
 * read at rank time. If any of them changed between the prefetch and the press,
 * the cached winner is not what this play would have chosen, so it is discarded
 * and the rank is redone live. Divergence is not detected after the fact, it is
 * structurally excluded.
 *
 * ⚠ [installedAddonOrder] is part of the snapshot even though
 * [AutoPlaySelection.Inputs] already carries installedAddonNames as a Set.
 * StreamAutoPlaySelector.orderAddonStreams consumes the ORDERED list, and
 * StreamQualityRank.rank is a stable sort, so incoming order decides ties. An
 * addon reordered between prefetch and press would change the winner while the
 * Set compared equal.
 *
 * The press's [AutoPlaySelection.Inputs.identity] (Spec 65 S3) is not compared: each site reads
 * the title and year from its own source, so exact equality would miss for no reason. The prefetch
 * ranks without one (its film flag is compared), and the gate keeps the winner only when the
 * press's identity guard passes it. Dropping other candidates never changes the winner of any
 * mode, so that winner is what a live rank with the identity would pick.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */

/** Everything that decides the auto-play winner, captured at rank time. */
data class SelectionSnapshot(
    val inputs: AutoPlaySelection.Inputs,
    val installedAddonOrder: List<String>
)

/**
 * A winner computed during the prefetch, with the snapshot it was computed under
 * and the ordered list it was ranked from.
 */
data class PrefetchedSelection(
    val snapshot: SelectionSnapshot,
    val winner: Stream,
    val candidates: List<Stream>
)

/** Outcome of consulting a prefetched selection at press time. */
sealed interface SelectionOutcome {
    /** Use this stream; it is the instance from the live list, not the cached twin. */
    data class Hit(val stream: Stream) : SelectionOutcome

    /** Rank live. [reason] is logged so a silent fallback cannot read as a win. */
    data class Live(val reason: String) : SelectionOutcome
}

object PrefetchedSelectionGate {

    const val REASON_NO_ENTRY = "no-entry"
    const val REASON_INPUTS_CHANGED = "inputs-changed"
    const val REASON_KEY_MISS = "key-miss"
    const val REASON_CANDIDATES_CHANGED = "candidates-changed"

    /** Spec 51 P5: only the stream list limit's hidden set moved (a runtime or table landed). */
    const val REASON_HIDDEN_CHANGED = "hidden-changed"

    /** Spec 65 S3: the press's identity guard rejects the cached winner. */
    const val REASON_IDENTITY_REJECTED = "identity-rejected"

    /**
     * Resolves a prefetched winner against the list actually being presented.
     *
     * The winner is matched by Stream.limitKey, which is badge-independent:
     * applySuccess badge-merges via stream.copy(badges = ...) before the ranking
     * pass, so the cached winner can be a badge-less twin of the instance in
     * [streams]; data-class equality would miss it, and returning the cached
     * instance would drop badges the UI has already computed. It also tells two
     * services' copies of one torrent file apart (same addon and infoHash,
     * different URL; badgeMergeKey does not), so the first match cannot be a
     * copy the stream list limit hides (Spec 51). The Stream screen's badge
     * merges key on it too.
     */
    fun resolve(
        prefetched: PrefetchedSelection?,
        snapshot: SelectionSnapshot,
        streams: List<Stream>
    ): SelectionOutcome {
        if (prefetched == null) return SelectionOutcome.Live(REASON_NO_ENTRY)
        val sameCandidates = sameCandidates(prefetched.candidates, streams)
        val identity = snapshot.inputs.identity
        val press = snapshot.withoutIdentity()
        if (prefetched.snapshot.withoutIdentity() != press) {
            // The hidden set is computed over the candidates, so a late addon moves both: name
            // the cause the hidden set alone cannot tell apart (logging only, never the pick).
            val hiddenOnly = prefetched.snapshot.withoutIdentity().let {
                it.copy(inputs = it.inputs.copy(hiddenKeys = snapshot.inputs.hiddenKeys))
            } == press
            val reason = when {
                !hiddenOnly -> REASON_INPUTS_CHANGED
                sameCandidates -> REASON_HIDDEN_CHANGED
                else -> REASON_CANDIDATES_CHANGED
            }
            return SelectionOutcome.Live(reason)
        }
        if (!sameCandidates) return SelectionOutcome.Live(REASON_CANDIDATES_CHANGED)
        val wantedKey = prefetched.winner.limitKey()
        val match = streams.firstOrNull { it.limitKey() == wantedKey }
            ?: return SelectionOutcome.Live(REASON_KEY_MISS)
        // The snapshots are equal, so the winner already passed every other candidate filter at
        // the prefetch; only the press's identity is new, and its verdict is per stream.
        if (identity != null && ContentIdentityGuard.rejections(listOf(match), identity).isNotEmpty()) {
            return SelectionOutcome.Live(REASON_IDENTITY_REJECTED)
        }
        return SelectionOutcome.Hit(match)
    }

    /** The snapshot less the press-only identity. */
    private fun SelectionSnapshot.withoutIdentity(): SelectionSnapshot =
        if (inputs.identity == null) this else copy(inputs = inputs.copy(identity = null))

    /**
     * Spec 36/S8 (B1-18, upstream 3d1aad1eb): the winner only holds for the list
     * it was ranked from. A stream that arrived later, left, moved, or changed its
     * cache state or size needs a fresh rank. Badges are added after the rank
     * (applySuccess's badge merge) and do not count.
     */
    private fun sameCandidates(ranked: List<Stream>, live: List<Stream>): Boolean {
        if (ranked.size != live.size) return false
        for (index in ranked.indices) {
            val before = ranked[index]
            val now = live[index]
            if (before === now) continue
            if (before.copy(badges = now.badges) != now) return false
        }
        return true
    }
}
