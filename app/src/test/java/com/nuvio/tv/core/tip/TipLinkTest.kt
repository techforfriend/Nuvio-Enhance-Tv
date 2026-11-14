package com.nuvio.tv.core.tip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 81 T1: what a tip link may be, what `tip.json` may hold, and the ladders (D2). */
class TipLinkTest {

    private val stripe = "https://buy.stripe.com/7sY4gA2Kq9fW0aE5kk"
    private val sponsors = "https://github.com/sponsors/Arshad-Kamal"

    @Test
    fun `a Stripe Payment Link and a GitHub Sponsors page are valid`() {
        assertTrue(validTipLink(stripe))
        assertTrue(validTipLink("https://buy.stripe.com/test_00g5kk"))
        assertTrue(validTipLink(sponsors))
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            "http://buy.stripe.com/abc",                    // not https
            "https://buy.stripe.com:8443/abc",              // another port
            "https://user:pw@buy.stripe.com/abc",           // user info
            "https://buy.stripe.com/abc?prefilled_email=x", // query
            "https://buy.stripe.com/abc#x",                 // fragment
            "https://buy.stripe.com/",                      // no link id
            "https://buy.stripe.com/a/b",                   // a deeper path
            "https://buy.stripe.com/abc%2F..",              // encoded characters
            "https://BUY.stripe.com/abc",                   // not canonical
            " https://buy.stripe.com/abc",                  // padding OkHttp would strip
            "https://buy.stripe.com.evil.example/abc",      // look-alike host
            "https://evil.example/buy.stripe.com/abc",
            "https://checkout.stripe.com/abc",              // another Stripe host
            "https://github.com/Arshad-Kamal",              // github.com, not a sponsors page
            "https://github.com/sponsors/a/b",
            "https://github.com/sponsors/" + "a".repeat(40),
            "https://buy.stripe.com/" + "a".repeat(65),
            "javascript:alert(1)",
            ""
        ).forEach { assertFalse(it, validTipLink(it)) }
    }

    @Test
    fun `a file gives its link and country overrides`() {
        val inr = "https://buy.stripe.com/inr_link"
        assertEquals(TipLinks(stripe, mapOf("IN" to inr)), parseTipFile("""{"link":"$stripe","links":{"IN":"$inr"}}"""))
        assertEquals(TipLinks(sponsors), parseTipFile("""{"link":"$sponsors","note":"unknown keys are ignored"}"""))
    }

    @Test
    fun `a null, blank or missing link pauses tips`() {
        assertEquals(TipLinks(null), parseTipFile("""{"link":null}"""))
        assertEquals(TipLinks(null), parseTipFile("""{"link":"  "}"""))
        assertEquals(TipLinks(null), parseTipFile("{}"))
    }

    @Test
    fun `one bad entry makes the whole file unusable`() {
        listOf(
            """{"link":"https://evil.example/pay"}""",
            """{"link":"$stripe","links":{"IN":"http://buy.stripe.com/x"}}""",
            """{"link":"$stripe","links":{"in":"$stripe"}}""",
            """{"link":"$stripe","links":{"IND":"$stripe"}}""",
            """{"link":"$stripe","links":{"IN":5}}""",
            """{"link":5}""",
            """{"link":["$stripe"]}""",
            """["$stripe"]""",
            "not json",
            "",
            """{"link":"$stripe",}"""
        ).forEach { assertNull(it, parseTipFile(it)) }
        val countries = { n: Int -> (0..<n).joinToString(",") { i -> "\"${'A' + i / 26}${'A' + i % 26}\":\"$stripe\"" } }
        assertEquals(32, parseTipFile("""{"link":"$stripe","links":{${countries(32)}}}""")?.byCountry?.size)
        val many = (0..<33).joinToString(",") { i -> "\"${'A' + i / 26}${'A' + i % 26}\":\"$stripe\"" }
        assertNull(parseTipFile("""{"link":"$stripe","links":{$many}}"""))
    }

    @Test
    fun `a country override wins, in any case, while tips are not paused`() {
        val links = TipLinks(stripe, mapOf("IN" to "https://buy.stripe.com/inr"))
        assertEquals("https://buy.stripe.com/inr", effectiveTipLink(links, "in"))
        assertEquals(stripe, effectiveTipLink(links, "US"))
        assertEquals(stripe, effectiveTipLink(links, ""))
        assertNull(effectiveTipLink(TipLinks(null), "US"))
        // The freeze switch pauses override countries too.
        assertNull(effectiveTipLink(TipLinks(null, links.byCountry), "IN"))
    }

    @Test
    fun `only a GitHub Sponsors link gets the Sponsors terms line`() {
        assertTrue(isSponsorsLink(sponsors))
        assertFalse(isSponsorsLink(stripe))
    }

    @Test
    fun `Tip shows only with a link and with Settings unlocked`() {
        assertTrue(tipEntryShown(stripe, settingsLocked = false))
        assertFalse(tipEntryShown(stripe, settingsLocked = true))
        assertFalse(tipEntryShown(null, settingsLocked = false))
        assertFalse(tipEntryShown(null, settingsLocked = true))
    }

    @Test
    fun `ladders follow the TV's country, US dollars elsewhere`() {
        assertEquals(listOf("$3", "$5", "$10"), tipLadder("US").amounts)
        assertEquals(listOf("C$4", "C$7", "C$14"), tipLadder("ca").amounts)
        assertEquals(listOf("£3", "£4", "£8"), tipLadder("GB").amounts)
        assertEquals(listOf("€3", "€5", "€10"), tipLadder("DE").amounts)
        assertEquals(listOf("€3", "€5", "€10"), tipLadder("NL").amounts)
        assertEquals(listOf("A$5", "A$8", "A$15"), tipLadder("AU").amounts)
        assertEquals(TipLadder(listOf("₹150", "₹250", "₹500"), upiFirst = true), tipLadder("IN"))
        assertEquals(listOf("R$10", "R$20", "R$40"), tipLadder("BR").amounts)
        assertEquals(TipLadder(listOf("US$3", "US$5", "US$10")), tipLadder("MX"))
        assertEquals(TipLadder(listOf("US$3", "US$5", "US$10")), tipLadder(""))
    }
}
