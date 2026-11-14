package com.nuvio.tv.core.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonConfigServerTest {

    private val context = mockk<Context> {
        every { getString(any<Int>()) } returns "Invalid request body"
    }

    @Test
    fun `collections only mode preserves existing addon and catalog changes`() {
        val currentState = PageState(
            addons = listOf(
                AddonInfo(
                    url = "https://primary.example",
                    name = "Primary",
                    description = null
                )
            ),
            catalogs = listOf(
                CatalogInfo(
                    key = "catalog_a",
                    disableKey = "catalog_a",
                    catalogName = "Featured",
                    addonName = "Primary",
                    type = "movie",
                    isDisabled = false
                ),
                CatalogInfo(
                    key = "collection_saved",
                    disableKey = "collection_saved",
                    catalogName = "Saved",
                    addonName = "1 folder",
                    type = "collection",
                    isDisabled = true
                )
            )
        )
        val proposedChange = PendingAddonChange(
            proposedUrls = listOf("https://other.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = listOf("catalog_b"),
            proposedCollectionsJson = "[{\"id\":\"new\"}]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonWebConfigMode.COLLECTIONS_ONLY,
            proposedChange = proposedChange,
            currentState = currentState
        )

        assertEquals(listOf("https://primary.example"), sanitized.proposedUrls)
        assertEquals(listOf("catalog_a", "collection_saved"), sanitized.proposedCatalogOrderKeys)
        assertEquals(listOf("collection_saved"), sanitized.proposedDisabledCatalogKeys)
        assertEquals("[{\"id\":\"new\"}]", sanitized.proposedCollectionsJson)
        assertEquals(listOf("collection_new"), sanitized.proposedDisabledCollectionKeys)
    }

    @Test
    fun `full mode keeps proposed addon and catalog changes`() {
        val proposedChange = PendingAddonChange(
            proposedUrls = listOf("https://other.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = listOf("catalog_b"),
            proposedCollectionsJson = "[]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonWebConfigMode.FULL,
            proposedChange = proposedChange,
            currentState = PageState(
                addons = emptyList(),
                catalogs = emptyList()
            )
        )

        assertEquals(proposedChange, sanitized)
    }

    @Test
    fun `collections only mode drops the follow addons order proposal`() {
        val proposedChange = PendingAddonChange(
            proposedUrls = emptyList(),
            proposedFollowAddonsOrder = true
        )
        val state = PageState(addons = emptyList(), catalogs = emptyList(), followAddonsOrder = false)

        val sanitized = sanitizePendingAddonChange(AddonWebConfigMode.COLLECTIONS_ONLY, proposedChange, state)
        // null = no change at confirm (AddonManagerViewModel.confirmPendingChange).
        assertEquals(null, sanitized.proposedFollowAddonsOrder)
        assertEquals(
            true,
            sanitizePendingAddonChange(AddonWebConfigMode.FULL, proposedChange, state).proposedFollowAddonsOrder
        )
    }

    @Test
    fun `addon update drops urls the tv install would reject and caps the list`() {
        var proposed: PendingAddonChange? = null
        val server = AddonConfigServer(
            token = TOKEN,
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = {
                PageState(
                    addons = listOf(AddonInfo(url = "custom-scheme://installed", name = "Old", description = null)),
                    catalogs = emptyList()
                )
            },
            onChangeProposed = { proposed = it }
        )
        val urls = listOf(
            "custom-scheme://installed",
            "stremio://s.example/manifest.json",
            "ftp://x.example",
            "javascript:alert(1)",
            "file:///data/data",
            "https://ok.example"
        ) + (1..150).map { "https://bulk$it.example" }
        val body = buildJsonObject { put("urls", JsonArray(urls.map { JsonPrimitive(it) })) }.toString()

        val response = server.serve(FakePostSession(body, uri = "/a/$TOKEN/api/addons"))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val kept = proposed?.proposedUrls.orEmpty()
        assertEquals(MAX_PROPOSED_ADDON_URLS, kept.size)
        assertEquals(
            listOf("custom-scheme://installed", "https://s.example/manifest.json", "https://ok.example"),
            kept.take(3)
        )
        assertTrue(kept.none { it.startsWith("ftp:") || it.startsWith("javascript:") || it.startsWith("file:") })
    }

    @Test
    fun `sanitizer rewrites stremio before dedupe and keeps only installed unaccepted urls`() {
        val kept = sanitizeProposedAddonUrls(
            urls = listOf(
                "stremio://s.example",
                "https://s.example",
                "custom-scheme://installed",
                "custom-scheme://other",
                "HTTPS://upper.example"
            ),
            installedUrls = setOf("custom-scheme://installed")
        )

        assertEquals(listOf("https://s.example", "custom-scheme://installed"), kept)
    }

    @Test
    fun `requests under a foreign host header are rejected before routing`() {
        var proposed = false
        val server = AddonConfigServer(
            token = TOKEN,
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = {
                PageState(
                    addons = listOf(AddonInfo(url = "https://secret.example/token", name = "A", description = null)),
                    catalogs = emptyList()
                )
            },
            onChangeProposed = { proposed = true }
        )

        val get = server.serve(FakeGetSession("/a/$TOKEN/api/state", host = "attacker.example:8080"))
        val post = server.serve(
            FakePostSession(
                """{"urls":[]}""",
                uri = "/a/$TOKEN/api/addons",
                host = "attacker.example:8080",
                origin = "http://attacker.example:8080"
            )
        )
        val noHost = server.serve(FakeGetSession("/a/$TOKEN/api/state", host = null))

        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, get.status)
        assertEquals(false, get.bodyText().contains("secret.example"))
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, post.status)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, noHost.status)
        assertEquals(false, proposed)
    }

    @Test
    fun `addon update parses well formed body`() {
        var proposed: PendingAddonChange? = null
        val server = AddonConfigServer(
            token = TOKEN,
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = { PageState(addons = emptyList(), catalogs = emptyList()) },
            onChangeProposed = { proposed = it }
        )

        val response = server.serve(
            FakePostSession(
                """{
                  "urls": [" https://a.example ", "https://b.example", "https://a.example", ""],
                  "catalogOrderKeys": ["k1", "k1"],
                  "disabledCatalogKeys": [],
                  "collections": [{"id": "c1", "title": "Mine", "position": 2}],
                  "disabledCollectionKeys": ["collection_c1"],
                  "followAddonsOrder": true,
                  "unknownFutureKey": {"ignored": true}
                }""",
                uri = "/a/$TOKEN/api/addons"
            )
        )

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val change = proposed
        assertNotNull(change)
        // Blank entries dropped, values trimmed, duplicates removed.
        assertEquals(listOf("https://a.example", "https://b.example"), change?.proposedUrls)
        assertEquals(listOf("k1"), change?.proposedCatalogOrderKeys)
        assertEquals(emptyList<String>(), change?.proposedDisabledCatalogKeys)
        assertEquals("[{\"id\":\"c1\",\"title\":\"Mine\",\"position\":2}]", change?.proposedCollectionsJson)
        assertEquals(listOf("collection_c1"), change?.proposedDisabledCollectionKeys)
        assertEquals(true, change?.proposedFollowAddonsOrder)
    }

    @Test
    fun `addon update treats json null collections as absent`() {
        // Gson read "collections": null as an absent field (no proposal); the
        // kotlinx path must not stringify JsonNull into a collections-wipe.
        var proposed: PendingAddonChange? = null
        val server = AddonConfigServer(
            token = TOKEN,
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = { PageState(addons = emptyList(), catalogs = emptyList()) },
            onChangeProposed = { proposed = it }
        )

        val response = server.serve(
            FakePostSession(
                """{"urls":["https://a.example"],"collections":null}""",
                uri = "/a/$TOKEN/api/addons"
            )
        )

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(null, proposed?.proposedCollectionsJson)
    }

    @Test
    fun `addon update rejects malformed body`() {
        var proposed = false
        val server = AddonConfigServer(
            token = TOKEN,
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = { PageState(addons = emptyList(), catalogs = emptyList()) },
            onChangeProposed = { proposed = true }
        )

        val response = server.serve(FakePostSession("{oops", uri = "/a/$TOKEN/api/addons"))

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertEquals(false, proposed)
    }

    @Test
    fun `page state serves gson compatible json shape`() {
        val server = AddonConfigServer(
            token = TOKEN,
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = {
                PageState(
                    addons = listOf(
                        AddonInfo(url = "https://addon.example", name = "Addon", description = null)
                    ),
                    catalogs = emptyList(),
                    followAddonsOrder = false
                )
            },
            onChangeProposed = {}
        )

        val response = server.serve(FakeGetSession("/a/$TOKEN/api/state"))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val body = response.data.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        assertTrue(body.contains("\"url\":\"https://addon.example\""))
        assertTrue(body.contains("\"name\":\"Addon\""))
        // Gson omitted nulls; kotlinx (explicitNulls=false) must keep omitting them.
        assertEquals(false, body.contains("\"description\""))
        // Gson emitted default-valued fields; encodeDefaults=true must keep doing so.
        assertTrue(body.contains("\"followAddonsOrder\":false"))
        assertTrue(body.contains("\"manifestAddonOrderKeys\":[]"))
    }

    private fun tokenServer(onChange: () -> Unit = {}) = AddonConfigServer(
        token = TOKEN,
        context = context,
        webConfigMode = AddonWebConfigMode.FULL,
        currentPageStateProvider = {
            PageState(
                addons = listOf(AddonInfo(url = "https://secret.example/token", name = "A", description = null)),
                catalogs = emptyList()
            )
        },
        onChangeProposed = { onChange() }
    )

    @Test
    fun `routes answer only under this session's token`() {
        val server = tokenServer()

        val open = server.serve(FakeGetSession("/a/$TOKEN/api/state"))
        val noToken = server.serve(FakeGetSession("/api/state"))
        val wrongToken = server.serve(FakeGetSession("/a/${"f".repeat(32)}/api/state"))
        val otherPrefix = server.serve(FakeGetSession("/b/$TOKEN/api/state"))

        assertEquals(NanoHTTPD.Response.Status.OK, open.status)
        assertTrue(open.bodyText().contains("secret.example"))
        for (response in listOf(noToken, wrongToken, otherPrefix)) {
            assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
            assertEquals(false, response.bodyText().contains("secret.example"))
        }
    }

    @Test
    fun `stale token post is drained and changes nothing`() {
        var proposed = false
        val server = tokenServer { proposed = true }
        val body = """{"urls":["https://a.example"]}""".toByteArray()
        val stale = FakeUploadSession("/a/${"0".repeat(32)}/api/addons", kind = null, body = body)

        val response = server.serve(stale)

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        assertEquals(0, stale.stream.available())
        assertEquals("close", response.getHeader("Connection"))
        assertEquals(false, proposed)
    }

    @Test
    fun `answers carry no-store and no-referrer and the url carries the token`() {
        val server = tokenServer()

        val response = server.serve(FakeGetSession("/a/$TOKEN/api/state"))

        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        assertEquals(null, response.getHeader("Connection"))
        val url = server.url("192.0.2.1")
        assertTrue(url, url.startsWith("http://192.0.2.1:") && url.endsWith("/a/$TOKEN/"))
    }

    @Test
    fun `page is served with and without the last slash and calls only relative paths`() {
        val server = AddonConfigServer(
            token = TOKEN,
            context = mockk(relaxed = true),
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = { PageState(addons = emptyList(), catalogs = emptyList()) },
            onChangeProposed = {}
        )

        for (uri in listOf("/a/$TOKEN/", "/a/$TOKEN")) {
            val page = server.serve(FakeGetSession(uri))
            assertEquals(NanoHTTPD.Response.Status.OK, page.status)
            assertNoAbsoluteSameOriginPath(page.bodyText())
        }
    }

    @Test
    fun `status poll reads the change posted under the token`() {
        val server = tokenServer()
        val posted = server.serve(
            FakePostSession("""{"urls":["https://a.example"]}""", uri = "/a/$TOKEN/api/addons")
        )
        val id = Regex(""""id":"([^"]+)"""").find(posted.bodyText())!!.groupValues[1]

        val status = server.serve(FakeGetSession("/a/$TOKEN/api/status/$id"))

        assertEquals("""{"status":"pending"}""", status.bodyText())
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
