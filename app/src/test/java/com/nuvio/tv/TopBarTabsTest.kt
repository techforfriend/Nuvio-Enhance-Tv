package com.nuvio.tv

import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TopBarTabsTest {

    @Test
    fun `live tv follows home, show in top bar adds discover right after search`() {
        assertEquals(
            listOf("home", "live_tv", "search", "discover", "library", "settings"),
            topBarTabRoutes(DiscoverLocation.IN_SIDEBAR, liveTvEnabled = true)
        )
    }

    @Test
    fun `show in search keeps discover out of the bar`() {
        assertEquals(
            listOf("home", "live_tv", "search", "library", "settings"),
            topBarTabRoutes(DiscoverLocation.IN_SEARCH, liveTvEnabled = true)
        )
    }

    @Test
    fun `discover off and not-yet-loaded location keep discover out of the bar`() {
        val withoutDiscover = listOf("home", "live_tv", "search", "library", "settings")
        assertEquals(withoutDiscover, topBarTabRoutes(DiscoverLocation.OFF, liveTvEnabled = true))
        assertEquals(withoutDiscover, topBarTabRoutes(null, liveTvEnabled = true))
    }

    @Test
    fun `live tv off or not yet loaded keeps the live tv tab out of the bar (Spec 34 D17)`() {
        val withoutLiveTv = listOf("home", "search", "library", "settings")
        assertEquals(withoutLiveTv, topBarTabRoutes(DiscoverLocation.OFF, liveTvEnabled = false))
        assertEquals(withoutLiveTv, topBarTabRoutes(DiscoverLocation.OFF, liveTvEnabled = null))
    }

    @Test
    fun `live tv routes are the tab and its sources route only`() {
        assertTrue(isLiveTvRoute(Screen.LiveTv.route))
        assertTrue(isLiveTvRoute(Screen.LiveTvSources.route))
        assertFalse(isLiveTvRoute("live_tv_other"))
        assertFalse(isLiveTvRoute(Screen.Home.route))
        assertFalse(isLiveTvRoute(null))
    }

    @Test
    fun `selected tab matches its own route and its sub-routes only`() {
        val tabs = listOf("home", "search", "library", "settings")
        assertEquals("search", selectedTopBarRoute(tabs, "search"))
        assertEquals("search", selectedTopBarRoute(tabs, "search/{query}"))
        assertNull(selectedTopBarRoute(tabs, "searchable"))
        assertNull(selectedTopBarRoute(tabs, "detail/{itemId}/{itemType}"))
        assertNull(selectedTopBarRoute(tabs, null))
    }

    @Test
    fun `bar shows over a tab root except Settings pushed over a page`() {
        val tabs = listOf("home", "search", "library", "settings")
        assertTrue(topBarShown(tabs, "settings", "home"))
        assertTrue(topBarShown(tabs, "settings", null))
        assertFalse(topBarShown(tabs, "settings", Screen.Player.route))
        // Spec 52 S4: Add a TMDB key pushes Settings over the person, studio or folder page.
        assertFalse(topBarShown(tabs, "settings", Screen.CastDetail.route))
        assertFalse(topBarShown(tabs, "settings", Screen.FolderDetail.route))
        assertTrue(topBarShown(tabs, "search", "home"))
        assertFalse(topBarShown(tabs, "detail/{itemId}/{itemType}", "home"))
        assertFalse(topBarShown(tabs, null, null))
    }

    @Test
    fun `a fallback sends the main UI back to the picker each time it is set (Spec 64 D14)`() = runTest {
        val fellBack = MutableStateFlow(false)
        var switches = 0
        val watcher = launch { returnToPickerOnFallback(fellBack) { switches++ } }
        runCurrent()
        assertEquals(0, switches)
        fellBack.value = true
        runCurrent()
        assertEquals(1, switches)
        // Cleared by a pick, set again by a later fallback.
        fellBack.value = false
        runCurrent()
        fellBack.value = true
        runCurrent()
        assertEquals(2, switches)
        watcher.cancel()
    }
}
