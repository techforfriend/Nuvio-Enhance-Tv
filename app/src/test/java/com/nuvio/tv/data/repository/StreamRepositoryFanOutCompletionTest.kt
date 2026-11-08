package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import com.nuvio.tv.core.di.aioStreamDataUserAgent
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamClientResolveDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The stream fan-out closes its result channel when the last addon job
 * finishes. These tests pin that the flow reaches its final state (and so
 * completes) for N addons and for zero addons, where no job would ever close
 * the channel. The search session runs on Dispatchers.IO, so a hang shows up
 * as a real-time withTimeout failure rather than a virtual-time stall.
 */
class StreamRepositoryFanOutCompletionTest {
    @Test
    fun `flow completes after every addon job finishes`() = runBlocking {
        val addons = listOf(
            streamAddon(id = "addon-a", name = "Addon A", baseUrl = "https://a.example"),
            streamAddon(id = "addon-b", name = "Addon B", baseUrl = "https://b.example")
        )
        val harness = newHarness(addons)

        val results = withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1341338"
            ).toList()
        }

        val final = results.last()
        assertTrue(final is NetworkResult.Success)
        val groups = (final as NetworkResult.Success).data
        assertEquals(setOf("Addon A", "Addon B"), groups.map { it.addonName }.toSet())
        assertTrue(groups.all { it.streams.size == 1 })
        coVerify(exactly = 2) { harness.api.getStreams(any(), any()) }
    }

    @Test
    fun `only an AIOStreams addon's stream request carries the AIO User-Agent`() = runBlocking {
        val addons = listOf(
            streamAddon(id = "com.aiostreams.viren070.ab12cd34ef56", name = "My Streams", baseUrl = "https://aio.example"),
            streamAddon(id = "addon-b", name = "Addon B", baseUrl = "https://b.example")
        )
        val harness = newHarness(addons)

        withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1341338").toList()
        }

        coVerify(exactly = 1) {
            harness.api.getStreams(match { it.startsWith("https://aio.example/") }, aioStreamDataUserAgent())
        }
        coVerify(exactly = 1) {
            harness.api.getStreams(match { it.startsWith("https://b.example/") }, isNull())
        }
    }

    @Test
    fun `flow completes with a final error when no addon is installed`() = runBlocking {
        val harness = newHarness(emptyList())

        val results = withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1341338"
            ).toList()
        }

        assertTrue(results.last() is NetworkResult.Error)
        coVerify(exactly = 0) { harness.api.getStreams(any(), any()) }
    }

    @Test
    fun `resolver-only streams are dropped at the addon boundary, info rows kept`() = runBlocking {
        val harness = newHarness(
            addons = emptyList(),
            streams = listOf(
                StreamDto(name = "Direct", url = "https://stream.example/video.mkv"),
                StreamDto(name = "Torrent", infoHash = "0123456789abcdef0123456789abcdef01234567"),
                StreamDto(name = "YouTube", ytId = "dQw4w9WgXcQ"),
                StreamDto(name = "External", externalUrl = "https://example.com/info"),
                StreamDto(
                    name = "Resolver only",
                    clientResolve = StreamClientResolveDto(
                        type = "debrid",
                        service = "torbox",
                        isCached = true,
                        infoHash = "0123456789abcdef0123456789abcdef01234567"
                    )
                ),
                StreamDto(name = "Info only", description = "No results")
            )
        )

        val result = harness.repository.getStreamsFromAddon(
            baseUrl = "https://a.example",
            type = "movie",
            videoId = "tt1341338",
            addonName = "Addon A",
            addonLogo = null
        )

        assertTrue(result is NetworkResult.Success)
        assertEquals(
            listOf("Direct", "Torrent", "YouTube", "External", "Info only"),
            (result as NetworkResult.Success).data.streams.map { it.name }
        )
    }

    // Spec 48 P1: the inner request shares the 25 s stream deadline; with the 20 s addon
    // default a gateway answering at 22 s came back as an error.
    @Test
    fun `an addon answering after 20 s but inside the stream deadline still counts`() = runTest {
        val harness = newHarness(addons = emptyList())
        coEvery { harness.api.getStreams(any(), any()) } coAnswers {
            delay(22_000)
            Response.success(StreamResponseDto(streams = listOf(StreamDto(name = "Late", url = "https://stream.example/late.mkv"))))
        }

        val result = harness.repository.getStreamsFromAddon(
            baseUrl = "https://aio.example",
            type = "movie",
            videoId = "tt1341338",
            addonName = "Nuvio AK Streams"
        )

        assertEquals(listOf("Late"), (result as NetworkResult.Success).data.streams.map { it.name })
    }

    // Spec 42 P1: AIOStreams appends `[❌] <upstream>` error rows and statistics lines; they
    // carry streamData.type and must never become sources.
    private fun statusRow(name: String, type: String) = StreamDto(
        name = name,
        externalUrl = "https://github.com/Viren070/AIOStreams",
        streamData = Json.parseToJsonElement("""{"type":"$type","error":{"title":"$name"}}""")
    )

    @Test
    fun `gateway error and statistic rows are dropped at the addon boundary`() = runBlocking {
        val harness = newHarness(
            addons = emptyList(),
            streams = listOf(
                StreamDto(
                    name = "4K",
                    url = "https://stream.example/video.mkv",
                    streamData = Json.parseToJsonElement("""{"type":"debrid"}""")
                ),
                StreamDto(name = "Plain", url = "https://stream.example/b.mkv"),
                statusRow("[❌] CNCVerse", "error"),
                statusRow("Statistics", "statistic")
            )
        )

        val result = harness.repository.getStreamsFromAddon(
            baseUrl = "https://aio.example",
            type = "movie",
            videoId = "tt1341338",
            addonName = "Nuvio AK Streams"
        )

        val answer = (result as NetworkResult.Success).data
        assertEquals(listOf("4K", "Plain"), answer.streams.map { it.name })
        // Spec 42 P6: the error row still names its upstream; the statistics line names none.
        assertEquals(listOf("CNCVerse"), answer.missingUpstreams)
    }

    @Test
    fun `error rows name their upstream from the title or the name, once each, prefix stripped`() = runBlocking {
        val harness = newHarness(
            addons = emptyList(),
            streams = listOf(
                StreamDto(name = "Plain", url = "https://stream.example/b.mkv"),
                statusRow("[❌] [❌] Knaben", "error"),
                // No streamData.error: the row's name carries the title.
                StreamDto(
                    name = "[❌]   ComeTorz\n",
                    externalUrl = "https://github.com/Viren070/AIOStreams",
                    streamData = Json.parseToJsonElement("""{"type":"error"}""")
                ),
                statusRow("[❌] Knaben", "error"),
                statusRow("[❌] ", "error")
            )
        )

        val result = harness.repository.getStreamsFromAddon(
            baseUrl = "https://aio.example",
            type = "movie",
            videoId = "tt1341338",
            addonName = "Nuvio AK Streams"
        )

        assertEquals(listOf("Knaben", "ComeTorz"), (result as NetworkResult.Success).data.missingUpstreams)
    }

    @Test
    fun `a gateway group carries the upstreams that failed`() = runBlocking {
        val harness = newHarness(
            addons = listOf(streamAddon(id = "com.aiostreams.viren070.ab12cd34ef56", name = "Streams", baseUrl = "https://aio.example")),
            streams = listOf(
                StreamDto(name = "4K", url = "https://stream.example/video.mkv"),
                statusRow("[❌] CNCVerse", "error")
            )
        )

        val final = withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1341338").toList()
        }.last()

        val group = (final as NetworkResult.Success).data.single()
        assertEquals(listOf("CNCVerse"), group.missingUpstreams)
    }

    @Test
    fun `another addon's streamData addon and error rows name no upstream`() = runBlocking {
        val harness = newHarness(
            addons = listOf(streamAddon(id = "addon-b", name = "Addon B", baseUrl = "https://b.example")),
            streams = listOf(
                StreamDto(
                    name = "4K",
                    url = "https://stream.example/video.mkv",
                    streamData = Json.parseToJsonElement("""{"type":"debrid","addon":"Spoofed"}""")
                ),
                statusRow("[❌] CNCVerse", "error")
            )
        )

        val final = withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1341338").toList()
        }.last()

        val group = (final as NetworkResult.Success).data.single()
        assertEquals(emptyList<String>(), group.missingUpstreams)
        assertEquals(null, group.streams.single().streamData?.upstream)
        assertEquals("debrid", group.streams.single().streamData?.type)
    }

    @Test
    fun `a gateway answering only error rows is not a source group`() = runBlocking {
        val harness = newHarness(
            addons = listOf(streamAddon(id = "com.aiostreams.viren070.ab12cd34ef56", name = "Streams", baseUrl = "https://aio.example")),
            streams = listOf(statusRow("[❌] CNCVerse", "error"), statusRow("[❌] PlavioHD", "error"))
        )

        val final = withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1341338").toList()
        }.last()

        val groups = (final as? NetworkResult.Success)?.data.orEmpty()
        assertTrue(groups.none { it.addonName == "Streams" })
    }

    @Test
    fun `when every upstream failed the fetch error names them`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.stream_upstreams_missing_one) } returns "%1${'$'}s didn't answer"
        every { context.getString(R.string.stream_upstreams_missing_list) } returns "%1${'$'}s and %2${'$'}s didn't answer"
        every { context.getString(R.string.stream_upstreams_missing_more) } returns "%1${'$'}s and %2${'$'}d more didn't answer"
        every { context.getString(R.string.stream_error_upstreams_failed, *anyVararg()) } answers {
            "${(args[1] as Array<*>)[0]}."
        }
        val harness = newHarness(
            addons = listOf(streamAddon(id = "com.aiostreams.viren070.ab12cd34ef56", name = "Streams", baseUrl = "https://aio.example")),
            streams = listOf("Torrentio", "MediaFusion", "Meteor", "Knaben", "ComeTorz").map { statusRow("[❌] $it", "error") },
            context = context
        )

        val final = withTimeout(5_000) {
            harness.repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1341338").toList()
        }.last()

        assertEquals(
            "Torrentio, MediaFusion, Meteor and 2 more didn't answer.",
            (final as NetworkResult.Error).message
        )
    }

    private fun newHarness(
        addons: List<Addon>,
        streams: List<StreamDto> = listOf(
            StreamDto(
                name = "Stream",
                url = "https://stream.example/video.m3u8"
            )
        ),
        context: Context = mockk(relaxed = true)
    ): Harness {
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any(), any()) } returns Response.success(
            StreamResponseDto(streams = streams)
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(addons)

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        return Harness(
            repository = StreamRepositoryImpl(
                context = context,
                api = api,
                addonRepository = addonRepository,
                profileManager = profileManager,
                healthStore = mockk(relaxed = true),
                cloudStreamStreamSource = CloudStreamStreamSource()
            ),
            api = api
        )
    }

    private fun streamAddon(id: String, name: String, baseUrl: String): Addon = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "stream",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    private data class Harness(
        val repository: StreamRepositoryImpl,
        val api: AddonApi
    )
}
