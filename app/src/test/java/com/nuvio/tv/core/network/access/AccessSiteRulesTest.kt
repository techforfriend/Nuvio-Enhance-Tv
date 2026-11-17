package com.nuvio.tv.core.network.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessSiteRulesTest {

    private val cf = listOf(AccessHeader("CF-Access-Client-Id", "id.access"), AccessHeader("CF-Access-Client-Secret", "s3cret"))

    private fun valid(address: String, headers: List<AccessHeader> = cf): AccessSite =
        (AccessSiteRules.check(address, headers) as AccessSiteCheck.Valid).site

    private fun problem(address: String, headers: List<AccessHeader> = cf): AccessSiteCheck.Invalid =
        AccessSiteRules.check(address, headers) as AccessSiteCheck.Invalid

    @Test
    fun `the key is the canonical host and port, path ignored`() {
        val site = valid("  https://Watch.Kamal.CA/some/path?q=1#x ")
        assertEquals("watch.kamal.ca", site.host)
        assertEquals(443, site.port)
        assertEquals(8443, valid("https://watch.kamal.ca:8443/").port)
        assertEquals("xn--bcher-kva.example", valid("https://bücher.example").host)
    }

    @Test
    fun `http, userinfo and non-urls are refused`() {
        assertEquals(AccessSiteProblem.NOT_HTTPS, problem("http://192.0.2.50:8096").problem)
        assertEquals(AccessSiteProblem.HAS_USERINFO, problem("https://me:pw@watch.kamal.ca").problem)
        assertEquals(AccessSiteProblem.HAS_USERINFO, problem("https://me@watch.kamal.ca").problem)
        assertEquals(AccessSiteProblem.NOT_A_URL, problem("watch.kamal.ca").problem)
    }

    @Test
    fun `one to four headers`() {
        assertEquals(AccessSiteProblem.NO_HEADERS, problem("https://a.example", emptyList()).problem)
        val five = (1..5).map { AccessHeader("X-H$it", "v") }
        assertEquals(AccessSiteProblem.TOO_MANY_HEADERS, problem("https://a.example", five).problem)
        assertEquals(4, valid("https://a.example", five.take(4)).headers.size)
    }

    @Test
    fun `names are tokens, not reserved, not repeated`() {
        fun at(name: String, index: Int = 1) =
            problem("https://a.example", listOf(AccessHeader("X-Ok", "v"), AccessHeader(name, "v")))
                .also { assertEquals(index, it.headerIndex) }.problem
        assertEquals(AccessSiteProblem.BAD_NAME, at("Bad Name"))
        assertEquals(AccessSiteProblem.BAD_NAME, at("X:Y"))
        assertEquals(AccessSiteProblem.BAD_NAME, at(""))
        assertEquals(AccessSiteProblem.BAD_NAME, at("X".repeat(65)))
        assertEquals(AccessSiteProblem.RESERVED_NAME, at("host"))
        assertEquals(AccessSiteProblem.RESERVED_NAME, at("Cookie"))
        assertEquals(AccessSiteProblem.RESERVED_NAME, at("Content-Length"))
        assertEquals(AccessSiteProblem.RESERVED_NAME, at("Proxy-Authorization"))
        assertEquals(AccessSiteProblem.DUPLICATE_NAME, at("x-ok"))
        assertEquals("Authorization is a user header", 2, valid("https://a.example", listOf(AccessHeader("Authorization", "Bearer t"), AccessHeader("X-Ok", "v"))).headers.size)
    }

    @Test
    fun `values are visible ascii without line breaks, trimmed`() {
        fun value(v: String) = AccessSiteRules.check("https://a.example", listOf(AccessHeader("X-Key", v)))
        assertEquals(AccessSiteProblem.BAD_VALUE, (value("a\r\nX-Evil: 1") as AccessSiteCheck.Invalid).problem)
        assertEquals(AccessSiteProblem.BAD_VALUE, (value("   ") as AccessSiteCheck.Invalid).problem)
        assertEquals(AccessSiteProblem.BAD_VALUE, (value("é") as AccessSiteCheck.Invalid).problem)
        assertEquals(AccessSiteProblem.BAD_VALUE, (value("v".repeat(4097)) as AccessSiteCheck.Invalid).problem)
        assertTrue(value("v".repeat(4096)) is AccessSiteCheck.Valid)
        assertEquals(AccessHeader("X-Key", "a\tb c"), (value("  a\tb c  ") as AccessSiteCheck.Valid).site.headers.single())
    }

    @Test
    fun `a header never prints its value`() {
        assertFalse(AccessHeader("CF-Access-Client-Secret", "s3cret").toString().contains("s3cret"))
        assertFalse(valid("https://a.example").toString().contains("s3cret"))
    }
}
