package com.nuvio.tv.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

enum class AbnormalExitKind(val label: String) {
    CRASH("crash"),
    NATIVE_CRASH("native-crash"),
    ANR("anr"),

    /** 70/D1: a low-memory kill while the app was in front counts as a crash, labelled apart. */
    MEMORY_KILL("memory-kill"),
}

data class AbnormalExit(val kind: AbnormalExitKind, val timestampMs: Long)

/**
 * Spec 70 R1: why earlier processes ended, from `ActivityManager.getHistoricalProcessExitReasons`
 * (API 30+; older devices have neither the log line nor the Settings row). Device-local only:
 * one logcat line per start and the Advanced › General row, no telemetry (KE removed crash
 * reporting).
 */
object LastExitReason {
    private const val TAG = "NuvioExit"
    private const val MAX_DESCRIPTION_CHARS = 160
    private val URL = Regex("""\b(?:\w+://|magnet:\?)\S+""")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Abnormal ends only. User exits, self-exit, background kills and power-off return null.
     * A memory kill counts only at foreground importance (the activity on screen; a foreground
     * service alone is not "in use"). Devices without lmkd kill reporting record such kills as
     * REASON_SIGNALED, which stays unclassified (to check on the Shield at acceptance).
     */
    fun classify(reason: Int, importance: Int): AbnormalExitKind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> AbnormalExitKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> AbnormalExitKind.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> AbnormalExitKind.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY
            if importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
            AbnormalExitKind.MEMORY_KILL
        else -> null
    }

    /** Descriptions can quote an exception message; keep URLs (tokens in queries) out of the log. */
    fun sanitizeDescription(description: String?): String =
        description.orEmpty()
            .replace(URL, "<url>")
            .replace('\n', ' ')
            .take(MAX_DESCRIPTION_CHARS)

    /**
     * The newest abnormal end in Android's own short history of this app's processes, or null.
     * Blocking binder call: run off the main thread.
     */
    fun latestAbnormal(context: Context): AbnormalExit? =
        // Newest first; 0 = every record Android kept.
        exits(context, maxNum = 0).firstNotNullOfOrNull { exit ->
            classify(exit.reason, exit.importance)?.let { AbnormalExit(it, exit.timestamp) }
        }

    /** Logs the previous process's end. Blocking binder call: run off the main thread. */
    fun logLastExit(context: Context) {
        val exit = exits(context, maxNum = 1).firstOrNull() ?: return
        val kind = classify(exit.reason, exit.importance)
        val ageMin = (System.currentTimeMillis() - exit.timestamp) / 60_000L
        if (kind == null) {
            Log.i(TAG, "LAST_EXIT kind=none reason=${exit.reason} ageMin=$ageMin")
        } else {
            Log.w(
                TAG,
                "LAST_EXIT kind=${kind.label} reason=${exit.reason} " +
                    "importance=${exit.importance} ageMin=$ageMin " +
                    "pssKiB=${exit.pss} rssKiB=${exit.rss} " +
                    "desc=${sanitizeDescription(exit.description)}"
            )
        }
    }

    /** Empty below API 30 or when the read fails: a diagnostic must never break its caller. */
    private fun exits(context: Context, maxNum: Int): List<ApplicationExitInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, maxNum)
                .orEmpty()
        } catch (e: RuntimeException) {
            // SecurityException / binder failures.
            Log.w(TAG, "LAST_EXIT read failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }
}
