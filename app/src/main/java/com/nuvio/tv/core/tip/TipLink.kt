package com.nuvio.tv.core.tip

import android.content.res.Resources
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Spec 81 T1: the tip links as the last good `tip.json` answer gave them (D5). [link] is the QR for
 * every country without an override; null = tips paused (the freeze switch, D4 hides Tip).
 * [byCountry]: ISO 3166 alpha-2 (upper case) to a link, e.g. D2's INR link for "IN"; used only while
 * [link] is set. Stored as JSON
 * (UpdateStore), so fields only ever get added.
 */
@Serializable
data class TipLinks(
    val link: String? = null,
    val byCountry: Map<String, String> = emptyMap()
)

/** The file as published: `{"link": "<url>" | null, "links": {"IN": "<url>"}}`; unknown keys are ignored. */
@Serializable
private data class TipFileDto(
    val link: String? = null,
    val links: Map<String, String> = emptyMap()
)

/**
 * The link every build carries for when no `tip.json` has answered (D9: a constant, not a build
 * setting; it is in every QR, so it is not secret). Empty = none, so Tip stays hidden until Arshad's
 * Stripe Payment Link exists. Must pass [validTipLink] to be used.
 */
const val BUILT_IN_TIP_LINK = ""

/** Bytes read from the answer at most; a longer body is unusable, never truncated and parsed. */
internal const val TIP_FILE_MAX_BYTES = 4 * 1024
internal const val TIP_LINK_MAX_CHARS = 200
private const val MAX_COUNTRY_LINKS = 32

/** The one untrusted-boundary Json for `tip.json` (KT-JSON-02): strict apart from unknown keys. */
private val tipFileJson = Json { ignoreUnknownKeys = true }

private val COUNTRY = Regex("^[A-Z]{2}$")
private val STRIPE_PATH = Regex("^/[A-Za-z0-9_-]{1,64}$")
private val SPONSORS_PATH = Regex("^/sponsors/[A-Za-z0-9-]{1,39}$")

/**
 * A link the QR may carry: at most [TIP_LINK_MAX_CHARS], https on the default port, no user info,
 * query or fragment, already in canonical form (what OkHttp would print), and either a Stripe
 * Payment Link (`buy.stripe.com/<id>`, D1) or a GitHub Sponsors page (`github.com/sponsors/<user>`,
 * D1's backup). Anything else is refused, so a tampered file can only point at those two.
 */
fun validTipLink(url: String): Boolean {
    if (url.length > TIP_LINK_MAX_CHARS) return false
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (parsed.toString() != url) return false
    if (!parsed.isHttps || parsed.port != 443) return false
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    if (parsed.query != null || parsed.fragment != null) return false
    val path = parsed.encodedPath
    return when (parsed.host) {
        "buy.stripe.com" -> STRIPE_PATH.matches(path)
        "github.com" -> SPONSORS_PATH.matches(path)
        else -> false
    }
}

/**
 * [link] (one that passed [validTipLink], so canonical) is the GitHub Sponsors backup, whose card
 * names GitHub Sponsors instead of Stripe (D11).
 */
fun isSponsorsLink(link: String): Boolean = link.startsWith("https://github.com/sponsors/")

/**
 * [body] (already capped at [TIP_FILE_MAX_BYTES]) as links, or null when it is unusable: not a JSON
 * object of that shape, a link that fails [validTipLink], a country key that is not two upper-case
 * letters, or more than 32 overrides. A null or blank `link` is a valid file that pauses tips; one
 * bad entry makes the whole file unusable (it never half-applies).
 */
fun parseTipFile(body: String): TipLinks? {
    val dto = try {
        tipFileJson.decodeFromString(TipFileDto.serializer(), body)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val link = dto.link?.takeIf { it.isNotBlank() }
    if (link != null && !validTipLink(link)) return null
    if (dto.links.size > MAX_COUNTRY_LINKS) return null
    if (dto.links.any { (country, url) -> !COUNTRY.matches(country) || !validTipLink(url) }) return null
    return TipLinks(link, dto.links)
}

/**
 * The QR for a TV in [country] (Locale country, any case): its override, else the general link;
 * null = no Tip. A null general link pauses tips everywhere (the freeze switch), overrides included.
 */
fun effectiveTipLink(links: TipLinks, country: String): String? =
    links.link?.let { link -> links.byCountry[country.uppercase()] ?: link }

/**
 * Whether the Welcome and What's new cards show Tip (D4): only with a [link] to show, and never on a
 * profile whose Settings are locked. About needs only the link: a locked profile never reaches Settings.
 */
fun tipEntryShown(link: String?, settingsLocked: Boolean): Boolean = link != null && !settingsLocked

/**
 * The TV's region (Android's system locale), not the app's: Nuvio's language setting replaces
 * `Locale.getDefault()` (MainActivity.attachBaseContext) with tags that mostly carry no region
 * ("hi", "en"), which would hide India's override and ladder. Empty when unknown.
 */
fun tvCountry(): String = Resources.getSystem().configuration.locales[0]?.country.orEmpty()

/** What the tip card suggests (D2, R2 report section 1): three amounts, and whether the pay line leads with UPI. */
data class TipLadder(val amounts: List<String>, val upiFirst: Boolean = false)

private val EUROZONE = setOf(
    "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR", "GR", "HR", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PT", "SI", "SK"
)

/**
 * The ladder for a TV in [country]. Amounts are labels, not prices: the payer picks the amount on
 * the phone, in the currency Stripe shows there. Any country not listed sees US dollars as "US$".
 */
fun tipLadder(country: String): TipLadder = when (country.uppercase()) {
    "US" -> TipLadder(listOf("$3", "$5", "$10"))
    "CA" -> TipLadder(listOf("C$4", "C$7", "C$14"))
    "GB" -> TipLadder(listOf("£3", "£4", "£8"))
    "AU" -> TipLadder(listOf("A$5", "A$8", "A$15"))
    "IN" -> TipLadder(listOf("₹150", "₹250", "₹500"), upiFirst = true)
    "BR" -> TipLadder(listOf("R$10", "R$20", "R$40"))
    in EUROZONE -> TipLadder(listOf("€3", "€5", "€10"))
    else -> TipLadder(listOf("US$3", "US$5", "US$10"))
}
