package com.nuvio.tv.livetv.resolve

import android.util.Log
import com.nuvio.tv.core.di.Validated
import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

/**
 * A stored stream as it will play: [stream] is the resolved stream, or the stored one unchanged;
 * [fromCache] = its URL rests on a token reused from an earlier resolve and not checked by this
 * one (the ladder grants such a stream a fresh resolve on a 401/403/404, Spec 72 D1).
 */
data class Resolution(val stream: LiveStream, val fromCache: Boolean = false)

/** A resolver's answer: the URL to play and whether its token came from the cache. */
internal data class Resolved(val url: String, val fromCache: Boolean)

/**
 * Turns a stored Live TV stream into the URL to play, at play time, on this device (Spec 72
 * A0.1, D1): Pluto (`jmp2.uk/plu-<id>.m3u8` or a Pluto stitcher URL) and Plex
 * (`jmp2.uk/plex-<A>-<B>.m3u8`) get a fresh tokenised URL; every other stream plays as stored.
 * Any resolver failure, or no answer within [RESOLVE_BUDGET_MS], plays the stored URL (today's
 * behaviour), so resolving only adds a path. The stored stream stays the identity (health,
 * "Stream n of m"); the resolved URL is never logged or persisted.
 */
@Singleton
class StreamResolver internal constructor(
    private val pluto: PlutoResolver,
    private val plex: PlexResolver,
    private val budgetMs: Long = RESOLVE_BUDGET_MS
) {
    @Inject
    constructor(@Validated client: OkHttpClient) : this(ResolverHttp(client), TimeSource.Monotonic)

    internal constructor(http: ResolverHttp, time: TimeSource.WithComparableMarks) :
        this(PlutoResolver(http, time), PlexResolver(http, time))

    /** [stream] ready to play; [fresh] skips cached tokens (the ladder's Reload). */
    suspend fun resolve(stream: LiveStream, fresh: Boolean): Resolution {
        val target = targetOf(stream.url) ?: return Resolution(stream)
        val resolved = try {
            withTimeoutOrNull(budgetMs) {
                when (target) {
                    is Target.Pluto -> pluto.resolve(target.channelId, fresh)
                    is Target.Plex -> plex.resolve(target.partKey, fresh)
                }
            }
        } catch (e: IOException) {
            fail(target, e)
        } catch (e: SerializationException) {
            fail(target, e)
        } catch (e: IllegalArgumentException) {
            fail(target, e)
        }
        if (resolved == null) {
            Log.w(TAG, "${target.name} resolve gave no URL, playing the stored one")
            return Resolution(stream)
        }
        // The stored headers were for the redirector; the platform URLs need none (browser UA by default).
        return Resolution(LiveStream(resolved.url), resolved.fromCache)
    }

    /** Logs the failure by class (its message may name a status code, never a URL or token). */
    private fun fail(target: Target, e: Exception): Resolved? {
        Log.w(TAG, "${target.name} resolve failed: ${e.javaClass.simpleName}")
        return null
    }

    internal sealed interface Target {
        val name: String

        data class Pluto(val channelId: String) : Target {
            override val name: String get() = "Pluto"
        }

        data class Plex(val partKey: String) : Target {
            override val name: String get() = "Plex"
        }
    }

    internal companion object {
        /** Leaves the stored URL a few seconds of the ladder's 10 s per-stream watchdog. */
        const val RESOLVE_BUDGET_MS = 6_000L
        private const val TAG = "LiveResolve"

        private val PLUTO_JMP2 = Regex("""https?://jmp2\.uk/plu-([0-9a-f]{24})\.m3u8""")
        private val PLUTO_STITCHER =
            Regex("""https?://[a-z0-9.-]+\.pluto\.tv/(?:[^?#]*/)?channel/([0-9a-f]{24})/master\.m3u8(?:[?#].*)?""")
        private val PLEX_JMP2 = Regex("""https?://jmp2\.uk/plex-([0-9a-f]{24}-[0-9a-f]{24})\.m3u8""")

        /** Which resolver a stored URL needs, or null to play it as stored. */
        fun targetOf(url: String): Target? {
            PLUTO_JMP2.matchEntire(url)?.let { return Target.Pluto(it.groupValues[1]) }
            PLUTO_STITCHER.matchEntire(url)?.let { return Target.Pluto(it.groupValues[1]) }
            PLEX_JMP2.matchEntire(url)?.let { return Target.Plex(it.groupValues[1]) }
            return null
        }
    }
}
