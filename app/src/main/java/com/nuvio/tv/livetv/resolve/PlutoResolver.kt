package com.nuvio.tv.livetv.resolve

import com.nuvio.tv.core.di.NetworkModule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Pluto TV (Spec 72 A0.1): one anonymous boot gives a session JWT valid for every channel; the
 * stream is the stitcher's master playlist for the channel id with that JWT. The session lives
 * in memory only, reused until the earlier of the JWT's lifetime and the boot's `refreshInSec`,
 * minus [EXPIRY_MARGIN]. The JWT carries the viewer's IP and location: it is never logged,
 * persisted or written anywhere but the play URL.
 */
internal class PlutoResolver(
    private val http: ResolverHttp,
    private val time: TimeSource.WithComparableMarks
) {
    private class Session(val token: String, val stitcherHost: String, val params: String?, val expiresAt: ComparableTimeMark)

    private val mutex = Mutex()
    private var session: Session? = null
    private val clientId = UUID.randomUUID().toString()

    /** The stitcher URL for [channelId] (24 hex); [fresh] skips the cached session. */
    suspend fun resolve(channelId: String, fresh: Boolean): Resolved {
        // One boot in flight: a caller waiting here reuses the session the first one booted.
        val (current, cached) = mutex.withLock {
            val reusable = session?.takeUnless { fresh || it.expiresAt.hasPassedNow() }
            if (reusable != null) reusable to true else boot().also { session = it } to false
        }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(current.stitcherHost)
            .addPathSegments("v2/stitch/hls/channel/$channelId/master.m3u8")
            .encodedQuery(current.params)
            .addQueryParameter("jwt", current.token)
            .addQueryParameter("masterJWTPassthrough", "true")
            .addQueryParameter("includeExtendedEvents", "true")
            .build()
        return Resolved(url.toString(), fromCache = cached)
    }

    private suspend fun boot(): Session {
        val url = BOOT_URL.newBuilder().addQueryParameter("clientID", clientId).build()
        val request = Request.Builder().url(url).header("User-Agent", ResolverHttp.BROWSER_USER_AGENT).build()
        val boot = http.execute(request) { response ->
            if (!response.isSuccessful) throw ResolveException("Pluto boot HTTP ${response.code}")
            json.decodeFromString<BootDto>(ResolverHttp.text(response))
        }
        val token = boot.sessionToken?.takeIf { JWT.matches(it) } ?: throw ResolveException("Pluto boot: no token")
        // The stitcher host is remote data: https on a pluto.tv host, or no resolve.
        val stitcher = boot.servers?.stitcher?.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host.endsWith(".pluto.tv") }
            ?: throw ResolveException("Pluto boot: no stitcher")
        val lifetimeSec = listOfNotNull(jwtLifetimeSec(token), boot.refreshInSec?.takeIf { it > 0 }).minOrNull() ?: 0L
        return Session(token, stitcher.host, boot.stitcherParams?.ifBlank { null }, time.markNow() + lifetimeSec.seconds - EXPIRY_MARGIN)
    }

    @Serializable
    private class BootDto(
        val sessionToken: String? = null,
        val servers: ServersDto? = null,
        val stitcherParams: String? = null,
        val refreshInSec: Long? = null
    )

    @Serializable
    private class ServersDto(val stitcher: String? = null)

    @Serializable
    private class JwtClaims(val exp: Long? = null, val iat: Long? = null)

    companion object {
        private val EXPIRY_MARGIN = 10.minutes

        private val BOOT_URL: HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("boot.pluto.tv")
            .addPathSegments("v4/start")
            .addQueryParameter("appName", "web")
            .addQueryParameter("appVersion", "9.0.0")
            .addQueryParameter("deviceVersion", "120.0.0")
            .addQueryParameter("deviceModel", "web")
            .addQueryParameter("deviceMake", "chrome")
            .addQueryParameter("deviceType", "web")
            .addQueryParameter("clientModelNumber", "1.0.0")
            .addQueryParameter("serverSideAds", "false")
            .addQueryParameter("drmCapabilities", "widevine:L3")
            .build()

        /** Three base64url parts: also keeps the token from adding query syntax to the play URL. */
        private val JWT = Regex("""[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

        private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        private val json = NetworkModule.provideWireJson()

        /** The JWT's own lifetime (`exp - iat`, server clock only); null when unreadable. */
        fun jwtLifetimeSec(token: String): Long? {
            val payload = token.split('.').getOrNull(1) ?: return null
            val claims = try {
                json.decodeFromString<JwtClaims>(base64Url.decode(payload).toString(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                // Bad base64 and SerializationException (an IllegalArgumentException) alike.
                return null
            }
            val exp = claims.exp ?: return null
            val iat = claims.iat ?: return null
            return (exp - iat).takeIf { it > 0 }
        }
    }
}
