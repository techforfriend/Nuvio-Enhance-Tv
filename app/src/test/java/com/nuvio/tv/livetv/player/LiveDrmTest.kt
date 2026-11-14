package com.nuvio.tv.livetv.player

import com.nuvio.tv.livetv.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveDrmTest {

    private val kid = "000102030405060708090a0b0c0d0e0f"
    private val key = "00112233445566778899aabbccddeeff"

    private fun props(vararg lines: String): Map<String, String> =
        lines.associate { "inputstream.adaptive." + it.substringBefore('=') to it.substringAfter('=') }

    private fun b64u(hex: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    @Test
    fun `old properties - Widevine licence URL and headers, raw post and response`() {
        val drm = LiveDrm.from(
            props(
                "license_type=com.widevine.alpha",
                "license_key=https://lic.example/wv?t=1|Content-Type=application%2Foctet-stream&User-Agent=UA+1|R{SSM}|"
            )
        )!!
        assertEquals(LiveDrm.WIDEVINE, drm.keySystem)
        assertEquals("https://lic.example/wv?t=1", drm.licenseUrl)
        assertEquals(mapOf("Content-Type" to "application/octet-stream", "User-Agent" to "UA 1"), drm.licenseHeaders)
        assertTrue(drm.clearKeys.isEmpty())
        assertFalse(drm.wrapped)
    }

    @Test
    fun `old properties - wrapped requests and responses are flagged`() {
        fun wrapped(licenseKey: String) = LiveDrm.from(props("license_type=widevine", "license_key=$licenseKey"))!!.wrapped
        assertFalse(wrapped("https://lic.example"))
        assertFalse(wrapped("https://lic.example||R{SSM}|R"))
        assertTrue(wrapped("https://lic.example||b{SSM}|"))
        assertTrue(wrapped("https://lic.example||{\"challenge\":\"b{SSM}\"}|JBlicense"))
        assertTrue(wrapped("https://lic.example?c={SSM}||R{SSM}|"))
        assertTrue(wrapped("https://lic.example?k={KID}"))
        assertFalse(wrapped("https://lic.example||R%7BSSM%7D|")) // URL-encoded R{SSM}
        assertTrue(LiveDrm.from(props("license_type=widevine", "license_key=https://lic.example", "license_url_append=?c={CHA-B64U}"))!!.wrapped)
    }

    @Test
    fun `non-http licence URLs are not kept, in every form`() {
        assertNull(LiveDrm.from(props("license_type=widevine", "license_key=file:///sdcard/x"))!!.licenseUrl)
        assertNull(LiveDrm.from(props("license_type=widevine", "license_url=javascript:x"))!!.licenseUrl)
        assertNull(LiveDrm.from(props("drm_legacy=com.widevine.alpha|ftp://x"))!!.licenseUrl)
        assertNull(LiveDrm.from(props("""drm={"com.widevine.alpha":{"license":{"server_url":"content://x"}}}"""))!!.licenseUrl)
    }

    @Test
    fun `toString never shows the licence URL, headers or keys`() {
        val drm = LiveDrm.from(props("drm_legacy=org.w3.clearkey|$kid:$key"))!!
        val secret = LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://lic.example/?token=SECRET|X-Auth=SECRET"))!!
        val odd = LiveDrm.from(props("license_type=token-SECRET"))!!
        for (text in listOf(drm.toString(), secret.toString(), odd.toString())) {
            assertFalse(text, key in text || kid in text || "SECRET" in text || "lic.example" in text)
        }
    }

    @Test
    fun `old properties - license_url and license_url_append, aliases`() {
        val drm = LiveDrm.from(
            props("license_type=PlayReady", "license_key=https://ignored.example|X-A=1", "license_url=https://lic.example/pr", "license_url_append=?id=7")
        )!!
        assertEquals(LiveDrm.PLAYREADY, drm.keySystem)
        assertEquals("https://lic.example/pr?id=7", drm.licenseUrl)
        assertEquals(mapOf("X-A" to "1"), drm.licenseHeaders)
        assertNull(LiveDrm.from(props("license_type=com.widevine.alpha"))!!.licenseUrl) // the manifest's own
    }

    @Test
    fun `old properties - ClearKey keys in every OTT Navigator form`() {
        val want = mapOf(kid to key)
        fun keys(licenseKey: String) = LiveDrm.from(props("license_type=clearkey", "license_key=$licenseKey"))!!.clearKeys
        assertEquals(want, keys("$kid:$key"))
        assertEquals(want, keys("${kid.uppercase()}:$key,bad:pair,${kid}:short"))
        assertEquals(want, keys("00010203-0405-0607-0809-0a0b0c0d0e0f:$key")) // UUID-style key id
        assertEquals(want, keys("""{"$kid":"$key"}"""))
        assertEquals(want, keys("""{"keys":[{"kty":"oct","kid":"${b64u(kid)}","k":"${b64u(key)}"}],"type":"temporary"}"""))
        val withUrl = LiveDrm.from(props("license_type=clearkey", "license_key=$kid:$key", "license_url=https://ck.example"))!!
        assertEquals(want, withUrl.clearKeys)
        assertEquals("https://ck.example", withUrl.licenseUrl)
        val badUrl = LiveDrm.from(props("license_type=clearkey", "license_key=$kid:$key", "license_url=javascript:x"))!!
        assertNull(badUrl.licenseUrl)
        assertTrue(LiveDrm.from(props("license_type=clearkey", "license_key=$kid:$key", "license_url=https://ck.example/{KID}"))!!.wrapped)
        // A non-http license_url does not hide license_key's URL.
        assertEquals("https://wv.example", LiveDrm.from(props("license_type=widevine", "license_key=https://wv.example", "license_url=x"))!!.licenseUrl)
        val viaServer = LiveDrm.from(props("license_type=org.w3.clearkey", "license_key=https://ck.example/lic"))!!
        assertEquals("https://ck.example/lic", viaServer.licenseUrl)
        assertTrue(viaServer.clearKeys.isEmpty())
    }

    @Test
    fun `drm_legacy - licence URL with headers, kid key pairs, data URI`() {
        val wv = LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://lic.example/a|User-Agent=Mozilla%2F5.0+X&Referer=https%3A%2F%2Fr.example%2F"))!!
        assertEquals(LiveDrm.WIDEVINE, wv.keySystem)
        assertEquals("https://lic.example/a", wv.licenseUrl)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0 X", "Referer" to "https://r.example/"), wv.licenseHeaders)

        val ck = LiveDrm.from(props("drm_legacy=org.w3.clearkey|$kid:$key"))!!
        assertNull(ck.licenseUrl)
        assertEquals(mapOf(kid to key), ck.clearKeys)

        val jwks = """{"keys":[{"kty":"oct","kid":"${b64u(kid)}","k":"${b64u(key)}"}]}"""
        val data = "data:application/json;base64," + Base64.getEncoder().encodeToString(jwks.toByteArray())
        assertEquals(mapOf(kid to key), LiveDrm.from(props("drm_legacy=org.w3.clearkey|$data"))!!.clearKeys)
        val plainData = "data:application/json," + java.net.URLEncoder.encode(" $jwks ", "UTF-8").replace("+", "%20")
        assertEquals(mapOf(kid to key), LiveDrm.from(props("drm_legacy=org.w3.clearkey|$plainData"))!!.clearKeys)

        // A literal '+' in a percent-encoded payload stays '+' (base64url has none, so a JWK set via kid:key pairs' JSON form).
        assertTrue(LiveDrm.from(props("drm_legacy=org.w3.clearkey|data:,{\"keys\":[]}+"))!!.clearKeys.isEmpty())
        // A data: URI inside a data: URI holds no keys, even when the inner one would.
        val inner = "data:application/json;base64," + Base64.getEncoder().encodeToString(jwks.toByteArray())
        val outer = "data:application/json;base64," + Base64.getEncoder().encodeToString(inner.toByteArray())
        assertTrue(LiveDrm.from(props("drm_legacy=org.w3.clearkey|$outer"))!!.clearKeys.isEmpty())
        // Keys are read for ClearKey only; a placeholder in the legacy URL is flagged.
        assertTrue(LiveDrm.from(props("drm_legacy=com.widevine.alpha|$kid:$key"))!!.clearKeys.isEmpty())
        assertTrue(LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://lic.example/{CHA-B64U}"))!!.wrapped)

        val bare = LiveDrm.from(props("drm_legacy=org.w3.clearkey"))!!
        assertNull(bare.licenseUrl)
        assertTrue(bare.clearKeys.isEmpty())
    }

    @Test
    fun `drm JSON - Widevine or ClearKey preferred, req fields read, wrappers flagged`() {
        val drm = LiveDrm.from(
            props(
                """drm={"com.microsoft.playready":{"license":{"server_url":"https://pr.example"}},""" +
                    """"none":{},"com.widevine.alpha":{"license":{"server_url":"https://wv.example/lic","req_params":"?x=1",""" +
                    """"req_headers":"X-Token=a%26b"}}}"""
            )
        )!!
        assertEquals(LiveDrm(LiveDrm.WIDEVINE, "https://wv.example/lic?x=1", mapOf("X-Token" to "a&b"), emptyMap(), false), drm)

        val pr = LiveDrm.from(props("""drm={"none":{},"com.microsoft.playready":{}}"""))!!
        assertEquals(LiveDrm.PLAYREADY, pr.keySystem)
        assertNull(pr.licenseUrl)

        val ck = LiveDrm.from(props("""drm={"org.w3.clearkey":{"license":{"keyids":{"$kid":"$key","x":"y"}}}}"""))!!
        assertEquals(mapOf(kid to key), ck.clearKeys)
        assertTrue(LiveDrm.from(props("""drm={"com.widevine.alpha":{"license":{"keyids":{"$kid":"$key"}}}}"""))!!.clearKeys.isEmpty())

        fun wrapped(license: String) = LiveDrm.from(props("""drm={"com.widevine.alpha":{"license":$license}}"""))!!.wrapped
        assertFalse(wrapped("""{"server_url":"https://wv.example"}"""))
        assertFalse(wrapped("""{"server_url":"https://wv.example","wrapper":"","use_http_get_request":false}"""))
        assertTrue(wrapped("""{"server_url":"https://wv.example","req_data":"e30="}"""))
        assertTrue(wrapped("""{"server_url":"https://wv.example","req_params":"?c={CHA-B64U}"}"""))
        assertTrue(wrapped("""{"server_url":"https://wv.example","req_data":"e30=","wrapper":"base64"}"""))
        assertTrue(wrapped("""{"server_url":"https://wv.example","unwrapper":"json"}"""))
        assertTrue(wrapped("""{"server_url":"https://wv.example","use_http_get_request":true}"""))
        assertTrue(wrapped("""{"server_url":"https://wv.example/c/{CHA-B64U}"}"""))
    }

    @Test
    fun `newest form wins when several are written`() {
        val drm = LiveDrm.from(
            props(
                "license_type=com.widevine.alpha", "license_key=https://old.example",
                "drm_legacy=com.widevine.alpha|https://legacy.example",
                """drm={"com.widevine.alpha":{"license":{"server_url":"https://new.example"}}}"""
            )
        )!!
        assertEquals("https://new.example", drm.licenseUrl)
        val legacy = LiveDrm.from(
            props("license_type=com.widevine.alpha", "license_key=https://old.example", "drm_legacy=com.widevine.alpha|https://legacy.example", """drm={"none":{}}""")
        )!!
        assertEquals("https://legacy.example", legacy.licenseUrl)
    }

    @Test
    fun `licence headers are sanitised`() {
        val drm = LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://lic.example|X%20Bad=1&X-Utf=caf%C3%A9&X-Ok=1"))!!
        assertEquals(mapOf("X-Ok" to "1"), drm.licenseHeaders)
    }

    @Test
    fun `settings exist exactly when the parser marks the stream drm`() {
        val cases = listOf(
            listOf("license_type=com.widevine.alpha"),
            listOf("license_type=none", "license_key=https://keys.example"),
            listOf("license_key=https://keys.example/k"),
            listOf("license_type="),
            listOf("drm_legacy=none|https://keys.example"),
            listOf("drm_legacy=|x"),
            listOf("drm_legacy=com.huawei.wiseplay|https://wp.example"),
            listOf("""drm={"none":{"license":{"req_headers":"a=b"}}}"""),
            listOf("""drm={"org.w3.clearkey":{}}"""),
            listOf("drm=not json"),
            listOf("""drm=[{"com.widevine.alpha":{}}]""", "license_type=clearkey", "license_key=$kid:$key"),
            listOf("""drm={"com.widevine.alpha":"not an object"}"""),
            emptyList()
        )
        for (lines in cases) {
            val m3u = lines.joinToString("") { "#KODIPROP:inputstream.adaptive.$it\n" } + "#EXTINF:-1,C\nhttps://example.org/c.mpd\n"
            val entry = M3uParser.parse(m3u).entries.single()
            assertEquals(lines.toString(), entry.drm, LiveDrm.from(entry.kodiProps) != null)
        }
        assertNull(LiveDrm.from(null))
        assertEquals("com.huawei.wiseplay", LiveDrm.from(props("drm_legacy=WisePlay|x"))!!.keySystem)
        assertEquals("com.example.other", LiveDrm.from(props("license_type=com.example.OTHER"))!!.keySystem)
    }

    @Test
    fun `ClearKey keys become the W3C licence JSON, base64url without padding`() {
        val kid2 = "ffeeddccbbaa99887766554433221100"
        val json = LiveDrm.from(props("drm_legacy=org.w3.clearkey|$kid:$key,$kid2:$kid"))!!.clearKeyLicense()
        assertEquals(
            """{"keys":[{"kty":"oct","kid":"${b64u(kid)}","k":"${b64u(key)}"},""" +
                """{"kty":"oct","kid":"${b64u(kid2)}","k":"${b64u(kid)}"}],"type":"temporary"}""",
            json
        )
        assertFalse(json.contains('='))
    }

    @Test
    fun `key systems have the names people know, Media3 UUIDs where it has them`() {
        assertEquals("Widevine", LiveDrm.displayName(LiveDrm.WIDEVINE))
        assertEquals("PlayReady", LiveDrm.displayName(LiveDrm.PLAYREADY))
        assertEquals("ClearKey", LiveDrm.displayName(LiveDrm.CLEARKEY))
        assertEquals("WisePlay", LiveDrm.displayName(LiveDrm.WISEPLAY))
        assertEquals(androidx.media3.common.C.PLAYREADY_UUID, LiveMediaSources.uuidOf(LiveDrm.PLAYREADY))
        assertNull(LiveMediaSources.uuidOf("com.example.other"))
        assertNull(LiveDrm.displayName("com.example.other"))
        assertEquals(androidx.media3.common.C.WIDEVINE_UUID, LiveMediaSources.uuidOf(LiveDrm.WIDEVINE))
        assertEquals(androidx.media3.common.C.CLEARKEY_UUID, LiveMediaSources.uuidOf(LiveDrm.CLEARKEY))
        assertNull(LiveMediaSources.uuidOf(LiveDrm.WISEPLAY))
    }

    @Test
    fun `licence source - local keys, forced or manifest URL, headers merged licence-wins, no stream Authorization`() {
        val stream = mapOf("User-Agent" to "UA", "Referer" to "https://r.example/", "Authorization" to "Basic c3RyZWFt", "Cookie" to "s=1")
        val local = LiveMediaSources.licenceSource(LiveDrm.from(props("license_type=clearkey", "license_key=$kid:$key", "license_url=https://ck.example"))!!, stream)
        assertTrue(local is LiveMediaSources.LicenceSource.Local)
        assertEquals(LiveDrm.from(props("drm_legacy=org.w3.clearkey|$kid:$key"))!!.clearKeyLicense(), (local as LiveMediaSources.LicenceSource.Local).license)

        val server = LiveMediaSources.licenceSource(
            LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://lic.example/wv|user-agent=LicUA&X-Token=t"))!!, stream
        ) as LiveMediaSources.LicenceSource.Server
        assertEquals("https://lic.example/wv", server.url)
        assertEquals(mapOf("Referer" to "https://r.example/", "Cookie" to "s=1"), server.streamHeaders)
        assertEquals(mapOf("User-Agent" to "LicUA", "X-Token" to "t"), server.licenseHeaders)
        assertNull(server.userAgent) // the licence's own UA, never the app default on top

        val manifest = LiveMediaSources.licenceSource(LiveDrm.from(props("drm_legacy=com.widevine.alpha"))!!, stream) as LiveMediaSources.LicenceSource.Server
        assertNull(manifest.url)
        assertNull(manifest.userAgent) // the stream's UA travels
        val bare = LiveMediaSources.licenceSource(LiveDrm.from(props("drm_legacy=com.widevine.alpha"))!!, emptyMap())
            as LiveMediaSources.LicenceSource.Server
        assertEquals(com.nuvio.tv.core.player.PlayerMediaSourceFactory.DEFAULT_USER_AGENT, bare.userAgent)
        // Mixed-case names: a licence header beats the stream's of any spelling.
        val mixed = LiveMediaSources.licenceSource(
            LiveDrm.from(props("""drm={"com.widevine.alpha":{"license":{"req_headers":"x-token=lic"}}}"""))!!, mapOf("X-TOKEN" to "stream", "Cookie" to "c")
        ) as LiveMediaSources.LicenceSource.Server
        assertEquals(mapOf("Cookie" to "c"), mixed.streamHeaders)
        assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://r.example/", "Cookie" to "s=1"), manifest.streamHeaders)
        // A licence header may set Authorization itself.
        val auth = LiveMediaSources.licenceSource(LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://l.example|Authorization=Bearer%20x"))!!, stream)
            as LiveMediaSources.LicenceSource.Server
        assertEquals(mapOf("Authorization" to "Bearer x"), auth.licenseHeaders)
        assertFalse(server.toString().contains("lic.example") || local.toString().contains(kid))
    }

    @Test
    fun `a session manager builds for both licence sources`() {
        // Smoke only (no throw): the choices it makes are licenceSource's, tested above.
        val request = LiveMediaSources.LiveRequest("https://example.org/c.mpd", mapOf("User-Agent" to "UA"))
        LiveMediaSources.drmSessionManager(LiveDrm.from(props("drm_legacy=com.widevine.alpha|https://lic.example"))!!, request)
        LiveMediaSources.drmSessionManager(LiveDrm.from(props("drm_legacy=org.w3.clearkey|$kid:$key"))!!, request)
    }

    @Test
    fun `hostile values do not throw`() {
        val deep = "{\"a\":" + "[".repeat(5000) + "]".repeat(5000) + "}"
        val nested = "data:,".repeat(200_000) // once recursive: a stack overflow
        for (value in listOf(deep, nested, "data:;base64,!!!", "data:,data:,data:,", "{\"keys\":[1,{\"kid\":5}]}", "|||||", ":")) {
            assertTrue(LiveDrm.from(props("license_type=clearkey", "license_key=$value"))!!.clearKeys.isEmpty())
            assertTrue(LiveDrm.from(props("drm_legacy=org.w3.clearkey|$value"))!!.clearKeys.isEmpty())
            assertNotNull(LiveDrm.from(props("drm=$value", "license_type=widevine"))) // `{"keys":..}` names a key system "keys"
        }
    }
}
