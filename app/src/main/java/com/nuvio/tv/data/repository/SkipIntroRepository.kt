package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.TheIntroDbApi
import com.nuvio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nuvio.tv.data.remote.api.TheIntroDbStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds; OPEN_END = to the end of the file
    val type: String,      // "intro", "recap", "outro"
    val provider: String   // "introdb", "theintrodb", "chapters"
) {
    companion object {
        /** An interval that runs to the end of the file (the player clamps it to the duration). */
        const val OPEN_END = Double.MAX_VALUE
    }
}

/**
 * Spec 61 D2: [fallback] fills only the categories (intro, recap, outro) [primary] has nothing
 * for; a category [primary] covers is never replaced or added to. Order of sources: IntroDB,
 * then TheIntroDB, then the file's chapter names.
 */
internal fun fillMissingSkipCategories(
    primary: List<SkipInterval>,
    fallback: List<SkipInterval>
): List<SkipInterval> {
    if (fallback.isEmpty()) return primary
    val covered = primary.mapNotNullTo(HashSet()) { AutoSkipSegmentType.fromSkipIntervalType(it.type) }
    return primary + fallback.filter { interval ->
        val category = AutoSkipSegmentType.fromSkipIntervalType(interval.type)
        category != null && category !in covered
    }
}

/** An intro or recap longer than this is bad crowd data, not a segment to skip. */
private const val THEINTRODB_MAX_LEAD_SEGMENT_SEC = 600.0

/**
 * TheIntroDB's stamps as skip intervals; untrusted crowd data, so each kind is bounded. An intro
 * or recap may start at the file start (null) but needs a real end. Credits and the
 * next-episode preview are both the ending ("outro": the Skip button and auto-skip have no
 * preview type); they may run to the end of the file (null) but need a real start past 0, or
 * one empty stamp would be "the whole file is the ending" and start the next episode at once.
 */
internal fun TheIntroDbMediaResponse.toSkipIntervals(): List<SkipInterval> {
    fun TheIntroDbStamp.toInterval(type: String, tail: Boolean): SkipInterval? {
        val startMs = startMs ?: if (tail) return null else 0L
        if (startMs < 0L || (tail && startMs == 0L)) return null
        val start = startMs / 1000.0
        val end = endMs?.let { it / 1000.0 } ?: if (tail) SkipInterval.OPEN_END else return null
        if (end <= start) return null
        if (!tail && end - start > THEINTRODB_MAX_LEAD_SEGMENT_SEC) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "theintrodb")
    }
    return intro.orEmpty().mapNotNull { it.toInterval("intro", tail = false) } +
        recap.orEmpty().mapNotNull { it.toInterval("recap", tail = false) } +
        credits.orEmpty().mapNotNull { it.toInterval("outro", tail = true) } +
        preview.orEmpty().mapNotNull { it.toInterval("outro", tail = true) }
}

/**
 * What the player may act on, by where the interval sits in the file (crowd data is untrusted,
 * and a wrong or hostile stamp must not end the film or start the next episode):
 * - an ending (credits, preview, outro), open- or closed-ended, only when the duration is known
 *   and it starts in the second half ("the ending starts at 0:01" is dropped);
 * - an intro or recap, once the duration is known, only when it ends in the first half (one
 *   that runs to the end of a short file is dropped). While the duration is unknown it stays,
 *   as before Spec 61: it cannot reach the end flow by itself.
 */
internal fun List<SkipInterval>.withPlausiblePositions(durationMs: Long): List<SkipInterval> = filter {
    when (AutoSkipSegmentType.fromSkipIntervalType(it.type)) {
        AutoSkipSegmentType.OUTRO -> durationMs > 0L && it.startTime * 1000.0 >= durationMs / 2.0
        AutoSkipSegmentType.INTRO, AutoSkipSegmentType.RECAP ->
            durationMs <= 0L || it.endTime * 1000.0 <= durationMs / 2.0
        null -> true
    }
}

@Singleton
class SkipIntroRepository internal constructor(
    private val introDbApi: IntroDbApi,
    private val theIntroDbApi: TheIntroDbApi,
    private val introDbConfigured: Boolean,
    private val theIntroDbConfigured: Boolean
) {
    @Inject
    constructor(introDbApi: IntroDbApi, theIntroDbApi: TheIntroDbApi) : this(
        introDbApi = introDbApi,
        theIntroDbApi = theIntroDbApi,
        introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty(),
        theIntroDbConfigured = BuildConfig.THEINTRODB_API_URL.isNotEmpty()
    )

    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()

    /** [season] and [episode] null = a movie: only TheIntroDB knows movies. */
    suspend fun getSkipIntervals(imdbId: String?, season: Int?, episode: Int?): List<SkipInterval> {
        if (imdbId == null) return emptyList()
        if ((season == null) != (episode == null)) return emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return it }
        // 2026-08-26 review: only authoritative results are cached. A transient failure
        // (network down, 5xx) must stay uncached or skip-intro stays poisoned all session.
        val fromIntroDb = if (introDbConfigured && season != null && episode != null) {
            fetchFromIntroDb(imdbId, season, episode)
        } else {
            emptyList()
        }
        val primary = fromIntroDb ?: emptyList()
        // TheIntroDB is asked only when IntroDB left a category open (Spec 61 D2).
        val introDbComplete = SKIP_CATEGORIES.all { category ->
            primary.any { AutoSkipSegmentType.fromSkipIntervalType(it.type) == category }
        }
        val fromTheIntroDb = if (theIntroDbConfigured && !introDbComplete) {
            fetchFromTheIntroDb(imdbId, season, episode)
        } else {
            emptyList()
        }
        val result = fillMissingSkipCategories(primary, fromTheIntroDb ?: emptyList())
        if (fromIntroDb != null && fromTheIntroDb != null) {
            cache[cacheKey] = result
        }
        return result
    }

    /** Null = fetch failed this time; caller must not cache. Empty list = confirmed no data. */
    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval>? {
        return try {
            // Its own deadline, leaving TheIntroDB its 5 s inside the caller's 15 s.
            withTimeoutOrNull(INTRODB_TIMEOUT_MS) {
                val response = introDbApi.getSegments(imdbId, season, episode)
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    listOfNotNull(
                        data.intro.toSkipIntervalOrNull("intro"),
                        data.recap.toSkipIntervalOrNull("recap"),
                        data.outro.toSkipIntervalOrNull("outro")
                    )
                } else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: fetch failed for $imdbId S${season}E${episode}")
            null
        }
    }

    /**
     * Null = fetch failed this time; empty = TheIntroDB has no such title or segment (404 too).
     * Its own short timeout: it runs after IntroDB inside the caller's one deadline, and a hung
     * second source must not take IntroDB's answer down with it.
     */
    private suspend fun fetchFromTheIntroDb(imdbId: String, season: Int?, episode: Int?): List<SkipInterval>? {
        return try {
            withTimeoutOrNull(THEINTRODB_TIMEOUT_MS) {
                val response = theIntroDbApi.getMedia(imdbId, season, episode)
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> body.toSkipIntervals()
                    response.code() == 404 -> emptyList()
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "TheIntroDB: fetch failed for $imdbId S${season}E${episode}")
            null
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private companion object {
        const val INTRODB_TIMEOUT_MS = 8_000L
        const val THEINTRODB_TIMEOUT_MS = 5_000L
        val SKIP_CATEGORIES = AutoSkipSegmentType.values().toList()
    }
}
