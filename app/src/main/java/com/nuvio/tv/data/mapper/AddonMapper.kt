package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.AddonBehaviorHintsDto
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import com.nuvio.tv.data.remote.dto.StremioAddonsConfigDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonBehaviorHints
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.StremioAddonsConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun AddonManifestDto.toDomain(baseUrl: String): Addon {
    val manifestTypes = types.map { it.trim() }.filter { it.isNotEmpty() }
    return Addon(
        id = id,
        name = name,
        version = version,
        description = description,
        logo = logo,
        background = background,
        baseUrl = baseUrl,
        catalogs = catalogs.map { it.toDomain(addonName = name) },
        types = manifestTypes.map { ContentType.fromString(it) },
        rawTypes = manifestTypes,
        resources = parseResources(resources, manifestTypes),
        idPrefixes = idPrefixes,
        behaviorHints = behaviorHints?.toDomain(),
        stremioAddonsConfig = stremioAddonsConfig?.toDomain(),
        manifestLanguage = language,
        configVersion = configVersion,
        timestamp = timestamp
    )
}

/**
 * A catalog without a `name` (Stremio's YouTube and Public Domain Movies addons) is labelled with
 * its addon's name, as Stremio does (stremio-core-web `name.unwrap_or(&addon.manifest.name)`).
 */
fun CatalogDescriptorDto.toDomain(addonName: String): CatalogDescriptor {
    val manifestType = type.trim()
    return CatalogDescriptor(
        type = ContentType.fromString(manifestType),
        rawType = manifestType,
        id = id,
        name = name ?: addonName,
        extra = parseCatalogExtras(extra),
        pageSize = pageSize,
        showInHome = showInHome == true,
        hasExplicitShowInHome = showInHome != null,
        extraSupported = extraSupported.orEmpty(),
        extraRequired = extraRequired.orEmpty()
    )
}

private fun parseResources(resources: List<JsonElement>, defaultTypes: List<String>): List<AddonResource> {
    return resources.mapNotNull { resource ->
        when (resource) {
            is JsonPrimitive -> {
                // Simple resource format: "meta", "stream", etc.
                if (!resource.isString) return@mapNotNull null
                AddonResource(
                    name = resource.content,
                    types = defaultTypes,
                    idPrefixes = null
                )
            }
            is JsonObject -> {
                // Complex resource format with types and idPrefixes
                val name = stringSlot(resource["name"]) ?: return@mapNotNull null
                val types = (resource["types"] as? JsonArray)
                    ?.filterIsInstance<JsonPrimitive>()
                    ?.filter { it.isString }
                    ?.map { it.content }
                    ?: defaultTypes
                val idPrefixes = (resource["idPrefixes"] as? JsonArray)
                    ?.filterIsInstance<JsonPrimitive>()
                    ?.filter { it.isString }
                    ?.map { it.content }
                AddonResource(
                    name = name,
                    types = types,
                    idPrefixes = idPrefixes
                )
            }
            else -> null
        }
    }
}

private fun parseCatalogExtras(rawExtras: List<JsonElement>?): List<CatalogExtra> {
    return rawExtras.orEmpty().mapNotNull { raw ->
        when (raw) {
            is JsonPrimitive -> {
                // Moshi-era Any semantics: only STRING entries carry a bare name.
                if (!raw.isString) return@mapNotNull null
                val name = raw.content.trim().lowercase()
                if (name.isBlank()) {
                    null
                } else {
                    CatalogExtra(name = name)
                }
            }
            is JsonObject -> {
                val name = stringSlot(raw["name"])?.trim()?.lowercase().orEmpty()
                if (name.isBlank()) return@mapNotNull null

                val isRequired = lenientBoolean(raw["isRequired"]) ?: false
                val options = (raw["options"] as? JsonArray)?.mapNotNull { option ->
                    when (option) {
                        is JsonNull -> null
                        is JsonPrimitive -> if (option.isString) option.content else option.toString()
                        else -> option.toString()
                    }
                }?.takeIf { it.isNotEmpty() }

                CatalogExtra(
                    name = name,
                    isRequired = isRequired,
                    options = options,
                    defaultValue = stringSlot(raw["default"])?.takeIf { it.isNotBlank() },
                    optionsLimit = when (val value = raw["optionsLimit"]) {
                        is JsonNull, null -> null
                        is JsonPrimitive ->
                            if (value.isString) value.content.toIntOrNull()
                            else value.content.toDoubleOrNull()?.toInt()
                        else -> null
                    }
                )
            }
            else -> null
        }
    }.distinct()
}

/**
 * Lenient flag: true / false literals, numbers (non-zero after truncation is true) and the
 * strings "true" / "false" (any case); null for anything else, absent or JSON null.
 */
private fun lenientBoolean(element: JsonElement?): Boolean? {
    // JsonNull IS a JsonPrimitive (content "null"); excluded by name so the trap stays visible.
    val primitive = (element as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
    return when {
        primitive.isString -> when {
            primitive.content.equals("true", ignoreCase = true) -> true
            primitive.content.equals("false", ignoreCase = true) -> false
            else -> null
        }
        primitive.content == "true" -> true
        primitive.content == "false" -> false
        else -> primitive.content.toDoubleOrNull()?.let { it.toInt() != 0 }
    }
}

/** Strict string slot: only string primitives pass (Moshi's `as? String`). */
private fun stringSlot(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun AddonBehaviorHintsDto.toDomain(): AddonBehaviorHints {
    return AddonBehaviorHints(
        configurable = lenientBoolean(configurable),
        configurationRequired = lenientBoolean(configurationRequired),
        newEpisodeNotifications = lenientBoolean(newEpisodeNotifications),
        p2p = lenientBoolean(p2p),
        adult = lenientBoolean(adult)
    )
}

private fun StremioAddonsConfigDto.toDomain(): StremioAddonsConfig {
    return StremioAddonsConfig(
        issuer = issuer?.takeIf { it.isNotBlank() },
        signature = signature?.takeIf { it.isNotBlank() }
    )
}
