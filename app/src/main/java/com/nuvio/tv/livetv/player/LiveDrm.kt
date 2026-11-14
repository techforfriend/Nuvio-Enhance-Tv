package com.nuvio.tv.livetv.player

import com.nuvio.tv.core.di.UNTRUSTED_JSON_MAX_DEPTH
import com.nuvio.tv.core.player.PlayerMediaSourceFactory
import com.nuvio.tv.livetv.parse.M3uParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.util.Locale

/**
 * Licence settings of one playlist stream, read from its `#KODIPROP` lines (Spec 74 S2a); the
 * Media3 session is built from them in S2b. Kodi inputstream.adaptive forms, newest first:
 * `drm` (JSON per key system), `drm_legacy` (`[key system]|[licence URL or kid:key,... or data:
 * URI]|[licence headers]`), the old `license_type` + `license_key`
 * (`[licence URL]|[headers]|[post data]|[response]`, or ClearKey keys as OTT Navigator writes them).
 * Values are untrusted and can hold keys and tokens: never log them (KT-SEC-02).
 *
 * [keySystem] is the Kodi key system id (`com.widevine.alpha`, `org.w3.clearkey`,
 * `com.microsoft.playready`, `com.huawei.wiseplay`, or another as written, lower-cased).
 * [licenseUrl] null = the manifest's own (also when the playlist's is not http(s)). [clearKeys] (ClearKey only; may
 * come with a [licenseUrl] in the old form) = key id -> key, lower-case hex
 * (16 bytes each). [wrapped] = the playlist asks for licence request/response handling Media3's
 * plain POST-the-challenge does not do (custom request data, wrappers, a GET request, challenge
 * placeholders in the URL): S2b says so rather than send a request the server will refuse.
 */
