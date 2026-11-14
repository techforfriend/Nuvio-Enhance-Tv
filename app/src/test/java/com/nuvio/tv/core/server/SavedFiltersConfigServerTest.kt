package com.nuvio.tv.core.server

import com.nuvio.tv.core.stream.SavedFilterEdit
import com.nuvio.tv.core.stream.SavedStreamFilters
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 S9: the saved-filters page's token, guard, body rules and answers. */
class SavedFiltersConfigServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private var rows: List<SavedFilterRow>? = listOf(
        SavedFilterRow("a", "4K DV remux", "4K · Dolby Vision · Remux"),
        SavedFilterRow("b", "Atmos \"loud\" </script>", "Atmos")
    )
    private val applied = mutableListOf<Pair<List<SavedFilterEdit>, Set<String>>>()
    private var stored: List<SavedFilterRow>? = listOf(SavedFilterRow("b", "Movie night", "Atmos"))

    private val server = SavedFiltersConfigServer(
        token = token,
        pageHtml = { "<html>page</html>" },
        rows = { rows },
        applyEdits = { order, deleted ->
            applied += order to deleted
            stored
        },
        hostname = "127.0.0.1",
        port = 0
    )

    private fun get(path: String, host: String? = FAKE_HOST): NanoHTTPD.Response = server.serve(FakeGetSession(path, host))

    private fun post(
        body: String,
        path: String = "/f/$token/save",
        declaredLength: Int? = null,
        origin: String? = null
    ): NanoHTTPD.Response = server.serve(FakePostSession(body, path, declaredLength, origin = origin))

    @Test
    fun `the page and the list answer only under the token`() {
        val page = get("/f/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertTrue(page.getHeader("Content-Security-Policy").startsWith("default-src 'none'"))
        assertEquals(Status.OK, get("/f/$token").status)
        assertEquals(Status.OK, get("/f/$token/filters").status)
        listOf("/", "/f/", "/f/ffffffffffffffffffffffffffffffff/", "/f/ffffffffffffffffffffffffffffffff/filters", "/k/$token/", "/f/$token/save", "/f/$token/other")
            .forEach { path -> assertEquals(path, Status.NOT_FOUND, get(path).status) }
    }

    @Test
    fun `the list is JSON that keeps a name's quotes and markup as text`() {
        val response = get("/f/$token/filters")
        assertEquals("application/json; charset=utf-8", response.mimeType)
        assertEquals(
            """[{"id":"a","name":"4K DV remux","summary":"4K · Dolby Vision · Remux"},""" +
                """{"id":"b","name":"Atmos \"loud\" </script>","summary":"Atmos"}]""",
            response.bodyText()
        )
    }

    @Test
    fun `every answer is uncached and sends no referrer, and a POST closes its connection`() {
        val save = post("""{"order":[],"deleted":[]}""")
        listOf(get("/f/$token/"), get("/f/$token/filters"), get("/nope"), save).forEach { response ->
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        }
        assertEquals("close", save.getHeader("Connection"))
        assertNull(get("/f/$token/").getHeader("Connection"))
    }

    @Test
    fun `a save hands the order and the deletions on and answers with what was stored`() {
        val response = post("""{"order":[{"id":"b","name":"Movie night"},{"id":"a"}],"deleted":["a","a"],"extra":{"x":[1]}}""")
        assertEquals(Status.OK, response.status)
        assertEquals("""[{"id":"b","name":"Movie night","summary":"Atmos"}]""", response.bodyText())
        assertEquals(
            listOf(listOf(SavedFilterEdit("b", "Movie night"), SavedFilterEdit("a", "")) to setOf("a")),
            applied
        )
    }

    @Test
    fun `a body that is not the page's shape is refused and nothing is applied`() {
        val tooMany = (0..SavedStreamFilters.MAX_FILTERS).joinToString(",") { """{"id":"f$it","name":"n"}""" }
        val tooManyDeleted = (0..SavedStreamFilters.MAX_FILTERS).joinToString(",") { "\"f$it\"" }
        listOf(
            "",
            "not json",
            "[]",
            """{"order":"a"}""",
            """{"order":[{"name":"no id"}]}""",
            """{"order":[{"id":7,"name":{}}]}""",
            """{"order":[$tooMany]}""",
            """{"deleted":[$tooManyDeleted]}"""
        ).forEach { body ->
            val response = post(body)
            assertEquals(body, Status.BAD_REQUEST, response.status)
            // The answer never quotes the body back.
            assertEquals("bad request", response.bodyText())
        }
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a list of exactly the cap is accepted`() {
        val full = (1..SavedStreamFilters.MAX_FILTERS).joinToString(",") { """{"id":"f$it","name":"n"}""" }
        val fullDeleted = (1..SavedStreamFilters.MAX_FILTERS).joinToString(",") { "\"f$it\"" }
        assertEquals(Status.OK, post("""{"order":[$full],"deleted":[$fullDeleted]}""").status)
        assertEquals(SavedStreamFilters.MAX_FILTERS, applied.single().first.size)
    }

    @Test
    fun `a body declared over the cap is refused before it is read`() {
        val response = post("{}", declaredLength = SavedFiltersConfigServer.MAX_EDIT_BODY_BYTES + 1)
        assertEquals(Status.PAYLOAD_TOO_LARGE, response.status)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a save under another token or path answers 404 and applies nothing`() {
        assertEquals(Status.NOT_FOUND, post("""{"order":[]}""", path = "/f/ffffffffffffffffffffffffffffffff/save").status)
        assertEquals(Status.NOT_FOUND, post("""{"order":[]}""", path = "/f/$token/filters").status)
        assertEquals(Status.NOT_FOUND, post("""{"order":[]}""", path = "/f/$token/").status)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `another host, or a POST from another origin, is refused before the token is read`() {
        assertEquals(Status.FORBIDDEN, get("/f/$token/", host = "evil.example:8130").status)
        assertEquals(Status.FORBIDDEN, get("/f/$token/filters", host = null).status)
        assertEquals(Status.FORBIDDEN, post("""{"order":[]}""", origin = "http://evil.example").status)
        assertTrue(applied.isEmpty())
        assertEquals(Status.OK, post("""{"order":[]}""", origin = "http://127.0.0.1:8130").status)
    }

    @Test
    fun `a store that cannot be read or written answers 500 without detail`() {
        rows = null
        stored = null
        listOf(get("/f/$token/filters"), post("""{"order":[]}""")).forEach { response ->
            assertEquals(Status.INTERNAL_ERROR, response.status)
            assertEquals("store unavailable", response.bodyText())
        }
    }

    @Test
    fun `the QR address carries the token`() {
        assertTrue(server.url("192.0.2.119").endsWith("/f/$token/"))
        assertTrue(server.url("192.0.2.119").startsWith("http://192.0.2.119:"))
    }
}
