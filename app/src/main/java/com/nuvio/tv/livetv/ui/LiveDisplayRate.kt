package com.nuvio.tv.livetv.ui

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.tv.livetv.player.LiveFrameRate
import com.nuvio.tv.livetv.player.LivePlayer
import kotlin.math.abs

/**
 * Live TV's display switch (Spec 94 T4, D1-D3): with Frame rate matching on, a 25/50 fps channel in
 * full screen puts the display at 50 Hz; another rate puts back what the window asked for before
 * Live TV changed it. A channel whose rate is not known yet leaves the display as it is, so zaps
 * between 50 Hz channels do not blink. Leaving Live TV or turning the setting Off restores it.
 *
 * It writes the window's `preferredDisplayModeId` itself rather than through `FrameRateUtils`: that
 * reads the active `Display.mode`, which lags a just-written request (a fast PAL -> other -> PAL zap
 * would record 50 Hz as the original and skip the write), and its saved mode is shared with the VOD
 * player. The window attribute is synchronous and Live TV's own. Main thread only.
 */
object LiveDisplayRate {

    enum class Action { NONE, PAL, RESTORE }

    /** The D1-D3 table: [matching] null = setting not read yet; [family] null = rate not known yet. */
    fun action(matching: Boolean?, fullScreen: Boolean, family: LiveFrameRate.Family?): Action = when {
        matching == null -> Action.NONE
        !matching -> Action.RESTORE
        // D2: the preview never switches; D3: an unknown rate keeps the display as it is.
        !fullScreen || family == null -> Action.NONE
        family == LiveFrameRate.Family.PAL -> Action.PAL
        else -> Action.RESTORE
    }

    /** The window's mode request, as [Switch] needs it. */
    interface Window {
        /** The `preferredDisplayModeId` the window asks for now (0 = no preference). */
        val requested: Int
        /** A 50 Hz mode at the current resolution; null when the display has none. */
        fun palModeId(): Int?
        fun request(modeId: Int)
    }

    /** What Live TV asked of the window, so a restore puts back exactly what was there before. */
    class Switch {
        /** The request before Live TV's first switch; null while Live TV has changed nothing. */
        private var before: Int? = null

        fun apply(action: Action, window: Window) {
            when (action) {
                Action.NONE -> Unit
                Action.RESTORE -> restore(window)
                Action.PAL -> {
                    val pal = window.palModeId() ?: return
                    if (before == null) before = window.requested
                    if (window.requested != pal) window.request(pal)
                }
            }
        }

        fun restore(window: Window) {
            val was = before ?: return
            before = null
            if (window.requested != was) window.request(was)
        }
    }

    private val switch = Switch()

    /** Leaving Live TV (the screen stops): what the window asked for before, if Live TV changed it. */
    fun restore(activity: Activity) {
        windowOf(activity)?.let(switch::restore)
    }

    /** The full-screen rule: runs while Live TV shows, [fullScreen] as the screen has it. */
    @Composable
    fun Follow(player: LivePlayer, activity: Activity?, fullScreen: Boolean) {
        val family by player.rateFamily.collectAsState()
        val matching by player.frameRateMatching.collectAsState()
        LaunchedEffect(activity, fullScreen, family, matching) {
            val window = activity?.let(::windowOf) ?: return@LaunchedEffect
            switch.apply(action(matching, fullScreen, family), window)
        }
    }

    private fun windowOf(activity: Activity): Window? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val window = activity.window ?: return null
        return object : Window {
            override val requested: Int get() = window.attributes.preferredDisplayModeId

            override fun palModeId(): Int? {
                val display = window.decorView.display ?: return null
                val active = display.mode
                return display.supportedModes.firstOrNull {
                    it.physicalWidth == active.physicalWidth &&
                        it.physicalHeight == active.physicalHeight &&
                        abs(it.refreshRate - PAL_HZ) <= PAL_TOLERANCE_HZ
                }?.modeId
            }

            override fun request(modeId: Int) {
                Log.i(TAG, "Display mode request $modeId")
                window.attributes = window.attributes.also { it.preferredDisplayModeId = modeId }
            }
        }
    }

    private const val TAG = "LiveDisplayRate"
    private const val PAL_HZ = 50f
    private const val PAL_TOLERANCE_HZ = 0.1f
}
