package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.core.health.AddonHealthLevel
import com.nuvio.tv.data.repository.splitAddonBaseUrl
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.isSearchable

/** What an addon provides, in the order the row's provides line lists it (mockup U11 stage 11). */
enum class AddonProvides { METADATA, CATALOGS, STREAMS, SUBTITLES }

/**
 * Row marks, in drawing order. CERTIFICATE is Spec 68 X7 (the server's certificate was refused);
 * P2P / ADULT come from the manifest (X7, X9); ON_ENTER is Spec 40.
 */
enum class AddonMark { CERTIFICATE, P2P, ADULT, SETUP, ON_ENTER, DISABLED }

/**
 * Everything an addon row in Settings › Addons shows, decided without Android or Compose
 * (Spec 31 S7b). The pane maps the enums and raw types to strings.
 *
 * @property version "v1.2.0", capped at [ADDON_VERSION_MAX_CHARS] (free text from the addon); null when blank.
 * @property types manifest types, trimmed + lowercased + distinct, manifest order; the pane
 *   labels known ones (movie, series, ...) and shows the rest as written.
 * @property health shown at the row's end; null while the addon is disabled, needs setup, or
 *   has no samples yet.
 * @property configureUrl QR target of the Configure action; null hides the action.
 * @property searchable the addon has a search catalog: only then does the row offer the Spec 40
 *   "Search: as you type / on Enter" action, and show the ON ENTER mark.
 * @property certificatePaused Spec 68 X7 stage 3: its server's last call failed on the certificate,
 *   so nothing is sent to it; the row shows PAUSED where health goes, a why line, and offers
 *   Review certificate first.
 */
data class AddonRowFacts(
    val version: String?,
    val provides: List<AddonProvides>,
    val types: List<String>,
    val marks: List<AddonMark>,
    val health: AddonHealthLevel?,
    val configureUrl: String?,
    val searchable: Boolean = false,
    val certificatePaused: Boolean = false
)

internal const val ADDON_VERSION_MAX_CHARS = 16

/**
 * [searchOnEnter]: the addon is marked search-on-Enter on this profile (Spec 40).
 * [certificateFailed]: its server is in the host guard's certificate failures (Spec 68 P6).
 */
fun addonRowFacts(
    addon: Addon,
    health: AddonHealthLevel,
    searchOnEnter: Boolean = false,
    certificateFailed: Boolean = false
): AddonRowFacts {
    val hints = addon.behaviorHints
    val needsSetup = hints?.configurationRequired == true
    val resourceNames = addon.resources.map { it.name.trim().lowercase() }.toSet()
    val provides = AddonProvides.entries.filter { kind ->
        when (kind) {
            AddonProvides.METADATA -> "meta" in resourceNames
            AddonProvides.CATALOGS -> "catalog" in resourceNames || addon.catalogs.isNotEmpty()
            AddonProvides.STREAMS -> "stream" in resourceNames
            AddonProvides.SUBTITLES -> "subtitles" in resourceNames
        }
    }
    val searchable = addon.catalogs.any { it.isSearchable() }
    val manifestTypes = addon.rawTypes.ifEmpty { addon.resources.flatMap { it.types } }
    val marks = buildList {
        if (certificateFailed) add(AddonMark.CERTIFICATE)
        if (hints?.p2p == true) add(AddonMark.P2P)
        if (hints?.adult == true) add(AddonMark.ADULT)
        if (needsSetup) add(AddonMark.SETUP)
        if (searchable && searchOnEnter) add(AddonMark.ON_ENTER)
        if (!addon.enabled) add(AddonMark.DISABLED)
    }
    val configurable = hints?.configurable == true || needsSetup
    return AddonRowFacts(
        version = addonVersionLabel(addon.version),
        provides = provides,
        types = manifestTypes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
        marks = marks,
        health = health.takeIf { addon.enabled && !needsSetup && !certificateFailed && it != AddonHealthLevel.UNKNOWN },
        configureUrl = if (configurable) addonConfigureUrl(addon.baseUrl) else null,
        searchable = searchable,
        certificatePaused = certificateFailed
    )
}

internal fun addonVersionLabel(version: String): String? {
    val trimmed = version.trim().takeIf { it.isNotEmpty() } ?: return null
    val label = if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
    return if (label.length <= ADDON_VERSION_MAX_CHARS) label else label.take(ADDON_VERSION_MAX_CHARS - 1) + "…"
}

/**
 * The addon's configure page, "<base path>/configure<base query>" (Stremio SDK: `configurable`
 * points at /configure on the addon's domain). http(s) only: synced URLs skip the install
 * screen's scheme check, and this goes into a QR. The URL can carry config tokens, so the pane
 * shows only its host and passes it to no intent, browser or log.
 */
fun addonConfigureUrl(baseUrl: String): String? {
    val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl.trim())
    val scheme = basePath.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    // A host must remain once userinfo and port are cut; a fragment would swallow "/configure".
    val host = basePath.substringAfter("://").substringBefore('/').substringAfterLast('@').substringBefore(':')
    if (host.isBlank() || '#' in basePath) return null
    return "$basePath/configure$baseQuery"
}

/**
 * The row focus goes to once [removed] leaves [keys]: the row that takes its place, else the one
 * above; null when it was the only one.
 */
internal fun addonFocusKeyAfterRemoval(keys: List<String>, removed: String): String? {
    val index = keys.indexOf(removed)
    if (index < 0) return null
    return keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1)
}

/**
 * A logo tile's fallback text: the first letters of the first two words ("Late Night" -> "LN"),
 * else a single word's capitals up to three ("AIOStreams" -> "AIO"), else its first two letters.
 */
internal fun addonInitials(name: String): String {
    val words = name.split(' ', '-', '_', '.').filter { word -> word.any { it.isLetterOrDigit() } }
    if (words.size >= 2) {
        return words.take(2).joinToString("") { word -> word.first { it.isLetterOrDigit() }.uppercase() }
    }
    val word = words.firstOrNull() ?: return "?"
    val capitals = word.filter { it.isUpperCase() }
    return if (capitals.length >= 2) capitals.take(3) else word.filter { it.isLetterOrDigit() }.take(2).uppercase()
}

/**
 * Spec 68 X7: a SHA-256 hex fingerprint in groups of four, [groupsPerLine] groups to a line
 * (sandbox `s068-x7-tls-confirm`: 8 in the trust panel, 4 in each column of the changed one).
 */
internal fun fingerprintLines(sha256: String, groupsPerLine: Int): String =
    sha256.chunked(4).chunked(groupsPerLine).joinToString("\n") { it.joinToString(" ") }
