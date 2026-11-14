package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.FailureFacts
import com.nuvio.tv.core.player.PlaybackFailure
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy
import com.nuvio.tv.core.player.ServerResponseFinding
import com.nuvio.tv.core.stream.FailureCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 75 S4: the approved copy picked per cause, and the Details lines (D4). */
class PlaybackFailureCopyTest {

    private val strings: FailureStrings = RealFailureStrings

    private fun s(id: Int, vararg args: Any) = strings.get(id, *args)

    private fun copy(cause: FailureCause, facts: FailureFacts = FailureFacts(), message: String = "raw") =
        PlaybackFailureCopy.of(PlaybackFailure(cause, message, facts = facts), strings)

    private fun text(line: List<DetailSpan>) = line.joinToString(" ") { it.text }

    @Test
    fun expiredNamesTheDebridServiceFromTheStreamName() {
        val c = copy(FailureCause.LINK_EXPIRED, FailureFacts(streamName = "[RD+] AIOStreams 2160p"))
        assertEquals(s(R.string.failure_title_link_expired), c.title)
        assertEquals(s(R.string.failure_body_link_expired, "Real-Debrid"), c.body)
    }

    @Test
    fun noKnownServiceSaysTheServer() {
        val c = copy(FailureCause.BLOCKED, FailureFacts(host = "cdn.example.com"))
        assertEquals(s(R.string.failure_body_blocked, s(R.string.failure_service_fallback)), c.body)
    }

    @Test
    fun webPageQuotesItsTitleOrFallsBackToPickAnother() {
        val page = ServerResponseFinding(ServerResponseFinding.Kind.WEB_PAGE, 200, "text/html", 14_000, title = "Fair use limit reached")
        assertEquals(
            s(R.string.failure_body_web_page, "Fair use limit reached"),
            copy(FailureCause.WEB_PAGE, FailureFacts(probe = page)).body
        )
        assertEquals(
            s(R.string.failure_body_pick_another),
            copy(FailureCause.WEB_PAGE, FailureFacts(probe = page.copy(title = null))).body
        )
    }

    @Test
    fun tinyFileComparesWithTheListedSizeWhenKnown() {
        val tiny = ServerResponseFinding(ServerResponseFinding.Kind.TINY_FILE, 200, "video/x-matroska", 2_000)
        val sixGb = 6_550_000_000L
        assertEquals(
            "The server sent a 2 KB file, not a 6.1 GB video",
            copy(FailureCause.TINY_FILE, FailureFacts(probe = tiny, fileSizeBytes = 2_000, listedSizeBytes = sixGb)).title
        )
        // What the player opened is the tiny reply itself: never "not a 2 KB video".
        assertEquals(
            s(R.string.failure_title_tiny_file_unsized, "2 KB"),
            copy(FailureCause.TINY_FILE, FailureFacts(probe = tiny, fileSizeBytes = 2_000)).title
        )
    }

    @Test
    fun textAndLoginNameWhatTheProbeSaw() {
        val text = ServerResponseFinding(ServerResponseFinding.Kind.TEXT, 200, "text/plain; charset=utf-8", 300)
        assertEquals(s(R.string.failure_title_text_typed, "text/plain"), copy(FailureCause.TEXT, FailureFacts(probe = text)).title)
        // Untrusted header text: bidi/format characters dropped, length capped.
        val hostile = text.copy(contentType = "text/\u202Epl\u00e9ain\u200B" + "x".repeat(200))
        val shown = copy(FailureCause.TEXT, FailureFacts(probe = hostile)).title
        assertEquals(s(R.string.failure_title_text_typed, "text/plain" + "x".repeat(50)), shown)
        assertEquals(s(R.string.failure_title_text), copy(FailureCause.TEXT).title)
        val login = ServerResponseFinding(ServerResponseFinding.Kind.LOGIN_PAGE, 200, "text/html", null, loginHostPath = "real-debrid.com/login")
        assertEquals(s(R.string.failure_title_login_at, "real-debrid.com/login"), copy(FailureCause.LOGIN, FailureFacts(probe = login)).title)
    }

