package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.data.remote.dto.SubtitleResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.util.Collections

/**
 * P6 pins for anime type routing: items typed `anime.movie`/`anime.series`
 * (some anime metadata addons) must reach stream and subtitle addons that
 * declare `movie`/`series`/`anime`, requested with the matched type, while an
 * addon declaring the raw type keeps the exact match.
 */
class AnimeTypeRoutingTest {

    @Test
    fun `candidates map anime types to the base type then anime`() {
        assertEquals(listOf("anime.movie", "movie", "anime"), addonRequestTypeCandidates("anime.movie"))
        assertEquals(listOf("anime.series", "series", "anime"), addonRequestTypeCandidates("anime.series"))
        assertEquals(listOf("series"), addonRequestTypeCandidates("series"))
    }

    @Test
    fun `plain anime tries series for an episode id and movie otherwise`() {
        assertEquals(listOf("anime", "series"), addonRequestTypeCandidates("anime", "tt2560140:1:3"))
        assertEquals(listOf("anime", "series"), addonRequestTypeCandidates("anime", "kitsu:1376:5"))
        assertEquals(listOf("anime", "movie"), addonRequestTypeCandidates("anime", "kitsu:1376"))
        assertEquals(listOf("anime", "movie"), addonRequestTypeCandidates("anime", "tt0245429"))
        // No video id (the subtitle path): unchanged.
        assertEquals(listOf("anime"), addonRequestTypeCandidates("anime"))
    }

    @Test
    fun `meta id drops season and episode but never the id itself`() {
        assertEquals("tt1234567", addonMetaIdOf("tt1234567:1:5"))
        assertEquals("mal:63375", addonMetaIdOf("mal:63375:1:5"))
        assertEquals("kitsu:12345", addonMetaIdOf("kitsu:12345:2"))
        assertEquals("kitsu:12345", addonMetaIdOf("kitsu:12345"))
        assertEquals("tt1234567", addonMetaIdOf("tt1234567"))
        assertEquals("yt_id:UC123", addonMetaIdOf("yt_id:UC123"))
        assertEquals("12345", addonMetaIdOf("12345:1:2"))
        assertEquals("tmdb:1399", addonMetaIdOf("tmdb:1399:3:9"))
        // A Kitsu movie listing its one video plays as kitsu:ID:1: an episode id, so plain anime routes it as series.
        assertEquals("kitsu:1376", addonMetaIdOf("kitsu:1376:1"))
    }

    @Test
    fun `plain anime subtitles still reach only an anime addon`() = runBlocking {
        // The subtitle path passes no video id to the candidates (Spec 84 A3 left it unchanged).
        val urls = subtitleRequestUrls(
            type = "anime",
            addons = listOf(
                addon("https://series.example", "subtitles", listOf("series")),
                addon("https://anime.example", "subtitles", listOf("anime"))
            )
        )
        assertEquals(listOf("https://anime.example/subtitles/anime/kitsu%3A1376%3A5.json"), urls)
    }

    @Test
    fun `plain anime episode reaches an addon declaring movie and series as series`() = runBlocking {
        // NuvioMedia/NuvioTV#2983: AIOMetadata types anime `anime`; AIOStreams declares
        // ["movie","series","tv","events"] and answers /stream/series/ with the streams.
        val aio = addon("https://aio.example", "stream", listOf("movie", "series", "tv", "events"), idPrefixes = listOf("tt"))
        val anime = addon("https://anime.example", "stream", listOf("anime"), idPrefixes = listOf("tt"))
        assertEquals(
            listOf(
                "https://aio.example/stream/series/tt2560140%3A1%3A3.json",
                "https://anime.example/stream/anime/tt2560140%3A1%3A3.json"
            ),
            streamRequestUrls(type = "anime", addons = listOf(aio, anime), videoId = "tt2560140:1:3").sorted()
        )
        assertEquals(
            listOf("https://aio.example/stream/movie/tt0245429.json"),
            streamRequestUrls(type = "anime", addons = listOf(aio), videoId = "tt0245429")
        )
    }

    @Test
    fun `anime series stream request reaches series and anime addons with the matched type`() = runBlocking {
        val urls = streamRequestUrls(
            type = "anime.series",
            addons = listOf(
                addon("https://exact.example", "stream", listOf("anime.series", "series")),
                addon("https://series.example", "stream", listOf("series")),
                addon("https://anime.example", "stream", listOf("anime")),
                addon("https://movie.example", "stream", listOf("movie"))
            )
        )

        // Sorted lists, not sets, so a duplicate request fails the test.
        assertEquals(
            listOf(
                "https://anime.example/stream/anime/kitsu%3A1376%3A5.json",
                "https://exact.example/stream/anime.series/kitsu%3A1376%3A5.json",
                "https://series.example/stream/series/kitsu%3A1376%3A5.json"
            ),
            urls.sorted()
        )
    }

