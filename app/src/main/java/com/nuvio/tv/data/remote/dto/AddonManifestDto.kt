package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AddonManifestDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("version") val version: String,
    @SerialName("description") val description: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("catalogs") val catalogs: List<CatalogDescriptorDto> = emptyList(),
    @SerialName("resources") val resources: List<JsonElement> = emptyList(),
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("idPrefixes") val idPrefixes: List<String> = emptyList(),
    @SerialName("behaviorHints") val behaviorHints: AddonBehaviorHintsDto? = null,
    @SerialName("stremioAddonsConfig") val stremioAddonsConfig: StremioAddonsConfigDto? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("configVersion") val configVersion: Long? = null,
    @SerialName("_timestamp") val timestamp: Long? = null
)

@Serializable
data class CatalogDescriptorDto(
    @SerialName("type") val type: String,
    @SerialName("id") val id: String,
    /** Optional in the Stremio SDK (its own example omits it); the mapper falls back to the addon's name. */
    @SerialName("name") val name: String? = null,
    @SerialName("extra") val extra: List<JsonElement>? = null,
    @SerialName("pageSize") val pageSize: Int? = null,
    @SerialName("showInHome") val showInHome: Boolean? = null,
    @SerialName("extraSupported") val extraSupported: List<String>? = null,
    @SerialName("extraRequired") val extraRequired: List<String>? = null
)

// Flags are JsonElement and read leniently in the mapper: the wire Json is strict on
// scalars, so a quoted "true" on a Boolean field would drop the whole manifest.
@Serializable
data class AddonBehaviorHintsDto(
    @SerialName("configurable") val configurable: JsonElement? = null,
    @SerialName("configurationRequired") val configurationRequired: JsonElement? = null,
    @SerialName("newEpisodeNotifications") val newEpisodeNotifications: JsonElement? = null,
    @SerialName("p2p") val p2p: JsonElement? = null,
    @SerialName("adult") val adult: JsonElement? = null
)

@Serializable
data class StremioAddonsConfigDto(
    @SerialName("issuer") val issuer: String? = null,
    @SerialName("signature") val signature: String? = null
)
