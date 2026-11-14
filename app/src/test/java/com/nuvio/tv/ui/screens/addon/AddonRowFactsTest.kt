package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.core.health.AddonHealthLevel
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonBehaviorHints
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AddonRowFactsTest {

    private fun addon(
        resources: List<String> = listOf("stream"),
        rawTypes: List<String> = listOf("movie", "series"),
        catalogs: List<CatalogDescriptor> = emptyList(),
        hints: AddonBehaviorHints? = null,
        enabled: Boolean = true,
        version: String = "1.2.0",
        baseUrl: String = "https://addon.example/token123"
    ) = Addon(
        id = "a",
        name = "A",
        version = version,
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogs,
        types = rawTypes.map { ContentType.fromString(it) },
        rawTypes = rawTypes,
        resources = resources.map { AddonResource(it, rawTypes, null) },
        behaviorHints = hints,
        enabled = enabled
    )

    @Test
    fun `provides follows metadata, catalogs, streams, subtitles order`() {
        val facts = addonRowFacts(
            addon(resources = listOf("subtitles", "stream", "Meta", "catalog", "addon_catalog", "x")),
            AddonHealthLevel.HEALTHY
        )
        assertEquals(
            listOf(AddonProvides.METADATA, AddonProvides.CATALOGS, AddonProvides.STREAMS, AddonProvides.SUBTITLES),
            facts.provides
        )
    }

    @Test
    fun `manifest catalogs count as catalogs without the resource`() {
        val catalog = CatalogDescriptor(type = ContentType.MOVIE, id = "c", name = "C")
        val facts = addonRowFacts(addon(resources = listOf("meta"), catalogs = listOf(catalog)), AddonHealthLevel.HEALTHY)
        assertEquals(listOf(AddonProvides.METADATA, AddonProvides.CATALOGS), facts.provides)
    }

    @Test
    fun `types are normalised, unknown kept, resource types used when the manifest lists none`() {
        assertEquals(
            listOf("movie", "anime", "other thing"),
            addonRowFacts(addon(rawTypes = listOf(" Movie", "anime", "movie", "", "Other Thing")), AddonHealthLevel.UNKNOWN).types
        )
        val fromResources = addon(rawTypes = emptyList()).copy(
            resources = listOf(AddonResource("stream", listOf("series", "movie"), null), AddonResource("meta", listOf("series"), null))
        )
        assertEquals(listOf("series", "movie"), addonRowFacts(fromResources, AddonHealthLevel.UNKNOWN).types)
    }

    @Test
    fun `marks in drawing order`() {
        val facts = addonRowFacts(
            addon(hints = AddonBehaviorHints(configurationRequired = true, p2p = true, adult = true), enabled = false),
            AddonHealthLevel.HEALTHY
        )
        assertEquals(listOf(AddonMark.P2P, AddonMark.ADULT, AddonMark.SETUP, AddonMark.DISABLED), facts.marks)
        assertEquals(emptyList<AddonMark>(), addonRowFacts(addon(), AddonHealthLevel.HEALTHY).marks)
    }

    @Test
    fun `search-on-Enter mark and action only for an addon with a search catalog`() {
        val search = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "s",
            name = "S",
            extra = listOf(com.nuvio.tv.domain.model.CatalogExtra(name = "search"))
        )
        val plain = CatalogDescriptor(type = ContentType.MOVIE, id = "c", name = "C")
        val searchable = addonRowFacts(
            addon(catalogs = listOf(plain, search), hints = AddonBehaviorHints(configurationRequired = true), enabled = false),
            AddonHealthLevel.HEALTHY,
            searchOnEnter = true
        )
        assertTrue(searchable.searchable)
        assertEquals(listOf(AddonMark.SETUP, AddonMark.ON_ENTER, AddonMark.DISABLED), searchable.marks)
        assertEquals(emptyList<AddonMark>(), addonRowFacts(addon(catalogs = listOf(search)), AddonHealthLevel.HEALTHY).marks)

        // A stale mark on an addon that no longer searches shows nothing.
        val notSearchable = addonRowFacts(addon(catalogs = listOf(plain)), AddonHealthLevel.HEALTHY, searchOnEnter = true)
        assertFalse(notSearchable.searchable)
        assertEquals(emptyList<AddonMark>(), notSearchable.marks)
    }

    @Test
    fun `health shows only for an enabled, set-up addon with samples`() {
        assertEquals(AddonHealthLevel.DEGRADED, addonRowFacts(addon(), AddonHealthLevel.DEGRADED).health)
        assertEquals(AddonHealthLevel.DOWN, addonRowFacts(addon(), AddonHealthLevel.DOWN).health)
        assertNull(addonRowFacts(addon(), AddonHealthLevel.UNKNOWN).health)
        assertNull(addonRowFacts(addon(enabled = false), AddonHealthLevel.HEALTHY).health)
        assertNull(addonRowFacts(addon(hints = AddonBehaviorHints(configurationRequired = true)), AddonHealthLevel.HEALTHY).health)
    }

    @Test
    fun `version label`() {
        assertEquals("v1.2.0", addonVersionLabel(" 1.2.0 "))
        assertEquals("V2", addonVersionLabel("V2"))
        assertNull(addonVersionLabel("  "))
        assertEquals("v1.0.0-beta.123…", addonVersionLabel("1.0.0-beta.12345678"))
        assertEquals(ADDON_VERSION_MAX_CHARS, addonVersionLabel("1.0.0-beta.12345678")!!.length)
    }

    @Test
    fun `configure only for configurable addons`() {
        assertNull(addonRowFacts(addon(), AddonHealthLevel.HEALTHY).configureUrl)
        assertEquals(
            "https://addon.example/token123/configure",
            addonRowFacts(addon(hints = AddonBehaviorHints(configurable = true)), AddonHealthLevel.HEALTHY).configureUrl
        )
        assertEquals(
            "https://addon.example/token123/configure",
            addonRowFacts(addon(hints = AddonBehaviorHints(configurationRequired = true)), AddonHealthLevel.HEALTHY).configureUrl
        )
    }

    @Test
    fun `configure url keeps the query and trims path slashes`() {
        assertEquals("https://a.example/x/configure", addonConfigureUrl("https://a.example/x/"))
        assertEquals("http://a.example/configure?config=ab+/", addonConfigureUrl("http://a.example/?config=ab+/"))
        assertEquals("HTTPS://A.example/configure", addonConfigureUrl(" HTTPS://A.example "))
        assertEquals("https://u:p@a.example:8443/x/configure", addonConfigureUrl("https://u:p@a.example:8443/x"))
    }

    @Test
    fun `configure url rejects non-http schemes and hostless urls`() {
        listOf(
            "javascript:alert(1)",
            "intent://a.example#Intent;end",
            "file:///sdcard/x",
            "a.example/path",
            "https:///path",
            "https://",
            "https://@/x",
            "https://:443/x",
            "https://a.example/x#f",
            ""
        ).forEach { assertNull(it, addonConfigureUrl(it)) }
    }

    @Test
    fun `focus after removal goes to the next row, else the previous, else nowhere`() {
        val keys = listOf("a", "b", "c")
        assertEquals("c", addonFocusKeyAfterRemoval(keys, "b"))
        assertEquals("b", addonFocusKeyAfterRemoval(keys, "c"))
        assertNull(addonFocusKeyAfterRemoval(listOf("a"), "a"))
        assertNull(addonFocusKeyAfterRemoval(keys, "missing"))
    }

    @Test
    fun `initials come from two words, else capitals, else the first letters`() {
        assertEquals("LN", addonInitials("Late Night"))
        assertEquals("AIO", addonInitials("AIOStreams"))
        assertEquals("TO", addonInitials("Torrentio"))
        assertEquals("OV", addonInitials("OpenSubtitles v3"))
        assertEquals("?", addonInitials("  "))
    }

    @Test
    fun `a certificate-paused row leads with CERTIFICATE and shows no health`() {
        val facts = addonRowFacts(addon(), AddonHealthLevel.HEALTHY, certificateFailed = true)
        assertEquals(AddonMark.CERTIFICATE, facts.marks.first())
        assertTrue(facts.certificatePaused)
        assertNull(facts.health)
        assertFalse(addonRowFacts(addon(), AddonHealthLevel.HEALTHY).certificatePaused)
    }

    @Test
    fun `fingerprints print in groups of four, eight or four to a line`() {
        val sha = "4F9A21C80B7ED153A96C3E02F84D7B1692E5C07A1D3F68B45A0E9C277DE0B315"
        assertEquals(
            "4F9A 21C8 0B7E D153 A96C 3E02 F84D 7B16\n92E5 C07A 1D3F 68B4 5A0E 9C27 7DE0 B315",
            fingerprintLines(sha, groupsPerLine = 8)
        )
        assertEquals(4, fingerprintLines(sha, groupsPerLine = 4).lines().size)
    }
}
