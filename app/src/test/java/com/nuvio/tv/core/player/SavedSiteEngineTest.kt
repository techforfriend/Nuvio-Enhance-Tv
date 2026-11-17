package com.nuvio.tv.core.player

import com.nuvio.tv.core.network.access.AccessHeader
import com.nuvio.tv.core.network.access.AccessSite
import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 97 D30: a stream on a saved access site plays on ExoPlayer, never on mpv. */
class SavedSiteEngineTest {

    private val sites = listOf(AccessSite("watch.kamal.ca", 443, listOf(AccessHeader("CF-Access-Client-Id", "id"))))

    @Test
    fun `only an https url on a saved host and port is on a saved site`() {
        assertTrue(isOnSavedAccessSite("https://watch.kamal.ca/movie.mkv") { sites })
        assertTrue("default port", isOnSavedAccessSite("https://WATCH.kamal.ca:443/x") { sites })
        assertFalse("http", isOnSavedAccessSite("http://watch.kamal.ca/x") { sites })
        assertFalse("another port", isOnSavedAccessSite("https://watch.kamal.ca:8443/x") { sites })
        assertFalse("a sibling host", isOnSavedAccessSite("https://cdn.kamal.ca/x") { sites })
        // OkHttp ends the authority at '\'; the host judged is watch.kamal.ca, as A3 sees it.
        assertTrue(isOnSavedAccessSite("https://watch.kamal.ca\\@evil.test/x") { sites })
        assertFalse("not a url", isOnSavedAccessSite("magnet:?xt=urn:btih:abc") { sites })
    }

    @Test
    fun `a store that can't be read counts as no site`() {
        assertFalse(isOnSavedAccessSite("https://watch.kamal.ca/x") { error("keystore") })
    }

    @Test
    fun `mpv becomes ExoPlayer on a saved site, every other engine stays`() {
        assertEquals(InternalPlayerEngine.EXOPLAYER, engineForSavedSite(InternalPlayerEngine.MVP_PLAYER, onSavedSite = true))
        assertEquals(InternalPlayerEngine.MVP_PLAYER, engineForSavedSite(InternalPlayerEngine.MVP_PLAYER, onSavedSite = false))
        assertEquals(InternalPlayerEngine.EXOPLAYER, engineForSavedSite(InternalPlayerEngine.EXOPLAYER, onSavedSite = true))
        assertEquals(InternalPlayerEngine.AUTO, engineForSavedSite(InternalPlayerEngine.AUTO, onSavedSite = true))
    }

    private val exo = InternalPlayerEngine.EXOPLAYER
    private val mpv = InternalPlayerEngine.MVP_PLAYER

    @Test
    fun `an mpv override on a saved site leaves the observer and restarts on ExoPlayer`() {
        // A manual switch or format hop to mpv, then a start on a saved site, with the setting on mpv.
        val start = savedSiteStartEngines(effective = mpv, override = mpv, onSavedSite = true)
        assertEquals(SavedSiteStartEngines(effective = exo, override = exo), start)
        // initializePlayer passes its override parameter (mpv, non-null) to sessionEngineToKeep.
        val session = sessionEngineToKeep(override = mpv, effective = start.effective)
        assertNull(session)
        // The settings observer derives override ?: session ?: setting.
        assertEquals(exo, start.override ?: session ?: mpv)
        assertEquals(exo, sameStreamRestartEngine(override = start.override, session = session))
    }

    @Test
    fun `an mpv setting on a saved site keeps ExoPlayer as the session engine`() {
        val start = savedSiteStartEngines(effective = mpv, override = null, onSavedSite = true)
        assertEquals(SavedSiteStartEngines(effective = exo, override = null), start)
        val session = sessionEngineToKeep(override = null, effective = start.effective)
        assertEquals(exo, session)
        assertEquals(exo, start.override ?: session ?: mpv)
    }

    @Test
    fun `off a saved site the start engines are unchanged`() {
        assertEquals(SavedSiteStartEngines(mpv, mpv), savedSiteStartEngines(mpv, mpv, onSavedSite = false))
        assertEquals(SavedSiteStartEngines(exo, null), savedSiteStartEngines(exo, null, onSavedSite = false))
    }

    @Test
    fun `a manual or failure-box switch never targets mpv on a saved site`() {
        assertNull(allowedSwitchTarget(manualEngineSwitchTarget(exo, mpvViewAttached = false), onSavedSite = true))
        assertNull(
            allowedSwitchTarget(manualEngineSwitchTarget(InternalPlayerEngine.AUTO, mpvViewAttached = false), onSavedSite = true)
        )
        assertEquals(exo, allowedSwitchTarget(manualEngineSwitchTarget(mpv, mpvViewAttached = true), onSavedSite = true))
        assertEquals(mpv, allowedSwitchTarget(manualEngineSwitchTarget(exo, mpvViewAttached = false), onSavedSite = false))
    }

    @Test
    fun `mpv is barred on a saved site and while the stream is not looked up yet`() {
        assertTrue("saved site", mpvBarred(onSavedSite = true))
        assertTrue("not looked up (fails closed)", mpvBarred(onSavedSite = null))
        assertFalse(mpvBarred(onSavedSite = false))
        // The UI state's default mirrors the controller field's initial null (initializers skip its setter).
        assertEquals(mpvBarred(null), PlayerUiState().mpvBarredForStream)
    }

    @Test
    fun `an attached mpv view starts the stream only after a lookup said no site and the start chose mpv`() {
        assertFalse("prelude, not looked up", mpvAttachMayStart(onSavedSite = null, streamEngine = mpv))
        assertFalse("prelude, nothing resolved", mpvAttachMayStart(onSavedSite = null, streamEngine = null))
        assertFalse("saved site", mpvAttachMayStart(onSavedSite = true, streamEngine = mpv))
        assertFalse("resolved to ExoPlayer", mpvAttachMayStart(onSavedSite = false, streamEngine = exo))
        assertTrue(mpvAttachMayStart(onSavedSite = false, streamEngine = mpv))
    }
}