    @Test
    fun `plain movie stream request does not widen to anime addons`() = runBlocking {
        val urls = streamRequestUrls(
            type = "movie",
            addons = listOf(addon("https://anime.example", "stream", listOf("anime")))
        )

        assertEquals(emptyList<String>(), urls)
    }

    @Test
    fun `anime movie subtitle request reaches a movie addon with type movie`() = runBlocking {
        val urls = subtitleRequestUrls(
            type = "anime.movie",
            addons = listOf(
                addon("https://exact.example", "subtitles", listOf("anime.movie", "movie")),
                addon("https://movie.example", "subtitles", listOf("movie")),
                addon("https://series.example", "subtitles", listOf("series"))
            )
        )

        assertEquals(
            listOf(
                "https://exact.example/subtitles/anime.movie/kitsu%3A1376%3A5.json",
                "https://movie.example/subtitles/movie/kitsu%3A1376%3A5.json"
            ),
            urls.sorted()
        )
    }

    @Test
    fun `tmdb episode reaches a tt-only subtitle addon by imdb video id`() = runBlocking {
        val urls = subtitleRequestUrls(
            type = "series",
            addons = listOf(
                addon("https://tt.example", "subtitles", listOf("series"), idPrefixes = listOf("tt"))
            ),
            id = "tmdb:1399",
            videoId = "tmdb:1399:3:9",
            imdbId = "tt0944947",
            season = 3,
            episode = 9
        )

        assertEquals(listOf("https://tt.example/subtitles/series/tt0944947%3A3%3A9.json"), urls)
    }

    @Test
    fun `app episodic type tv reaches a series subtitle addon as series`() = runBlocking {
        val urls = subtitleRequestUrls(
            type = "tv",
            addons = listOf(addon("https://series.example", "subtitles", listOf("series")))
        )

        assertEquals(listOf("https://series.example/subtitles/series/kitsu%3A1376%3A5.json"), urls)
    }

    @Test
    fun `streamRequestType matches declared types exactly and honours prefix precedence`() {
        // Exact match, same as the request fan-out (source chips share this predicate).
        assertNull(addon("https://a.example", "stream", listOf("Movie")).streamRequestType("movie", "kitsu:1"))
        // No declared types: any type.
        assertEquals("movie", addon("https://a.example", "stream", emptyList()).streamRequestType("movie", "kitsu:1"))
        // Resource idPrefixes override the addon's top-level ones.
        val resourceWins = addon("https://a.example", "stream", listOf("movie"), idPrefixes = listOf("tt"))
            .copy(idPrefixes = listOf("kitsu:"))
        assertNull(resourceWins.streamRequestType("movie", "kitsu:1"))
        assertEquals("movie", resourceWins.streamRequestType("movie", "tt1"))
        // No resource prefixes: the top-level ones apply.
        val topLevel = addon("https://a.example", "stream", listOf("movie"), idPrefixes = emptyList())
            .copy(idPrefixes = listOf("tt"))
        assertNull(topLevel.streamRequestType("movie", "kitsu:1"))
        assertEquals("movie", topLevel.streamRequestType("movie", "tt1"))
    }

    private suspend fun streamRequestUrls(type: String, addons: List<Addon>, videoId: String = VIDEO_ID): List<String> {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(capture(urls), any()) } returns Response.success(
            StreamResponseDto(streams = listOf(StreamDto(name = "Stream", url = "https://stream.example/v.mkv")))
        )
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(addons)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val repository = StreamRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            addonRepository = addonRepository,
            profileManager = profileManager,
            healthStore = mockk(relaxed = true),
            cloudStreamStreamSource = CloudStreamStreamSource()
        )

        withTimeout(5_000) {
            repository.getStreamsFromAllAddons(type = type, videoId = videoId).toList()
        }
        return urls.toList()
    }

    private suspend fun subtitleRequestUrls(
        type: String,
        addons: List<Addon>,
        id: String = "kitsu:1376",
        videoId: String = VIDEO_ID,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val api = mockk<AddonApi>()
        coEvery { api.getSubtitles(capture(urls)) } returns Response.success(
            SubtitleResponseDto(subtitles = emptyList())
        )
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(addons)
        val repository = SubtitleRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )

        withTimeout(5_000) {
            repository.getSubtitles(
                type = type,
                id = id,
                videoId = videoId,
                imdbId = imdbId,
                season = season,
                episode = episode
            )
        }
        return urls.toList()
    }

    private fun addon(
        baseUrl: String,
        resource: String,
        types: List<String>,
        idPrefixes: List<String> = listOf("kitsu:")
    ): Addon = Addon(
        id = baseUrl,
        name = baseUrl,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(AddonResource(name = resource, types = types, idPrefixes = idPrefixes))
    )

    private companion object {
        const val VIDEO_ID = "kitsu:1376:5"
    }
}