    @Test
    fun tooSlowSaysBothSpeedsInWholeMbps() {
        val c = copy(FailureCause.TOO_SLOW, FailureFacts(measuredMbps = 18.4, neededMbps = 69.6))
        assertEquals("It arrived at 18 Mbit/s; this file needs about 70. A 1080p or web copy should play.", c.body)
        // Just under the need never reads equal; a trickle never reads 0.
        assertEquals(s(R.string.failure_body_too_slow, "69", "70"), copy(FailureCause.TOO_SLOW, FailureFacts(measuredMbps = 69.7, neededMbps = 69.8)).body)
        assertEquals(s(R.string.failure_body_too_slow, "0.4", "5.1"), copy(FailureCause.TOO_SLOW, FailureFacts(measuredMbps = 0.43, neededMbps = 5.01)).body)
    }

    @Test
    fun torrentBodyCarriesTheEngineMessageWithoutADoubleStop() {
        assertEquals(
            s(R.string.failure_body_torrent, "No peers"),
            copy(FailureCause.TORRENT, FailureFacts(engineMessage = "No peers.")).body
        )
        assertEquals(s(R.string.failure_body_torrent_plain), copy(FailureCause.TORRENT, FailureFacts(engineMessage = " ")).body)
    }

    @Test
    fun otherHttpNamesTheCode() {
        assertEquals(s(R.string.failure_body_other_http, "418"), copy(FailureCause.OTHER_HTTP, FailureFacts(httpCode = 418)).body)
    }

    @Test
    fun everyCauseHasATitleAndBody() {
        FailureCause.entries.forEach { cause ->
            val c = copy(cause)
            assertTrue("$cause", c.title.isNotBlank() && c.body.isNotBlank())
        }
    }

    @Test
    fun httpDetailsAreServerLineHostAndFile() {
        val c = copy(
            FailureCause.LINK_EXPIRED,
            FailureFacts(
                httpCode = 410, httpStatus = "Gone", playerError = "ERROR_CODE_IO_BAD_HTTP_STATUS (2004)",
                host = "x.download.real-debrid.com", addon = "AIOStreams",
                fileName = "Tears.of.Steel.mkv", fileSizeBytes = 6_550_000_000L
            )
        )
        assertEquals(
            listOf(
                "${s(R.string.failure_detail_server_answered)} HTTP 410 Gone \u00b7 ERROR_CODE_IO_BAD_HTTP_STATUS (2004)",
                "${s(R.string.failure_detail_host)} x.download.real-debrid.com ${s(R.string.failure_detail_via, "AIOStreams")}",
                "${s(R.string.failure_detail_file)} 6.1 GB \u00b7 Tears.of.Steel.mkv"
            ),
            c.details.map(::text)
        )
        assertEquals(DetailSpan.Tone.BOLD, c.details[0][1].tone)
        assertEquals(DetailSpan.Tone.MUTED, c.details[0][2].tone)
    }

    @Test
    fun probeLineComesAfterThePlayerLine() {
        val page = ServerResponseFinding(ServerResponseFinding.Kind.WEB_PAGE, 200, "text/html; charset=utf-8", 14_000, title = "Fair use")
        val c = copy(FailureCause.WEB_PAGE, FailureFacts(playerError = "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)", probe = page))
        assertEquals(
            listOf(
                "${s(R.string.failure_detail_player)} ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)",
                "${s(R.string.failure_detail_probe)} 200 text/html, 14 KB \u00b7 ${s(R.string.failure_detail_probe_title, "Fair use")}"
            ),
            c.details.map(::text)
        )
    }

