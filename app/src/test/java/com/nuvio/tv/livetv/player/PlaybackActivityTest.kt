package com.nuvio.tv.livetv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PlaybackActivity] (Spec 73 S3a): the main player's session or any live player counts as playing. */
class PlaybackActivityTest {

    @Test
    fun `playing while the main player is open or any live player is active`() = runTest {
        val session = MutableStateFlow(false)
        val activity = PlaybackActivity(session)
        val a = Any()
        val b = Any()
        assertFalse(activity.playing.first())
        activity.setLive(a, true)
        activity.setLive(b, true)
        activity.setLive(a, false)
        assertTrue(activity.playing.first())
        activity.setLive(b, false)
        activity.setLive(b, false)
        assertFalse(activity.playing.first())
        session.value = true
        assertTrue(activity.playing.first())
    }
}
