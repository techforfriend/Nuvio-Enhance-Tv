package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import com.nuvio.tv.data.remote.api.TheIntroDbApi
import com.nuvio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nuvio.tv.data.remote.api.TheIntroDbStamp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** Spec 61 Q7 / D2: TheIntroDB behind IntroDB, missing categories only, failures never cached. */
class SkipIntroRepositoryTest {

    private val introDb = mockk<IntroDbApi>()
    private val theIntroDb = mockk<TheIntroDbApi>()

    private fun repository(introDbConfigured: Boolean = true, theIntroDbConfigured: Boolean = true) =
        SkipIntroRepository(introDb, theIntroDb, introDbConfigured, theIntroDbConfigured)

    private fun introDbReturns(response: IntroDbSegmentsResponse) {
        coEvery { introDb.getSegments(any(), any(), any()) } returns Response.success(response)
    }

    private fun theIntroDbReturns(response: TheIntroDbMediaResponse) {
        coEvery { theIntroDb.getMedia(any(), any(), any()) } returns Response.success(response)
    }

    private fun segment(startSec: Double, endSec: Double) = IntroDbSegment(startSec = startSec, endSec = endSec)

    @Test
    fun `TheIntroDB fills only the categories IntroDB lacks`() = runTest {
        introDbReturns(IntroDbSegmentsResponse(intro = segment(60.0, 150.0)))
        theIntroDbReturns(
            TheIntroDbMediaResponse(
                intro = listOf(TheIntroDbStamp(1_000, 90_000)),
                recap = listOf(TheIntroDbStamp(null, 55_000)),
                credits = listOf(TheIntroDbStamp(2_500_000, null))
            )
        )

        val intervals = repository().getSkipIntervals("tt1", 1, 2)

        assertEquals(listOf("introdb", "theintrodb", "theintrodb"), intervals.map { it.provider })
        assertEquals(listOf("intro", "recap", "outro"), intervals.map { it.type })
        assertEquals(60.0, intervals[0].startTime, 0.0)
        assertEquals(0.0, intervals[1].startTime, 0.0)
        assertEquals(SkipInterval.OPEN_END, intervals[2].endTime, 0.0)
    }

    @Test
    fun `a complete IntroDB answer never asks TheIntroDB`() = runTest {
        introDbReturns(
            IntroDbSegmentsResponse(
                intro = segment(60.0, 150.0),
                recap = segment(0.0, 50.0),
                outro = segment(1_300.0, 1_400.0)
            )
        )

        assertEquals(3, repository().getSkipIntervals("tt1", 1, 2).size)
        coVerify(exactly = 0) { theIntroDb.getMedia(any(), any(), any()) }
    }

    @Test
    fun `a movie asks TheIntroDB alone, without season or episode`() = runTest {
        theIntroDbReturns(
            TheIntroDbMediaResponse(
                credits = listOf(TheIntroDbStamp(7_726_802, null)),
                preview = listOf(TheIntroDbStamp(8_000_000, 8_100_000))
            )
        )

        val intervals = repository().getSkipIntervals("tt0133093", null, null)

        assertEquals(listOf("outro", "outro"), intervals.map { it.type })
        coVerify(exactly = 0) { introDb.getSegments(any(), any(), any()) }
        coVerify(exactly = 1) { theIntroDb.getMedia("tt0133093", null, null) }
    }

    @Test
    fun `a TheIntroDB failure keeps IntroDB's answer and is asked again next time`() = runTest {
        introDbReturns(IntroDbSegmentsResponse(intro = segment(60.0, 150.0)))
        coEvery { theIntroDb.getMedia(any(), any(), any()) } throws IOException("down")
        val repository = repository()

        assertEquals(listOf("intro"), repository.getSkipIntervals("tt1", 1, 2).map { it.type })
        theIntroDbReturns(TheIntroDbMediaResponse(credits = listOf(TheIntroDbStamp(2_500_000, null))))
        assertEquals(listOf("intro", "outro"), repository.getSkipIntervals("tt1", 1, 2).map { it.type })
        // Both answered: cached from here on.
        repository.getSkipIntervals("tt1", 1, 2)
        coVerify(exactly = 2) { theIntroDb.getMedia(any(), any(), any()) }
        coVerify(exactly = 2) { introDb.getSegments(any(), any(), any()) }
    }

    @Test
    fun `an IntroDB failure still takes TheIntroDB's answer, uncached`() = runTest {
        coEvery { introDb.getSegments(any(), any(), any()) } throws IOException("down")
        theIntroDbReturns(TheIntroDbMediaResponse(intro = listOf(TheIntroDbStamp(1_000, 90_000))))
        val repository = repository()

        assertEquals(listOf("theintrodb"), repository.getSkipIntervals("tt1", 1, 2).map { it.provider })
        repository.getSkipIntervals("tt1", 1, 2)
        coVerify(exactly = 2) { introDb.getSegments(any(), any(), any()) }
    }

