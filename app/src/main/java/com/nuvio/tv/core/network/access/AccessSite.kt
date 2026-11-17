package com.nuvio.tv.core.network.access

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One secret header a saved site gets (Spec 97 X4, A1). */
@Serializable
data class AccessHeader(val name: String, val value: String) {
    /** Never prints the value (KT-SEC-02): a site list may reach a log line or a crash report. */
    override fun toString(): String = "AccessHeader($name)"
}

/**
 * A saved site (Spec 97 A1): its canonical host (lowercase, punycode, as OkHttp's `HttpUrl.host`)
 * and port, and the headers sent to exactly that host:port over https (D6).
 */
@Serializable
data class AccessSite(val host: String, val port: Int, val headers: List<AccessHeader>) {
    fun matches(host: String, port: Int): Boolean = this.host == host && this.port == port
}

enum class AccessSiteProblem {
    NOT_A_URL,
    NOT_HTTPS,
    HAS_USERINFO,
    NO_HEADERS,
    TOO_MANY_HEADERS,
    BAD_NAME,
    RESERVED_NAME,
    DUPLICATE_NAME,
    BAD_VALUE
}

sealed interface AccessSiteCheck {
    data class Valid(val site: AccessSite) : AccessSiteCheck

    /** [headerIndex] names the header at fault (0-based), null for the address or the header count. */
    data class Invalid(val problem: AccessSiteProblem, val headerIndex: Int? = null) : AccessSiteCheck
}

/** A1's one validator, used by the phone server (S3) and the store's callers. */
object AccessSiteRules {
    const val MAX_HEADERS = 4
    const val MAX_SITES = 16
    const val MAX_NAME_CHARS = 64
    const val MAX_VALUE_CHARS = 4096

    /** Names OkHttp or HTTP itself owns, or that would change what is fetched (mockup stage 3). */
    private val RESERVED_NAMES = setOf(
        "host", "connection", "content-length", "content-type", "transfer-encoding", "te", "trailer",
        "upgrade", "keep-alive", "expect", "range", "cookie", "accept-encoding"
    )

    /** RFC 7230 tchar, beside ALPHA and DIGIT. */
    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

    /**
     * Checks a typed site address and its headers; names and values are trimmed (OkHttp trims a
     * value on the wire anyway). The key comes from the address's host and port only: path, query
     * and fragment are ignored, userinfo is refused.
     */
    fun check(address: String, headers: List<AccessHeader>): AccessSiteCheck {
        val url = address.trim().toHttpUrlOrNull()
            ?: return AccessSiteCheck.Invalid(AccessSiteProblem.NOT_A_URL)
        if (!url.isHttps) return AccessSiteCheck.Invalid(AccessSiteProblem.NOT_HTTPS)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return AccessSiteCheck.Invalid(AccessSiteProblem.HAS_USERINFO)
        }
        if (headers.isEmpty()) return AccessSiteCheck.Invalid(AccessSiteProblem.NO_HEADERS)
        if (headers.size > MAX_HEADERS) return AccessSiteCheck.Invalid(AccessSiteProblem.TOO_MANY_HEADERS)
        val seen = HashSet<String>()
        val clean = headers.mapIndexed { index, header ->
            val name = header.name.trim()
            val value = header.value.trim()
            val problem = when {
                !isToken(name) -> AccessSiteProblem.BAD_NAME
                isReserved(name) -> AccessSiteProblem.RESERVED_NAME
                !seen.add(name.lowercase()) -> AccessSiteProblem.DUPLICATE_NAME
                !isValue(value) -> AccessSiteProblem.BAD_VALUE
                else -> null
            }
            if (problem != null) return AccessSiteCheck.Invalid(problem, index)
            AccessHeader(name, value)
        }
        return AccessSiteCheck.Valid(AccessSite(url.host, url.port, clean))
    }

    /** True when [site] is exactly what [check] makes of its own host, port and headers (the store's guard). */
    fun isValid(site: AccessSite): Boolean {
        val address = try {
            HttpUrl.Builder().scheme("https").host(site.host).port(site.port).build().toString()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return (check(address, site.headers) as? AccessSiteCheck.Valid)?.site == site
    }

    private fun isToken(name: String): Boolean =
        name.length in 1..MAX_NAME_CHARS &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }

    private fun isReserved(name: String): Boolean {
        val lower = name.lowercase()
        return lower in RESERVED_NAMES || lower.startsWith("proxy-")
    }

    /** Visible ASCII plus space and tab: no CR/LF (header injection) and nothing OkHttp would refuse. */
    private fun isValue(value: String): Boolean =
        value.length in 1..MAX_VALUE_CHARS && value.all { it == '\t' || it in ' '..'~' }
}
