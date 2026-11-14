package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgProgramme

/** One guide channel the user may pick for a channel (D16 Choose guide): [now] is its current programme. */
data class GuideCandidate(val ref: EpgRef, val name: String, val now: EpgProgramme?)

/**
 * Choose guide's list (Spec 34 L7d), pure: guide channels from every cached guide, closest names
 * first. Closeness per channel name (title, then [Channel.altNames]) against each guide channel's
 * display names and id stem: an id the matcher accepts (a [ChannelIdentity.tvgIdKeys] key, or its
 * [ChannelIdentity.looseId]) or the same normalised name is exact; one normalised name
 * inside the other (3+ characters) next, by length ratio; else the share of words in common
 * ("Al Jazeera English" vs "Al Jazeera Arabic"), filler words (`tv`, `channel`) aside. Nothing in
 * common = not a candidate. No country gate (the user is choosing), but on a tie a guide of the
 * channel's country comes first, then the current guide, then a guide channel with programmes in
 * its window, then guide and file order. A name a guide lists several times (free-epg.de) shows once.
 */
object GuideCandidates {

    const val MAX = 12

    private const val EXACT = 3.0
    private const val CONTAINS = 2.0
    private const val MIN_CONTAINED = 3
    private val FILLER = setOf("tv", "channel", "the", "and")

    /**
     * Up to [limit] candidates for [channel] over [sources] (guide order) at [nowMs]. [current] (the
     * channel's guide now) is always listed: first when it would not make the cut otherwise.
     */
    fun rank(
        channel: Channel,
        sources: List<EpgSource>,
        current: EpgRef?,
        nowMs: Long,
        limit: Int = MAX
    ): List<GuideCandidate> {
        val idKeys = ChannelIdentity.tvgIdKeys(channel.tvgId).toSet()
        val looseKeys = idKeys.mapNotNullTo(HashSet(), ChannelIdentity::looseId)
        val country = ChannelIdentity.normalizeCountry(channel.country)
        val mine = (listOf(channel.name) + channel.altNames).map(::Name).filter { it.joined.isNotEmpty() }
        val scored = ArrayList<Scored>()
        for (source in sources) {
            val sameCountry = country != null && ChannelIdentity.normalizeCountry(source.country) == country
            for (epg in source.snapshot.channels.values) {
                val theirs = (epg.displayNames + epg.id).map { Name(ChannelIdentity.stripCountrySuffix(it)) }
                val score = if (idHit(epg.id, idKeys, looseKeys)) EXACT
                else mine.maxOfOrNull { a -> theirs.maxOfOrNull { b -> closeness(a, b) } ?: 0.0 } ?: 0.0
                if (score <= 0.0) continue
                val name = epg.displayNames.firstOrNull { it.isNotBlank() } ?: epg.id
                val live = source.snapshot.programmes[epg.id].orEmpty().isNotEmpty()
                scored += Scored(EpgRef(source.id, epg.id), name, score, sameCountry, live)
            }
        }
        val ranked = scored
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.sameCountry }
                    // The current guide wins its name's dedupe below.
                    .thenByDescending { it.ref == current }
                    .thenByDescending { it.live }
            )
            .distinctBy { it.ref.epgSourceId to ChannelIdentity.normalizeName(it.name) }
            .take(limit)
            .map { it.ref }
        val refs = if (current == null || current in ranked || !exists(current, sources)) ranked
        else listOf(current) + ranked.take(limit - 1)
        return refs.map { ref -> candidate(ref, sources, nowMs) }
    }

    /**
     * Whether the matcher's tvg-id step would accept [epgId] for these tvg-id keys and their loose
     * forms. Every key scores the same: a feed and its plain id tie (`Dave.+1.uk` / `Dave.uk`), and
     * the current guide, which is the matcher's most specific hit, wins the tie.
     */
    private fun idHit(epgId: String, idKeys: Set<String>, looseKeys: Set<String>): Boolean {
        val id = ChannelIdentity.normalizeTvgId(epgId) ?: return false
        if (id in idKeys) return true
        if (looseKeys.isEmpty()) return false
        return ChannelIdentity.looseId(epgId)?.let { it in looseKeys } == true
    }

    private fun closeness(a: Name, b: Name): Double {
        if (b.joined.isEmpty()) return 0.0
        if (a.joined == b.joined) return EXACT
        val (short, long) = if (a.joined.length <= b.joined.length) a.joined to b.joined else b.joined to a.joined
        if (short.length >= MIN_CONTAINED && short in long) return CONTAINS + short.length.toDouble() / long.length
        val common = a.words.intersect(b.words).size
        return if (common == 0) 0.0 else common.toDouble() / a.words.union(b.words).size
    }

    private fun exists(ref: EpgRef, sources: List<EpgSource>): Boolean =
        sources.any { it.id == ref.epgSourceId && ref.epgChannelId in it.snapshot.channels }

    private fun candidate(ref: EpgRef, sources: List<EpgSource>, nowMs: Long): GuideCandidate {
        val snapshot = sources.first { it.id == ref.epgSourceId }.snapshot
        val epg = snapshot.channels.getValue(ref.epgChannelId)
        val name = epg.displayNames.firstOrNull { it.isNotBlank() } ?: epg.id
        return GuideCandidate(ref, name, snapshot.nowNext(ref.epgChannelId, nowMs).first)
    }

    private class Name(raw: String) {
        private val all = ChannelIdentity.nameWords(raw)
        val joined = all.joinToString("")
        val words = all.filterNot { it in FILLER }.toSet()
    }

    private class Scored(val ref: EpgRef, val name: String, val score: Double, val sameCountry: Boolean, val live: Boolean)
}