data class LiveDrm(
    val keySystem: String,
    val licenseUrl: String?,
    val licenseHeaders: Map<String, String>,
    val clearKeys: Map<String, String>,
    val wrapped: Boolean
) {
    /**
     * [clearKeys] as the W3C ClearKey licence a ClearKey session takes in place of a server answer:
     * `{"keys":[{"kty":"oct","kid":<base64url>,"k":<base64url>}],"type":"temporary"}`.
     */
    fun clearKeyLicense(): String = buildJsonObject {
        putJsonArray("keys") {
            for ((kid, key) in clearKeys) {
                addJsonObject {
                    put("kty", "oct")
                    put("kid", base64Url(kid))
                    put("k", base64Url(key))
                }
            }
        }
        put("type", "temporary")
    }.toString()

    /** Redacted: the URL can carry tokens, the headers and keys are secrets (KT-SEC-02). */
    override fun toString(): String =
        "LiveDrm(keySystem=${keySystem.takeIf { it in KNOWN } ?: "other"}, licenseUrl=${if (licenseUrl == null) "none" else "set"}, " +
            "licenseHeaders=${licenseHeaders.size}, clearKeys=${clearKeys.size}, wrapped=$wrapped)"

    companion object {
        /** The name people know a key system by (Spec 74 D6); null for one this app has no name for. */
        fun displayName(keySystem: String?): String? = when (keySystem) {
            WIDEVINE -> "Widevine"
            CLEARKEY -> "ClearKey"
            PLAYREADY -> "PlayReady"
            WISEPLAY -> "WisePlay"
            else -> null
        }

        private fun base64Url(hex: String): String = hex.decodeHex().base64Url().trimEnd('=')

        const val WIDEVINE = "com.widevine.alpha"
        const val CLEARKEY = "org.w3.clearkey"
        const val PLAYREADY = "com.microsoft.playready"
        const val WISEPLAY = "com.huawei.wiseplay"

        /** Licence settings for [kodiProps]; null exactly when the parser marks the stream not `drm`. */
        fun from(kodiProps: Map<String, String>?): LiveDrm? {
            val props = kodiProps ?: return null
            if (!M3uParser.needsLicence(props)) return null
            return fromJson(props[DRM]) ?: fromLegacy(props[DRM_LEGACY]) ?: fromOld(props)
        }

        /** Kodi 22 `drm`: the first Widevine/ClearKey entry (what this TV can play), else the first other one. */
        private fun fromJson(value: String?): LiveDrm? {
            val obj = value?.trim()?.let { M3uParser.jsonObjectOrNull(it, UNTRUSTED_JSON_MAX_DEPTH) } ?: return null
            val entries = obj.entries.filter { keySystemOf(it.key) != null }
            val (name, config) = entries.firstOrNull { keySystemOf(it.key) in PLAYABLE } ?: entries.firstOrNull() ?: return null
            val license = ((config as? JsonObject)?.get("license") as? JsonObject) ?: JsonObject(emptyMap())
            // req_params is appended verbatim, as Kodi does (its example is a path: "/one/two/three-path").
            val url = license.string("server_url")?.let { it + license.string("req_params").orEmpty() }
            val keys = (license["keyids"] as? JsonObject)?.takeIf { keySystemOf(name) == CLEARKEY }
                ?.mapNotNull { (kid, key) -> (key as? JsonPrimitive)?.takeIf { it.isString }?.let { hexPair(kid, it.content) } }
                ?.toMap().orEmpty()
            return LiveDrm(
                keySystem = keySystemOf(name)!!,
                licenseUrl = url?.takeIf(::isUrl),
                licenseHeaders = headers(license.string("req_headers")),
                clearKeys = keys,
                wrapped = WRAPPING_FIELDS.any { license.string(it) != null } ||
                    (license["use_http_get_request"] as? JsonPrimitive)?.booleanOrNull == true ||
                    hasPlaceholder(url)
            )
        }

        /** Kodi 21 `drm_legacy`: `[key system]|[licence URL, kid:key pairs or data: URI]|[headers]`. */
        private fun fromLegacy(value: String?): LiveDrm? {
            val parts = value?.split('|', limit = 3) ?: return null
            val keySystem = keySystemOf(parts[0]) ?: return null
            val target = parts.getOrNull(1)?.trim().orEmpty()
            val keys = if (keySystem != CLEARKEY || isUrl(target)) emptyMap() else clearKeysOf(target)
            val url = target.takeIf(::isUrl)
            return LiveDrm(keySystem, url, headers(parts.getOrNull(2)), keys, wrapped = hasPlaceholder(url))
        }

        /** Old properties: `license_type` + `license_key` (+ `license_url`, `license_url_append`). */
        private fun fromOld(props: Map<String, String>): LiveDrm? {
            val keySystem = keySystemOf(props[LICENSE_TYPE].orEmpty()) ?: return null
            val key = props[LICENSE_KEY]?.trim().orEmpty()
            val append = props[LICENSE_URL_APPEND]?.trim().orEmpty()
            val licenseUrl = props[LICENSE_URL]?.trim()?.ifEmpty { null }?.let { it + append }?.takeIf(::isUrl)
            // ClearKey keys in license_key (OTT Navigator): kid:key pairs, a JWK set or {"kid":"key"}.
            if (keySystem == CLEARKEY && key.isNotEmpty() && !isUrl(key)) {
                return LiveDrm(keySystem, licenseUrl, emptyMap(), clearKeysOf(key), hasPlaceholder(licenseUrl))
            }
            val parts = key.split('|')
            val url = licenseUrl ?: parts[0].trim().ifEmpty { null }?.let { it + append }
            val post = decode(parts.getOrNull(2)?.trim().orEmpty())
            val response = parts.getOrNull(3)?.trim().orEmpty()
            return LiveDrm(
                keySystem = keySystem,
                licenseUrl = url?.takeIf(::isUrl),
                licenseHeaders = headers(parts.getOrNull(1)),
                clearKeys = emptyMap(),
                // R{SSM} = the raw challenge (Media3's default); an empty or R response = raw licence bytes.
                wrapped = post !in RAW_POST || response !in RAW_RESPONSE || hasPlaceholder(url)
            )
        }

        private fun keySystemOf(raw: String): String? {
            val id = raw.trim().lowercase(Locale.ROOT)
            if (id.isEmpty() || id == "none") return null
            return ALIASES[id] ?: id
        }

        /** A Kodi placeholder (`{SSM}`, `{KID}`, `{CHA-B64U}`, ...) in the licence URL: Media3 sends the URL as written. */
        private fun hasPlaceholder(url: String?): Boolean = url != null && PLACEHOLDER.containsMatchIn(url)

        private fun decode(text: String): String =
            runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8.name()) }.getOrDefault(text)

        private fun headers(pairs: String?): Map<String, String> =
            PlayerMediaSourceFactory.sanitizeHeaders(pairs?.let(M3uParser::parseHeaderPairs))

        /** `kid:key,kid:key` (hex), a JWK set `{"keys":[{"kid","k"}]}` (base64url), `{"kid":"key"}`, or a `data:` URI holding a JWK set. */
        private fun clearKeysOf(text: String): Map<String, String> {
            if (!text.startsWith("data:", ignoreCase = true)) return clearKeysOfPlain(text)
            // Decoded once, never recursively: a nested data: URI holds no keys.
            val payload = text.substringAfter(',', "")
            val json = if (text.substringBefore(',').endsWith(";base64", ignoreCase = true)) {
                payload.trim().decodeBase64()?.utf8()
            } else {
                decode(payload.replace("+", "%2B")) // percent-encoding (RFC 2397), not form encoding: '+' stays '+'
            }
            return json?.trim()?.takeUnless { it.startsWith("data:", ignoreCase = true) }?.let(::clearKeysOfPlain).orEmpty()
        }

        private fun clearKeysOfPlain(text: String): Map<String, String> {
            val obj = M3uParser.jsonObjectOrNull(text, UNTRUSTED_JSON_MAX_DEPTH)
                ?: return text.split(',').mapNotNull { pair ->
                    pair.split(':').takeIf { it.size == 2 }?.let { (kid, key) -> hexPair(kid, key) }
                }.toMap()
            val jwks = obj["keys"] as? JsonArray
            return if (jwks != null) {
                jwks.mapNotNull { jwk ->
                    val o = jwk as? JsonObject ?: return@mapNotNull null
                    hexPair(o.string("kid")?.let(::base64UrlToHex), o.string("k")?.let(::base64UrlToHex))
                }.toMap()
            } else {
                obj.mapNotNull { (kid, key) -> (key as? JsonPrimitive)?.takeIf { it.isString }?.let { hexPair(kid, it.content) } }.toMap()
            }
        }

        /** Both 16-byte hex (dashes allowed, as in UUID key ids), lower-cased; null when either is not. */
        private fun hexPair(kid: String?, key: String?): Pair<String, String>? {
            val k = kid?.let(::hex16) ?: return null
            val v = key?.let(::hex16) ?: return null
            return k to v
        }

        private fun hex16(raw: String): String? =
            raw.trim().replace("-", "").lowercase(Locale.ROOT).takeIf { HEX16.matches(it) }

        private fun base64UrlToHex(raw: String): String? = raw.trim().decodeBase64()?.takeIf { it.size == 16 }?.hex()

        private fun isUrl(text: String): Boolean =
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

        private const val DRM = "inputstream.adaptive.drm"
        private const val DRM_LEGACY = "inputstream.adaptive.drm_legacy"
        private const val LICENSE_TYPE = "inputstream.adaptive.license_type"
        private const val LICENSE_KEY = "inputstream.adaptive.license_key"
        private const val LICENSE_URL = "inputstream.adaptive.license_url"
        private const val LICENSE_URL_APPEND = "inputstream.adaptive.license_url_append"

        private val ALIASES = mapOf(
            "widevine" to WIDEVINE,
            "clearkey" to CLEARKEY,
            "playready" to PLAYREADY,
            "wiseplay" to WISEPLAY
        )
        private val PLAYABLE = setOf(WIDEVINE, CLEARKEY)
        private val KNOWN = setOf(WIDEVINE, CLEARKEY, PLAYREADY, WISEPLAY)
        private val RAW_POST = setOf("", "R{SSM}")
        private val RAW_RESPONSE = setOf("", "R")
        private val WRAPPING_FIELDS = setOf("req_data", "wrapper", "unwrapper")
        private val HEX16 = Regex("[0-9a-f]{32}")
        private val PLACEHOLDER = Regex("""\{[A-Z][A-Z0-9-]*\}""")
    }
}
