package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.update.ChangelogRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 80 S3: the list row's NEW / Installed rule and its What's new line. */
class ChangelogScreensTest {

    private fun release(code: Int?) = ChangelogRelease(
        title = "Nuvio KE", versionName = code?.let { "1.0.0" }, versionCode = code,
        publishedAt = null, whatsNew = "", notes = "", video = null
    )

    @Test
    fun `NEW is a higher versionCode, Installed the same one`() {
        assertTrue(release(1411).isNewer(installedCode = 1410))
        assertFalse(release(1410).isNewer(installedCode = 1410))
        assertTrue(release(1410).isInstalled(installedCode = 1410))
        assertFalse(release(null).isNewer(installedCode = 1410))
        assertFalse(release(null).isInstalled(installedCode = 1410))
    }

    @Test
    fun `the row line is What's new's first non-empty line, as plain text`() {
        assertEquals("Live TV: Xtream playlists", firstWhatsNewLine("\n- **Live TV**: Xtream playlists\n- Profiles"))
        assertEquals("Heading", firstWhatsNewLine("### Heading\n- A"))
        assertEquals("", firstWhatsNewLine(""))
    }
}
