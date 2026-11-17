package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.core.network.access.AccessHeader
import com.nuvio.tv.core.network.access.AccessSaveResult
import com.nuvio.tv.core.network.access.AccessSite
import com.nuvio.tv.core.network.access.AccessSiteProblem
import com.nuvio.tv.core.network.access.AccessSiteRules
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec 97 A5: the phone page's token, guard, body rules, A1 check, save outcomes and close. */
class AccessHeaderServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val saved = mutableListOf<AccessSite>()
    private var result = AccessSaveResult.SAVED
    private val started = mutableListOf<AccessHeaderServer>()

    @After
    fun stopServers() = started.forEach { it.stop() }

    private fun newServer(
        stopGraceMs: Long = 0,
        save: (AccessSite) -> AccessSaveResult = { site -> saved += site; result }
    ) = AccessHeaderServer(token, { "<html>page</html>" }, save, "127.0.0.1", 0, stopGraceMs, 5_000)

    private fun post(
        server: AccessHeaderServer,
        body: String = SITE_JSON,
        declaredLength: String? = body.toByteArray().size.toString(),
        path: String = "/h/$token/site",
        origin: String? = null
    ): NanoHTTPD.Response = server.serve(
        FakeUploadSession(path, kind = null, body = body.toByteArray(), declaredLength = declaredLength, origin = origin)
    )

    private fun get(server: AccessHeaderServer, path: String, host: String = FAKE_HOST): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, method = NanoHTTPD.Method.GET, kind = null, host = host))

    private fun json(response: NanoHTTPD.Response) = Json.parseToJsonElement(response.bodyText()).jsonObject

    @Test
    fun `the page answers only under its token, with the token-path headers and the CSP`() {
        val server = newServer()
        val page = get(server, "/h/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertEquals(INLINE_PAGE_CSP, page.getHeader("Content-Security-Policy"))
        assertEquals("no-store", page.getHeader("Cache-Control"))
        assertEquals("no-referrer", page.getHeader("Referrer-Policy"))
        assertEquals(Status.NOT_FOUND, get(server, "/h/ffffffffffffffffffffffffffffffff/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/k/$token/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/h/$token/site").status)
        assertEquals(Status.NOT_FOUND, post(server, path = "/h/$token/").status)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a send under another token answers 404 after draining its body`() {
        val server = newServer()
        val stale = FakeUploadSession("/h/ffffffffffffffffffffffffffffffff/site", kind = null, body = SITE_JSON.toByteArray())
        val response = server.serve(stale)
        assertEquals(Status.NOT_FOUND, response.status)
        assertEquals(0, stale.stream.available())
        assertEquals("close", response.getHeader("Connection"))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the guard runs before the token and the save`() {
        val server = newServer()
        assertEquals(Status.FORBIDDEN, get(server, "/h/$token/", host = "evil.example").status)
        assertEquals(Status.FORBIDDEN, post(server, origin = "http://evil.example").status)
        assertTrue(saved.isEmpty())
        assertEquals(Status.OK, post(server, origin = "http://127.0.0.1:8140").status)
        // Wrong token and a foreign host: the guard answers first (403, not the token's 404).
        assertEquals(Status.FORBIDDEN, get(server, "/h/wrong/", host = "evil.example").status)
    }

    @Test
    fun `a valid site is saved canonical and the answer carries no value`() {
        val server = newServer()
        val response = post(server)
        assertEquals(Status.OK, response.status)
        val site = saved.single()
        assertEquals("watch.kamal.ca", site.host)
        assertEquals(443, site.port)
        assertEquals(HEADERS, site.headers)
        val body = response.bodyText()
        assertFalse(body, SECRET in body)
        val answer = Json.parseToJsonElement(body).jsonObject
        assertEquals("watch.kamal.ca", answer.getValue("host").jsonPrimitive.content)
        assertEquals(2, answer.getValue("headers").jsonPrimitive.int)
        assertEquals("close", response.getHeader("Connection"))
    }

    @Test
    fun `a site failing A1 answers 422 with its problem and header, unsaved`() {
        val server = newServer()
        val http = post(server, body = siteJson("http://192.0.2.50:8096"))
        assertEquals(422, http.status.requestStatus)
        val httpAnswer = json(http)
        assertEquals("NOT_HTTPS", httpAnswer.getValue("problem").jsonPrimitive.content)
        assertNull(httpAnswer["header"])

        val reserved = post(server, body = siteJson(headers = listOf(AccessHeader("X-Ok", "1"), AccessHeader("Cookie", "a=b"))))
        assertEquals(422, reserved.status.requestStatus)
        val reservedAnswer = json(reserved)
        assertEquals("RESERVED_NAME", reservedAnswer.getValue("problem").jsonPrimitive.content)
        assertEquals(1, reservedAnswer.getValue("header").jsonPrimitive.int)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `no answer echoes a value, whatever its status`() {
        val server = newServer()
        val badValue = post(server, body = siteJson(headers = listOf(AccessHeader("X-Key", "$SECRET\u00e9"))))
        assertEquals(422, badValue.status.requestStatus)
        AccessSaveResult.entries.forEach { outcome ->
            result = outcome
            val body = post(server).bodyText()
            assertFalse(body, SECRET in body)
        }
        assertFalse(SECRET in badValue.bodyText())
    }

    @Test
    fun `a site at A1's caps fits the body cap`() {
        val headers = (1..AccessSiteRules.MAX_HEADERS).map { i ->
            AccessHeader("X-${"n".repeat(AccessSiteRules.MAX_NAME_CHARS - 3)}$i".take(AccessSiteRules.MAX_NAME_CHARS), "\"".repeat(AccessSiteRules.MAX_VALUE_CHARS))
        }
        val body = siteJson(headers = headers)
        assertTrue(body.toByteArray().size <= AccessHeaderServer.MAX_SITE_BODY_BYTES)
        assertEquals(Status.OK, post(newServer(), body = body).status)
    }

    @Test
    fun `each save outcome has its status`() {
        val server = newServer()
        val expected = mapOf(
            AccessSaveResult.SAVED to 200,
            AccessSaveResult.TOO_MANY_SITES to 409,
            AccessSaveResult.UNREADABLE to 503,
            AccessSaveResult.INVALID to 500,
            AccessSaveResult.FAILED to 500
        )
        AccessSaveResult.entries.forEach { outcome ->
            result = outcome
            assertEquals(outcome.name, expected.getValue(outcome), post(server).status.requestStatus)
        }
    }

    @Test
    fun `bodies that are not one site answer 400, 411 and 413 without a save`() {
        val server = newServer()
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = null).status)
        assertEquals(
            Status.PAYLOAD_TOO_LARGE,
            post(server, declaredLength = (AccessHeaderServer.MAX_SITE_BODY_BYTES + 1).toString()).status
        )
        assertEquals(Status.BAD_REQUEST, post(server, declaredLength = (SITE_JSON.length + 10).toString()).status)
        listOf("", "not json", "[]", """{"site":"https://a.example"}""", """{"site":1,"headers":[]}""",
            """{"site":"https://a.example","headers":[{"name":"X"}]}""").forEach { body ->
            assertEquals(body, Status.BAD_REQUEST, post(server, body = body).status)
        }
        val dropped = FakeUploadSession("/h/$token/site", kind = null, body = SITE_JSON.toByteArray(), failAfter = 4)
        assertEquals(Status.BAD_REQUEST, server.serve(dropped).status)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `after close a send answers 410 at once, drained and unsaved`() {
        val server = newServer()
        server.close()
        assertTrue(server.isClosed)
        val late = FakeUploadSession("/h/$token/site", kind = null, body = SITE_JSON.toByteArray())
        val response = server.serve(late)
        assertEquals(Status.GONE, response.status)
        assertEquals(0, late.stream.available())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a close during a save still delivers the answer, then the server stops`() {
        val saving = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val server = newServer(stopGraceMs = 200) { site ->
            saved += site
            saving.countDown()
            finish.await(5, TimeUnit.SECONDS)
            AccessSaveResult.SAVED
        }.also(::startOnLoopback)
        var reply = ""
        val client = Thread { reply = rawPost(server.listeningPort) }.apply { start() }
        assertTrue(saving.await(5, TimeUnit.SECONDS))

        server.close()
        Thread.sleep(300)
        assertTrue(server.isAlive)
        finish.countDown()
        client.join(5_000)

        assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (server.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(server.isAlive)
    }

    @Test
    fun `the page escapes every text and leaves no template behind`() {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "it's"
            every { getString(any(), *anyVararg()) } returns "it's"
            every { resources.getQuantityString(any(), any(), *anyVararg()) } returns "\u0001 gets 'these'"
        }
        val html = AccessHeaderWebPage.html(context)
        assertNoAbsoluteSameOriginPath(html)
        // Every problem the server can name has its line on the page; NOT_HTTPS has the https frame.
        AccessSiteProblem.entries.filter { it != AccessSiteProblem.NOT_HTTPS }
            .forEach { assertTrue(it.name, "  ${it.name}: '" in html) }
        assertTrue("problem === 'NOT_HTTPS'" in html)
        assertFalse("an unescaped quote", "'it's'" in html)
        assertTrue("it\\'s" in html)
        assertFalse("a Kotlin template left in", "\${" in html)
        assertTrue("const MAX_HEADERS = ${AccessSiteRules.MAX_HEADERS}" in html)
    }

    private fun startOnLoopback(server: AccessHeaderServer) {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        started += server
    }

    private fun rawPost(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val body = SITE_JSON.toByteArray(StandardCharsets.UTF_8)
        val head = "POST /h/$token/site HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
        val received = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = socket.getInputStream().read(buffer)
                if (read <= 0) break
                received.write(buffer, 0, read)
            }
        } catch (_: IOException) {
        }
        received.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val SECRET = "s3cr3t-value-1234567890"

        val HEADERS = listOf(
            AccessHeader("CF-Access-Client-Id", "8f3c2e1a9b7d4c60.access"),
            AccessHeader("CF-Access-Client-Secret", SECRET)
        )

        fun siteJson(site: String = "https://Watch.Kamal.ca/path?q=1", headers: List<AccessHeader> = HEADERS): String =
            buildJsonObject {
                put("site", JsonPrimitive(site))
                put("headers", JsonArray(headers.map {
                    buildJsonObject {
                        put("name", JsonPrimitive(it.name))
                        put("value", JsonPrimitive(it.value))
                    }
                }))
            }.toString()

        val SITE_JSON = siteJson()
    }
}