    @Test
    fun watchdogDetailsSayTheWaitAndTheMeasuredSpeed() {
        val c = copy(
            FailureCause.TOO_SLOW,
            FailureFacts(
                watchdogFire = PlayerStartupWatchdogPolicy.FireReason.TracksNotRead, waitedMs = 20_000,
                measuredMbps = 18.4, measuredOverMs = 20_000, neededMbps = 69.6,
                fileSizeBytes = 6_765_000_000L, runtimeMinutes = 12
            )
        )
        assertEquals(
            "${s(R.string.failure_detail_player)} ${s(R.string.failure_detail_watchdog_no_data, "20.0 s")} " +
                s(R.string.failure_detail_watchdog_note),
            text(c.details[0])
        )
        assertEquals(
            "${s(R.string.failure_detail_measured)} ${s(R.string.failure_detail_mbps, "18.4")} " +
                "${s(R.string.failure_detail_over, "20.0 s")} ${s(R.string.failure_detail_needs)} " +
                "${s(R.string.failure_detail_about_mbps, "70")} ${s(R.string.failure_detail_size_over_runtime, "6.3 GB", 12)}",
            text(c.details[1])
        )
    }

    @Test
    fun watchdogDetailsSayStillLoadingWhenDataKeptArriving() {
        val c = copy(
            FailureCause.TOO_SLOW,
            FailureFacts(watchdogFire = PlayerStartupWatchdogPolicy.FireReason.DataStillArriving, waitedMs = 60_000)
        )
        assertEquals(
            "${s(R.string.failure_detail_player)} ${s(R.string.failure_detail_watchdog_still_loading, "1 min")} " +
                s(R.string.failure_detail_watchdog_note),
            text(c.details[0])
        )
    }

    @Test
    fun withNoFactsThePlayerLineIsTheMessage() {
        val c = copy(FailureCause.UNKNOWN, message = "Something odd [ERROR_CODE_UNSPECIFIED]")
        assertEquals(listOf("${s(R.string.failure_detail_player)} Something odd [ERROR_CODE_UNSPECIFIED]"), c.details.map(::text))
    }

    @Test
    fun fileLineWithANameButNoSize() {
        val c = copy(FailureCause.ENGINE, FailureFacts(engineMessage = "boom", fileName = "a.mkv"))
        assertEquals("${s(R.string.failure_detail_file)} a.mkv", text(c.details.last()))
        assertNull(copy(FailureCause.ENGINE).details.getOrNull(1))
    }

    @Test
    fun `the app lookup never formats a template it was given no arguments for`() {
        val context = io.mockk.mockk<android.content.Context>()
        io.mockk.every { context.getString(R.string.unit_size_gb) } returns "%1\$s GB"
        io.mockk.every { context.getString(R.string.unit_size_mb) } returns "%1\$s MB"
        io.mockk.every { context.getString(R.string.failure_time_minutes, 40) } returns "40 min"
        val s = contextFailureStrings(context)
        assertEquals("%1\$s GB", s.get(R.string.unit_size_gb))
        assertEquals("40 min", s.get(R.string.failure_time_minutes, 40))
        assertEquals("6.1 GB", PlaybackFailureCopy.size(6_550_000_000L, s))
    }

    @Test
    fun `a content type with nothing left after cleaning reads as plain text`() {
        val junk = ServerResponseFinding(ServerResponseFinding.Kind.TEXT, 200, "\u202E\u200B", 3)
        assertEquals(s(R.string.failure_title_text), copy(FailureCause.TEXT, FailureFacts(probe = junk)).title)
        val probeLine = copy(FailureCause.TEXT, FailureFacts(probe = junk)).details[1] // after the player line
        assertEquals("${s(R.string.failure_detail_probe)} 200, 1 KB", text(probeLine))
    }

    @Test
    fun `details speed rounds down like the body`() {
        val c = copy(FailureCause.TOO_SLOW, FailureFacts(measuredMbps = 17.96, neededMbps = 70.0))
        assertEquals(s(R.string.failure_body_too_slow, "17", "70"), c.body)
        assertEquals(s(R.string.failure_detail_mbps, "17.9"), c.details.first { it.size > 1 && it[0].text == s(R.string.failure_detail_measured) }[1].text)
    }

    @Test
    fun timesAndSizes() {
        assertEquals("1.4 s", PlaybackFailureCopy.duration(1_400, strings))
        assertEquals("40 min", PlaybackFailureCopy.duration(40 * 60_000L + 5_000, strings))
        assertEquals("1 KB", PlaybackFailureCopy.size(10, strings))
        assertEquals("512 MB", PlaybackFailureCopy.size(512L * 1024 * 1024, strings))
    }
}
