package com.nuvio.tv.core.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastExitReasonTest {

    @Test
    fun `crashes and ANRs classify at any importance`() {
        assertEquals(AbnormalExitKind.CRASH, LastExitReason.classify(ApplicationExitInfo.REASON_CRASH, IMPORTANCE_CACHED))
        assertEquals(
            AbnormalExitKind.NATIVE_CRASH,
            LastExitReason.classify(ApplicationExitInfo.REASON_CRASH_NATIVE, IMPORTANCE_FOREGROUND),
        )
        assertEquals(AbnormalExitKind.ANR, LastExitReason.classify(ApplicationExitInfo.REASON_ANR, IMPORTANCE_FOREGROUND))
    }

    @Test
    fun `memory kill counts only in the foreground`() {
        assertEquals(
            AbnormalExitKind.MEMORY_KILL,
            LastExitReason.classify(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_FOREGROUND),
        )
        // The boundary: anything less important than the activity on screen is not "in use".
        assertNull(LastExitReason.classify(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_FOREGROUND_SERVICE))
        assertNull(LastExitReason.classify(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_VISIBLE))
        assertNull(LastExitReason.classify(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_CACHED))
    }

    @Test
    fun `normal ends are ignored`() {
        listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_UNKNOWN,
        ).forEach { assertNull(LastExitReason.classify(it, IMPORTANCE_FOREGROUND)) }
    }

    @Test
    fun `description drops URLs and newlines and is capped`() {
        val out = LastExitReason.sanitizeDescription("boom https://h.example/p?token=abc\nnext")
        assertEquals("boom <url> next", out)
        assertEquals("bad <url> end", LastExitReason.sanitizeDescription("bad magnet:?xt=urn:btih:abc end"))
        assertEquals(160, LastExitReason.sanitizeDescription("x".repeat(500)).length)
        assertEquals("", LastExitReason.sanitizeDescription(null))
    }
}
