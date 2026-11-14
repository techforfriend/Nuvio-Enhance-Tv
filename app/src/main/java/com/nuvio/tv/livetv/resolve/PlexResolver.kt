package com.nuvio.tv.livetv.resolve

import com.nuvio.tv.core.di.NetworkModule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/**
 * Plex free live TV (Spec 72 A0.1, D3): an anonymous token (in memory, [TOKEN_LIFETIME]) and the
 * channel's `library/parts/<key>.m3u8`, the pool key as-is. The parts URL is checked with one GET
 * (it redirects to the channel's playlist host), so a channel Plex no longer serves (404), a
 * refused token or a rate limit (429) falls back to the stored URL before anything plays. A
 * cached token refused with 401/403 gets one fresh token; nothing else is retried. The token is
 * never logged or persisted.
 */
internal class PlexResolver(
    private val http: ResolverHttp,
    private val time: TimeSource.WithComparableMarks
) {
    private class Token(val value: String, val expiresAt: ComparableTimeMark)

    private val mutex = Mutex()
    private var token: Token? = null
    private val clientId = UUID.randomUUID().toString().replace("-", "")

    /** The playable URL for parts key [partKey] (`<24hex>-<24hex>`); [fresh] skips the cached token. */
    suspend fun resolve(partKey: String, fresh: Boolean): Resolved {
        val (first, cached) = token(fresh)
        val code = when (val probe = probe(partKey, first)) {
            is Probe.Playable -> return Resolved(probe.url, fromCache = false)
            is Probe.Refused -> probe.code
        }
        if (!cached || code !in REFUSED_TOKEN_CODES) throw ResolveException("Plex parts HTTP $code")
        val (second, _) = token(fresh = true)
        return when (val retry = probe(partKey, second)) {
            is Probe.Playable -> Resolved(retry.url, fromCache = false)
            is Probe.Refused -> throw ResolveException("Plex parts HTTP ${retry.code}")
        }
    }

    /** The token and whether it came from the cache. One anonymous sign-in in flight. */
    private suspend fun token(fresh: Boolean): Pair<String, Boolean> = mutex.withLock {
        val reusable = token?.takeUnless { fresh || it.expiresAt.hasPassedNow() }
        if (reusable != null) return@withLock reusable.value to true
        val value = signIn()
        token = Token(value, time.markNow() + TOKEN_LIFETIME)
        value to false
    }

    private suspend fun signIn(): String {
        val url = ANONYMOUS_URL.newBuilder().addQueryParameter("X-Plex-Client-Identifier", clientId).build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ResolverHttp.BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody())
            .build()
        val value = http.execute(request) { response ->
            if (!response.isSuccessful) throw ResolveException("Plex sign-in HTTP ${response.code}")
            authToken(ResolverHttp.text(response))
        }
        return value ?: throw ResolveException("Plex sign-in: no token")
    }

    private sealed interface Probe {
        data class Playable(val url: String) : Probe
        data class Refused(val code: Int) : Probe
    }

    private suspend fun probe(partKey: String, token: String): Probe {
        val url = PARTS_URL.newBuilder()
            .addPathSegment("$partKey.m3u8")
            .addQueryParameter("X-Plex-Token", token)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", ResolverHttp.BROWSER_USER_AGENT).build()
        // The body is not read: the status and the URL after redirects are the answer.
        return http.execute(request) { response ->
            if (response.isSuccessful) Probe.Playable(response.request.url.toString()) else Probe.Refused(response.code)
        }
    }

    @Serializable
    private class AnonymousUserDto(val authToken: String? = null)

    companion object {
        private val TOKEN_LIFETIME = 5.hours
        private val REFUSED_TOKEN_CODES = setOf(401, 403)

        private val ANONYMOUS_URL: HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("clients.plex.tv")
            .addPathSegments("api/v2/users/anonymous")
            .addQueryParameter("X-Plex-Product", "Plex Web")
            .addQueryParameter("X-Plex-Version", "4.145.0")
            .addQueryParameter("X-Plex-Platform", "Chrome")
            .build()

        private val PARTS_URL: HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("epg.provider.plex.tv")
            .addPathSegments("library/parts")
            .build()

        /** Also keeps the token from adding query syntax to the parts URL. */
        private val TOKEN = Regex("""[A-Za-z0-9_-]{1,256}""")
        private val XML_TOKEN = Regex("""\bauthToken="([^"]*)"""")
        private val json = NetworkModule.provideWireJson()

        /** `authToken` from the JSON answer, else the XML one (Plex has sent XML whatever Accept says since 2026-08-09). */
        fun authToken(body: String): String? {
            val text = body.trimStart()
            val raw = if (text.startsWith("{")) {
                json.decodeFromString<AnonymousUserDto>(text).authToken
            } else {
                XML_TOKEN.find(text)?.groupValues?.get(1)
            }
            return raw?.takeIf { TOKEN.matches(it) }
        }
    }
}