    @Test
    fun `an unknown title on TheIntroDB is a confirmed empty answer`() = runTest {
        introDbReturns(IntroDbSegmentsResponse())
        coEvery { theIntroDb.getMedia(any(), any(), any()) } returns
            Response.error(404, """{"code":"media_not_found"}""".toResponseBody())
        val repository = repository()

        assertTrue(repository.getSkipIntervals("tt1", 1, 2).isEmpty())
        repository.getSkipIntervals("tt1", 1, 2)
        coVerify(exactly = 1) { theIntroDb.getMedia(any(), any(), any()) }
    }

    @Test
    fun `an unconfigured TheIntroDB is never asked`() = runTest {
        introDbReturns(IntroDbSegmentsResponse())

        assertTrue(repository(theIntroDbConfigured = false).getSkipIntervals("tt1", 1, 2).isEmpty())
        assertTrue(repository(theIntroDbConfigured = false).getSkipIntervals("tt1", null, null).isEmpty())
        coVerify(exactly = 0) { theIntroDb.getMedia(any(), any(), any()) }
    }

    @Test
    fun `bad crowd stamps are dropped`() {
        val intervals = TheIntroDbMediaResponse(
            intro = listOf(
                TheIntroDbStamp(0, null),          // an intro to the end of the file
                TheIntroDbStamp(0, 7_000_000),     // a two-hour intro
                TheIntroDbStamp(90_000, 60_000),   // ends before it starts
                TheIntroDbStamp(-5_000, 30_000)    // starts before the file
            ),
            recap = listOf(TheIntroDbStamp(null, null)),
            credits = listOf(
                TheIntroDbStamp(2_000_000, 1_000_000),
                TheIntroDbStamp(null, null),       // "the whole file is the ending"
                TheIntroDbStamp(0, null)
            ),
            preview = listOf(TheIntroDbStamp(null, 90_000))
        ).toSkipIntervals()

        assertTrue(intervals.isEmpty())
        assertTrue(TheIntroDbMediaResponse().toSkipIntervals().isEmpty())
    }

    @Test
    fun `online intervals are kept only where their kind belongs in the file`() {
        val late = SkipInterval(2_500.0, SkipInterval.OPEN_END, "outro", "theintrodb")
        val earlyOpen = SkipInterval(60.0, SkipInterval.OPEN_END, "outro", "theintrodb")
        val earlyClosed = SkipInterval(0.001, 99_999.0, "outro", "theintrodb")
        val intro = SkipInterval(60.0, 150.0, "intro", "introdb")
        val introToTheEnd = SkipInterval(0.0, 600.0, "intro", "theintrodb")
        val all = listOf(intro, late, earlyOpen, earlyClosed, introToTheEnd)

        assertEquals(listOf(intro, late, introToTheEnd), all.withPlausiblePositions(2_700_000L))
        // A nine-minute file: the "intro" would skip all of it.
        assertEquals(listOf(intro), all.withPlausiblePositions(540_000L).filter { it.type == "intro" })
        // Unknown duration: no ending at all, intros as before.
        assertEquals(listOf(intro, introToTheEnd), all.withPlausiblePositions(0L))
    }

    @Test
    fun `explicit JSON nulls for the arrays parse as no stamps`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val response = json.decodeFromString<TheIntroDbMediaResponse>(
            """{"type":"tv","intro":[{"start_ms":null,"end_ms":40000}],"recap":null,"credits":null}"""
        )
        assertEquals(listOf("intro"), response.toSkipIntervals().map { it.type })
    }

    @Test
    fun `a slow IntroDB leaves TheIntroDB its turn inside the caller's deadline`() = runTest {
        coEvery { introDb.getSegments(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000L)
            Response.success(IntroDbSegmentsResponse())
        }
        theIntroDbReturns(TheIntroDbMediaResponse(intro = listOf(TheIntroDbStamp(1_000, 90_000))))

        val intervals = kotlinx.coroutines.withTimeoutOrNull(15_000L) { repository().getSkipIntervals("tt1", 1, 2) }

        assertEquals(listOf("theintrodb"), intervals?.map { it.provider })
    }

    @Test
    fun `a hung TheIntroDB does not take IntroDB's answer with it`() = runTest {
        introDbReturns(IntroDbSegmentsResponse(intro = segment(60.0, 150.0)))
        coEvery { theIntroDb.getMedia(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000L)
            Response.success(TheIntroDbMediaResponse())
        }
        val repository = repository()

        val intervals = kotlinx.coroutines.withTimeoutOrNull(15_000L) { repository.getSkipIntervals("tt1", 1, 2) }

        assertEquals(listOf("intro"), intervals?.map { it.type })
        // A timeout is a failure: asked again next time.
        repository.getSkipIntervals("tt1", 1, 2)
        coVerify(exactly = 2) { theIntroDb.getMedia(any(), any(), any()) }
    }
}
