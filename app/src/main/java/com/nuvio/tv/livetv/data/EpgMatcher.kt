package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgSnapshot

/** One parsed EPG source: [country] is the file's country (epg.pw `epg_IN` -> `in`), null if unknown. */
data class EpgSource(val id: String, val country: String?, val snapshot: EpgSnapshot)

/**
 * The Spec 34 D9 match chain, per channel:
 * 1. a persisted user override wins outright;
 * 2. a provider id in a stream URL ([ChannelIdentity.providerIds]: Pluto TV, Plex, Samsung TV
 *    Plus) equals an XMLTV channel id (any country; i.mjh.nz's service guides key channels by
 *    that id, Spec 38 S1, Spec 55 S2);
 * 3. per tvg-id key, most specific first ([ChannelIdentity.tvgIdKeys]: stem + feed, then the
 *    plain id): the key equals a normalised XMLTV channel id, else its
 *    [ChannelIdentity.looseId] equals an XMLTV id's (epgshare01's `BBC.Two.HD.uk` /
 *    `CNN.HD.us2`, Spec 55 S3), each in any country (the loose id keeps the country tag), so
 *    `Dave.uk@Plus1` takes `Dave.+1.uk` before the plain `Dave.uk`;
 * 4. normalised channel name (title, then [Channel.altNames]) equals a normalised XMLTV
 *    display-name or id stem, accepted only when the channel's country equals the EPG source's
 *    country (without the gate NTV.bd got India's NTV and US Discovery got Indian schedules).
 * Steps 2-4 run twice: first accepting only XMLTV ids with programmes in the retained window
 * (today and tomorrow since Spec 67 G1c, so earlier today counts; free-epg.de is half stale, so
 * its id hit must not shadow a live epg.pw name hit), then accepting any hit. Several XMLTV ids
 * can carry one name (free-epg.de lists a channel 3-5 times): within a pass the first in file
 * order wins.
 */
object EpgMatcher {

    fun match(
        channels: List<Channel>,
        sources: List<EpgSource>,
        overrides: Map<String, EpgOverride>
    ): Map<String, EpgRef> {
        val indexes = sources.map { Index(it) }
        val out = HashMap<String, EpgRef>()
        for (ch in channels) {
            val override = overrides[ch.key]
            val ref = if (override != null) override.ref
            else matchOne(ch, indexes, live = true) ?: matchOne(ch, indexes, live = false)
            if (ref != null) out[ch.key] = ref
        }
        return out
    }

    private fun matchOne(ch: Channel, indexes: List<Index>, live: Boolean): EpgRef? {
        for (id in ChannelIdentity.providerIds(ch)) {
            for (ix in indexes) ix.pick(ix.byId[id], live)?.let { return EpgRef(ix.source.id, it) }
        }
        for (key in ChannelIdentity.tvgIdKeys(ch.tvgId)) {
            for (ix in indexes) ix.pick(ix.byId[key], live)?.let { return EpgRef(ix.source.id, it) }
            val loose = ChannelIdentity.looseId(key) ?: continue
            for (ix in indexes) ix.pick(ix.byLoose[loose], live)?.let { return EpgRef(ix.source.id, it) }
        }
        val country = ChannelIdentity.normalizeCountry(ch.country) ?: return null
        val names = (listOf(ch.name) + ch.altNames).map(ChannelIdentity::normalizeName).filter { it.isNotEmpty() }
        for (ix in indexes) {
            if (ix.country != country) continue
            for (n in names) ix.pick(ix.byName[n], live)?.let { return EpgRef(ix.source.id, it) }
        }
        return null
    }

    private class Index(val source: EpgSource) {
        val country = ChannelIdentity.normalizeCountry(source.country)
        val byId = HashMap<String, MutableList<String>>()
        val byLoose = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()

        init {
            for (ch in source.snapshot.channels.values) {
                ChannelIdentity.normalizeTvgId(ch.id)?.let { byId.getOrPut(it) { ArrayList() } += ch.id }
                ChannelIdentity.looseId(ch.id)?.let { byLoose.getOrPut(it) { ArrayList() } += ch.id }
                // Guide ids double as names ("Star Jalsha HD.in"): the country suffix goes, once
                // (twice would also take `.TV` off `ABC.TV.au`).
                for (name in ch.displayNames + ch.id) {
                    val n = ChannelIdentity.normalizeName(ChannelIdentity.stripCountrySuffix(name))
                    if (n.isEmpty()) continue
                    val ids = byName.getOrPut(n) { ArrayList() }
                    if (ch.id !in ids) ids += ch.id
                }
            }
        }

        fun pick(ids: List<String>?, live: Boolean): String? = when {
            ids.isNullOrEmpty() -> null
            live -> ids.firstOrNull { source.snapshot.programmes[it].orEmpty().isNotEmpty() }
            else -> ids.first()
        }
    }
}
