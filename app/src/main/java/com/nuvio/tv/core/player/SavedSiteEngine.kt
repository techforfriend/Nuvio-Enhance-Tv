package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.core.network.access.AccessHeaders
import com.nuvio.tv.core.network.access.AccessSite
import com.nuvio.tv.core.network.access.accessSiteFor
import com.nuvio.tv.data.local.InternalPlayerEngine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val TAG = "SavedSiteEngine"

/**
 * Spec 97 D30: whether the stream [url] is on a saved access site (A3's match: https, exact host and
 * port). Such a stream plays on ExoPlayer, whose OkHttp stack adds the site's headers per hop; mpv
 * sends one global header list everywhere it goes (redirects, playlist segments, subtitles), so it
 * never gets a site's headers and never plays such a stream. Blocking on the store's first load:
 * call off the main thread. A store that can't be read counts as no site: mpv then gets the stream
 * without the site's headers (as every mpv start does), fails on a site that needs them, and the
 * engine failover hands it to ExoPlayer where allowed.
 */
internal fun isOnSavedAccessSite(url: String, sites: () -> List<AccessSite> = AccessHeaders::sites): Boolean =
    try {
        url.toHttpUrlOrNull()?.let { accessSiteFor(it, sites) } != null
    } catch (e: Exception) {
        Log.w(TAG, "Saved-site lookup failed, counted as no site: ${e.javaClass.simpleName}")
        false
    }

/** D30: the engine a start runs: ExoPlayer instead of mpv for a stream on a saved site. */
internal fun engineForSavedSite(engine: InternalPlayerEngine, onSavedSite: Boolean): InternalPlayerEngine =
    if (onSavedSite && engine == InternalPlayerEngine.MVP_PLAYER) InternalPlayerEngine.EXOPLAYER else engine

/** D30: the engines a start keeps once its site lookup is in: the effective engine and the runtime override. */
internal data class SavedSiteStartEngines(val effective: InternalPlayerEngine, val override: InternalPlayerEngine?)

/**
 * D30: an mpv choice (setting, AUTO, start-on-mpv name) and an mpv runtime override (manual switch,
 * format hop) both become ExoPlayer on a saved site, so the settings observer
 * (override ?: session ?: setting) and same-stream restarts agree with the engine that runs.
 */
internal fun savedSiteStartEngines(
    effective: InternalPlayerEngine,
    override: InternalPlayerEngine?,
    onSavedSite: Boolean
): SavedSiteStartEngines = SavedSiteStartEngines(
    effective = engineForSavedSite(effective, onSavedSite),
    override = override?.let { engineForSavedSite(it, onSavedSite) }
)

/**
 * D30: the engine a manual switch (More tray, error box's Play with) may go to: none when the
 * [target] is mpv and the stream is on a saved site.
 */
internal fun allowedSwitchTarget(target: InternalPlayerEngine, onSavedSite: Boolean): InternalPlayerEngine? =
    target.takeUnless { onSavedSite && it == InternalPlayerEngine.MVP_PLAYER }

/**
 * D30: mpv is barred for the current stream (format hop, manual switch, error box): on a saved site,
 * or not looked up yet (a start's prelude, a new stream before its start), failing closed.
 */
internal val PlayerRuntimeController.mpvBarredForStream: Boolean
    get() = mpvBarred(streamOnSavedAccessSite)

/** D30: [onSavedSite] true, or null (not looked up yet), bars mpv. */
internal fun mpvBarred(onSavedSite: Boolean?): Boolean = onSavedSite != false

/**
 * D30: whether an attached mpv view may start the current stream itself: only once this stream's site
 * lookup said "not a saved site" ([onSavedSite] false; null = not looked up yet, e.g. a start's
 * prelude) and the engine the start resolved is mpv. Otherwise initializePlayer starts mpv (or not).
 */
internal fun mpvAttachMayStart(onSavedSite: Boolean?, streamEngine: InternalPlayerEngine?): Boolean =
    onSavedSite == false && streamEngine == InternalPlayerEngine.MVP_PLAYER
