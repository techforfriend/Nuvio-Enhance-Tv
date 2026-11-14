package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.source.GuidePreset
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.PackSummary
import com.nuvio.tv.livetv.source.SourceKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A Sources row's health label (D16 stage 14), the row's last word. */
sealed interface SourceHealth {
    data object Ok : SourceHealth
    data class Lost(val count: Int) : SourceHealth
    /** The last refresh kept part of a guide (cut-short file). */
    data object Partial : SourceHealth
    /** The last refresh failed; [LiveSource.lastError] says why. */
    data object Failed : SourceHealth
    /** Added, not fetched yet. */
    data object Waiting : SourceHealth
    data object Refreshing : SourceHealth
}

/**
 * One Sources row: [listed] = the playlist's or addon's channels, or the channels a guide file
 * holds (null until it is cached); [matched] = a guide's matched channels; [preview] = the
 * built-in preset's channel names (its line lists them).
 */
data class SourceRow(
    val source: LiveSource,
    val listed: Int?,
    val matched: Int?,
    val health: SourceHealth,
    val preview: List<String> = emptyList()
)

/**
 * A guide preset offered on Sources (Spec 55 S2; the Pluto row of Spec 38 S2 stage 6 and the India
 * row of Spec 49 D27 F5 are two of them). [channels] = listed channels it fits; [sourceName] =
 * the one source holding them all, null when several do.
 */
data class PresetSuggestion(val preset: GuidePreset, val channels: Int, val sourceName: String?)

/**
 * One header guide Sources offers (Spec 55 S1): [country] read from its file name, [channels] =
 * the playlist's listed channels of that country (0 when none or unknown), [name] = the row name
 * adding it gives ("epgshare01 United Kingdom", else its host).
 */
data class HeaderGuide(val url: String, val country: String?, val channels: Int, val name: String)

/**
 * A playlist's header guides that no source holds yet (Spec 55 D4/D5): [guides] largest country
 * first; [byCountry] as [com.nuvio.tv.livetv.source.HeaderGuides.byCountry]; [channels] and
 * [countries] = the playlist's listed channels and how many countries they come from; [hosts] =
 * where the guides live. [shown] = no source holds any of its header guides (the SUGGESTED row,
 * D5b); either way the playlist row offers Choose guides.
 */
data class HeaderSuggestion(
    val sourceId: String,
    val sourceName: String,
    val guides: List<HeaderGuide>,
    val byCountry: Boolean,
    val channels: Int,
    val countries: Int,
    val hosts: List<String>,
    val shown: Boolean
) {
    /** D4: few guides = the row's button adds them all; more = it opens Choose guides. */
    val addsAll: Boolean get() = guides.size <= LiveSourceManager.FEW_HEADER_GUIDES
}

/**
 * The channel pack offer (Spec 73 D2 empty state, D6 SUGGESTED row): [summary] = the list repo's
 * figures (null until read, or when they cannot be read: no figures shown); [adding] = Add pack is
 * downloading; [error] = why the last add failed (the empty state shows it), null otherwise.
 */
data class PackOffer(val summary: PackSummary? = null, val adding: Boolean = false, val error: String? = null)

/** One hidden channel, for Sources > Hidden channels. */
data class HiddenRow(val key: String, val name: String, val sourceName: String?)

/** How long ago a source refreshed, as the row says it (stage 14). */
sealed interface RefreshAge {
    data object Never : RefreshAge
    data object JustNow : RefreshAge
    data class Minutes(val count: Int) : RefreshAge
    /** Earlier today, at [atMs]. */
    data class Today(val atMs: Long) : RefreshAge
    /** [count] calendar days ago (1 = yesterday). */
    data class Days(val count: Int) : RefreshAge
}

/** Pure derivations of the Sources route (Spec 34 L7e). */
object LiveTvSourcesModel {

    fun rows(
        sources: List<LiveSource>,
        channels: List<Channel>,
        guides: Map<String, EpgSnapshot>,
        refreshing: Set<String>
    ): List<SourceRow> {
        val lost = channels.filter { it.lostAtMs != null }.groupingBy { it.sourceId }.eachCount()
        return sources.map { source ->
            val guide = source.kind == SourceKind.XMLTV
            SourceRow(
                source = source,
                listed = if (guide) guides[source.id]?.channels?.size else source.channelCount,
                matched = if (guide) source.channelCount else null,
                // Spec 73 D15 (Q5): the pack's lost channels are the ones its scans hid (or its daily
                // list dropped), expected churn its row line reports; they keep their Source lost tag.
                health = health(source, if (source.kind == SourceKind.PACK) 0 else lost[source.id] ?: 0, source.id in refreshing),
                preview = if (source.id == LiveSourceManager.PRESET_ID) {
                    channels.filter { it.sourceId == source.id && it.lostAtMs == null }.map { it.name }
                } else {
                    emptyList()
                }
            )
        }
    }

