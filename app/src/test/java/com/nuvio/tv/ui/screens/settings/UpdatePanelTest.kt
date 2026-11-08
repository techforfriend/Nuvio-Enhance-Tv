package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.update.DownloadState
import com.nuvio.tv.core.update.KnownRelease
import com.nuvio.tv.core.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Spec 52 T2: what the open update panel shows as the controller's state moves, and its text. */
class UpdatePanelTest {

    private fun release(code: Int = 1392, publishedAt: String? = "2026-11-03T23:30:00Z") = KnownRelease(
        versionName = "0.9.0-ak51",
        versionCode = code,
        abi = "armeabi-v7a",
        downloadUrl = "https://github.com/Arshad-Kamal/Nuvio-AK/releases/download/v/a.apk",
        sizeBytes = 41_000_000L,
        sha256 = "ab".repeat(32),
        notes = "",
        publishedAt = publishedAt
    )

    private val shown = release()
    private val state = UpdateState(supported = true, available = shown)

    @Test
    fun `the panel follows the known release, a newer one included`() {
        assertEquals(shown, updatePanelRelease(state, shown))
        val newer = release(code = 1393)
        assertEquals(newer, updatePanelRelease(state.copy(available = newer), shown))
    }

    @Test
    fun `a dropped release stays on the panel while a download runs or after it failed`() {
        val dropped = state.copy(available = null)
        assertEquals(shown, updatePanelRelease(dropped.copy(download = DownloadState.Running()), shown))
        assertEquals(shown, updatePanelRelease(dropped.copy(download = DownloadState.Running(10L, 41L)), shown))
        assertEquals(shown, updatePanelRelease(dropped.copy(download = DownloadState.Failed), shown))
    }

    @Test
    fun `nothing newer and nothing running closes the panel`() {
        assertNull(updatePanelRelease(state.copy(available = null, download = DownloadState.Idle), shown))
    }

    @Test
    fun `the publish day is the device's, and a missing or bad date is none`() {
        assertEquals(LocalDate.of(2026, 11, 3), shown.publishedDate(ZoneId.of("America/Chicago")))
        assertEquals(LocalDate.of(2026, 11, 4), shown.publishedDate(ZoneId.of("Asia/Kolkata")))
        assertNull(release(publishedAt = null).publishedDate())
        assertNull(release(publishedAt = "3 Nov").publishedDate())
    }

    @Test
    fun `release notes read as plain text`() {
        val markdown = "## What's new\r\n\r\n- **Streams:** capped to [your network](https://example.com/a)\n" +
            "  * nested `item`\n\n\n\nPlain line ##\n### Fixes ###\n+ one"
        assertEquals(
            "What's new\n\n\u2022 Streams: capped to your network\n  \u2022 nested item\n\nPlain line ##\nFixes\n\u2022 one",
            releaseNotesText(markdown)
        )
        assertEquals("", releaseNotesText("  \n\n"))
        assertEquals("See shot and the docs.", releaseNotesText("See ![shot](a.png) and [the docs](https://x.org/a_(b))."))
        assertEquals("Fixed __init__ order", releaseNotesText("Fixed `__init__` order"))
    }

    @Test
    fun `progress counts the fresh check against the release size and stays within 0 to 1`() {
        assertEquals(41_000_000L, progressTotal(DownloadState.Running(), 41_000_000L))
        assertEquals(40_000_000L, progressTotal(DownloadState.Running(0L, 40_000_000L), 41_000_000L))
        assertEquals(0f, progressFraction(0L, 41_000_000L))
        assertEquals(0.5f, progressFraction(20_500_000L, 41_000_000L))
        assertEquals(1f, progressFraction(50L, 40L))
        assertEquals(0f, progressFraction(0L, 0L))
    }

    @Test
    fun `the status line's precedence`() {
        val idle = UpdateState(supported = true)
        assertEquals(UpdateStatus.NEVER_CHECKED, updateStatus(idle))
        assertEquals(UpdateStatus.UP_TO_DATE, updateStatus(idle.copy(lastOkCheckMs = 5L)))
        assertEquals(UpdateStatus.FAILED, updateStatus(idle.copy(lastCheckFailed = true)))
        assertEquals(UpdateStatus.FAILED_AFTER_OK, updateStatus(idle.copy(lastCheckFailed = true, lastOkCheckMs = 5L)))
        assertEquals(UpdateStatus.CHECKING, updateStatus(idle.copy(checking = true, lastCheckFailed = true)))
        assertEquals(UpdateStatus.RELEASE, updateStatus(state.copy(checking = true, lastCheckFailed = true)))
        assertEquals(
            listOf(UpdateStatus.FAILED_AFTER_OK, UpdateStatus.FAILED),
            UpdateStatus.entries.filter { it.isFailure }
        )
    }

    @Test
    fun `checked today, yesterday or another day in the device's zone`() {
        val zone = ZoneId.of("America/Chicago")
        val today = LocalDate.of(2026, 11, 2)
        // 2026-11-02 05:30 UTC = 23:30 on 1 Nov in Chicago.
        val lateYesterday = java.time.Instant.parse("2026-11-02T05:30:00Z").toEpochMilli()
        assertEquals(CheckedDay.YESTERDAY, checkedDay(lateYesterday, today, zone))
        assertEquals(CheckedDay.TODAY, checkedDay(lateYesterday, today, ZoneId.of("UTC")))
        val morning = java.time.Instant.parse("2026-11-02T15:00:00Z").toEpochMilli()
        assertEquals(CheckedDay.TODAY, checkedDay(morning, today, zone))
        assertEquals(CheckedDay.OTHER, checkedDay(morning, today.plusDays(2), zone))
        assertEquals(CheckedDay.OTHER, checkedDay(morning, today.minusDays(1), zone))
    }
}