    /**
     * The guide presets ([presets], default [LiveSourceManager.GUIDE_PRESETS]) Sources offers: each
     * whose fit (a service's stream URL id, or a country) some listed (not lost) channel has and
     * none of whose guide URLs a source holds; most channels first, preset order on ties. More
     * than [FOLD_PRESETS] fold into one row (D11).
     */
    fun presetSuggestions(
        sources: List<LiveSource>,
        channels: List<Channel>,
        presets: List<GuidePreset> = LiveSourceManager.GUIDE_PRESETS
    ): List<PresetSuggestion> {
        val held = sources.mapTo(HashSet()) { it.url }
        val open = presets.filter { preset -> preset.guides.none { it.url in held } }
        if (open.isEmpty()) return emptyList()
        val listed = channels.filter { it.lostAtMs == null }
        // Each channel's stream URLs are scanned once, not once per service preset.
        val services = listed.map(ChannelIdentity::services)
        val countries = listed.map { ChannelIdentity.normalizeCountry(it.country) }
        return open.mapNotNull { preset ->
            val fits = listed.filterIndexed { i, _ ->
                if (preset.service != null) preset.service in services[i] else countries[i] == preset.country
            }
            if (fits.isEmpty()) return@mapNotNull null
            val holder = fits.mapTo(HashSet()) { it.sourceId }.singleOrNull()
            PresetSuggestion(preset, fits.size, holder?.let { id -> sources.firstOrNull { it.id == id }?.name })
        }.sortedByDescending { it.channels }
    }

    /** D11: more preset suggestions than this fold into one "Guides for your channels" row. */
    const val FOLD_PRESETS = 3

    /** One per playlist whose recorded header guides ([LiveSource.headerGuides]) are not all held by URL. */
    fun headerSuggestions(sources: List<LiveSource>, channels: List<Channel>): List<HeaderSuggestion> {
        val held = sources.mapTo(HashSet()) { it.url }
        return sources.mapNotNull { source ->
            val header = source.headerGuides ?: return@mapNotNull null
            val remaining = header.urls.filterNot { it in held }
            if (remaining.isEmpty()) return@mapNotNull null
            val perCountry = channels
                .filter { it.sourceId == source.id && it.lostAtMs == null }
                .mapNotNull { ChannelIdentity.normalizeCountry(it.country) }
                .groupingBy { it }
                .eachCount()
            val guides = remaining.mapNotNull { url ->
                val http = url.toHttpUrlOrNull() ?: return@mapNotNull null
                val country = LiveSourceManager.guessCountry(http)
                HeaderGuide(url, country, country?.let { perCountry[it] } ?: 0, guideName(http.host, country))
            }.sortedByDescending { it.channels }
            if (guides.isEmpty()) return@mapNotNull null
            HeaderSuggestion(
                sourceId = source.id,
                sourceName = source.name,
                guides = guides,
                byCountry = header.byCountry,
                channels = channels.count { it.sourceId == source.id && it.lostAtMs == null },
                countries = perCountry.size,
                hosts = guides.mapNotNull { it.url.toHttpUrlOrNull()?.host }.distinct(),
                shown = remaining.size == header.urls.size
            )
        }
    }

    /**
     * "epgshare01 United Kingdom": the host's first label (unless shorter than 3 letters) and the
     * country; a guide with no country is named by its host, as Add source URL names one.
     */
    internal fun guideName(host: String, country: String?): String {
        if (country == null) return host
        val label = host.removePrefix("www.").substringBefore('.').takeIf { it.length >= 3 } ?: host
        return "$label ${LiveTvModel.countryName(country)}"
    }

    internal fun health(source: LiveSource, lost: Int, refreshing: Boolean): SourceHealth = when {
        refreshing -> SourceHealth.Refreshing
        source.lastError == LiveSourceManager.GUIDE_INCOMPLETE -> SourceHealth.Partial
        source.lastError != null -> SourceHealth.Failed
        source.lastRefreshMs == null -> SourceHealth.Waiting
        lost > 0 -> SourceHealth.Lost(lost)
        else -> SourceHealth.Ok
    }

    /** Hidden channels that still exist, by name; keys of vanished channels are left out. */
    fun hidden(keys: Set<String>, channels: List<Channel>, sources: List<LiveSource>): List<HiddenRow> {
        if (keys.isEmpty()) return emptyList()
        val names = sources.associate { it.id to it.name }
        return channels.filter { it.key in keys }
            .map { HiddenRow(it.key, it.shownName, names[it.sourceId]) }
            .sortedBy { it.name.lowercase() }
    }

    fun refreshAge(lastMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): RefreshAge {
        if (lastMs == null) return RefreshAge.Never
        val elapsed = nowMs - lastMs
        if (elapsed < MINUTE_MS) return RefreshAge.JustNow
        if (elapsed < HOUR_MS) return RefreshAge.Minutes((elapsed / MINUTE_MS).toInt())
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(lastMs).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        ).toInt()
        return if (days <= 0) RefreshAge.Today(lastMs) else RefreshAge.Days(days)
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
}
