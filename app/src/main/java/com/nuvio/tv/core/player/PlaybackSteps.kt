package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.FailureCause
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Spec 75 D1.2, D2: the six steps of starting a stream, in the order the step line draws them.
 * PICTURE and SOUND both follow READ (they run side by side); the line still draws them in order.
 */
enum class PlaybackStep { SOURCES, LINK, CONNECT, READ, PICTURE, SOUND }

/** One segment: passed (green), failed (red) or not reached (grey); [durationMs] null when not measured. */
data class StepResult(val step: PlaybackStep, val status: Status, val durationMs: Long?) {
    enum class Status { PASSED, FAILED, NOT_REACHED }
}

/** The step line of one failed attempt; [failed] is its red step. */
data class StepLine(val steps: List<StepResult>) {
    val failed: PlaybackStep? get() = steps.firstOrNull { it.status == StepResult.Status.FAILED }?.step
}

/**
 * When each step of one attempt ended, in SystemClock.elapsedRealtime() ms, null when not seen:
 * [startedAtMs] the player began opening the stream; [linkAtMs] the link was known (the last
 * redirect answered, or the first request left when there was none); [connectAtMs] the serving
 * host answered; [tracksAtMs] the file's tracks were read; [pictureAtMs] the first frame;
 * [soundAtMs] the audio output opened. [sourcesMs] is the Stream screen's search, a duration.
 */
data class StepMarks(
    val startedAtMs: Long,
    val sourcesMs: Long? = null,
    val linkAtMs: Long? = null,
    val connectAtMs: Long? = null,
    val tracksAtMs: Long? = null,
    val pictureAtMs: Long? = null,
    val soundAtMs: Long? = null
)

internal object PlaybackSteps {

    /** The step a cause fails at (D4 table); null = the step the attempt had reached. */
    fun stepOf(cause: FailureCause): PlaybackStep? = when (cause) {
        FailureCause.LINK_EXPIRED, FailureCause.BLOCKED, FailureCause.REMOVED, FailureCause.RATE_LIMITED,
        FailureCause.SERVER_DOWN, FailureCause.OTHER_HTTP, FailureCause.NO_CONNECTION -> PlaybackStep.CONNECT

        FailureCause.WEB_PAGE, FailureCause.TINY_FILE, FailureCause.TEXT, FailureCause.LOGIN,
        FailureCause.UNREADABLE, FailureCause.TOO_SLOW, FailureCause.NO_DATA, FailureCause.PLACEHOLDER,
        FailureCause.CORRUPT -> PlaybackStep.READ

        FailureCause.VIDEO_UNSUPPORTED, FailureCause.DECODER_STUCK, FailureCause.FROZEN -> PlaybackStep.PICTURE
        FailureCause.AUDIO_FAILED -> PlaybackStep.SOUND
        FailureCause.TORRENT, FailureCause.NO_URL -> PlaybackStep.LINK
        FailureCause.ENGINE, FailureCause.UNKNOWN, FailureCause.WRONG_FILE -> null
    }

    /**
     * The step the attempt had reached: the one after the latest step that ended (SOUND once the
     * picture is up, since the two run side by side; LINK when nothing ended yet).
     */
    fun reachedStep(marks: StepMarks): PlaybackStep = when {
        marks.pictureAtMs != null -> PlaybackStep.SOUND
        marks.soundAtMs != null -> PlaybackStep.PICTURE
        marks.tracksAtMs != null -> PlaybackStep.PICTURE
        marks.connectAtMs != null -> PlaybackStep.READ
        marks.linkAtMs != null -> PlaybackStep.CONNECT
        else -> PlaybackStep.LINK
    }

    /**
     * The line for an attempt that failed at [failed] ([nowMs] the failure): steps before it
     * passed with their measured time (none when unmeasured, D4), the failed one red with its
     * own time (measured when it had ended, else, before the first frame, since the last step
     * that did; after it, none), the rest grey.
     */
    fun line(marks: StepMarks, failed: PlaybackStep, nowMs: Long): StepLine {
        val failedIndex = failed.ordinal
        val steps = PlaybackStep.entries.map { step ->
            when {
                // Picture and Sound run side by side: a Sound failure before the first frame leaves
                // Picture unreached, not passed.
                step == PlaybackStep.PICTURE && failed == PlaybackStep.SOUND && marks.pictureAtMs == null ->
                    StepResult(step, StepResult.Status.NOT_REACHED, null)
                step.ordinal < failedIndex -> StepResult(step, StepResult.Status.PASSED, durationOf(step, marks))
                // After the first frame the failure time is the playback time, not the step's: only a
                // measured time shows (D4, never one that was not measured).
                step == failed -> StepResult(
                    step,
                    StepResult.Status.FAILED,
                    durationOf(step, marks) ?: sinceLastEnd(step, marks, nowMs).takeIf { marks.pictureAtMs == null }
                )
                else -> StepResult(step, StepResult.Status.NOT_REACHED, null)
            }
        }
        return StepLine(steps)
    }

    /**
     * A step's measured time: its end minus the end of the step it follows; null when either is
     * unknown or they are out of order (never an invented 0 ms).
     */
    private fun durationOf(step: PlaybackStep, marks: StepMarks): Long? {
        if (step == PlaybackStep.SOURCES) return marks.sourcesMs?.takeIf { it >= 0L }
        val end = endOf(step, marks) ?: return null
        val start = startOf(step, marks) ?: return null
        return (end - start).takeIf { it >= 0L }
    }

    /** A failed step that never ended: from the latest end before it (or the start) to [nowMs]. */
    private fun sinceLastEnd(step: PlaybackStep, marks: StepMarks, nowMs: Long): Long? {
        if (step == PlaybackStep.SOURCES) return null
        val from = startOf(step, marks) ?: latestEndBefore(step, marks)
        return (nowMs - from).takeIf { it >= 0L }
    }

    private fun endOf(step: PlaybackStep, marks: StepMarks): Long? = when (step) {
        PlaybackStep.SOURCES -> null
        PlaybackStep.LINK -> marks.linkAtMs
        PlaybackStep.CONNECT -> marks.connectAtMs
        PlaybackStep.READ -> marks.tracksAtMs
        PlaybackStep.PICTURE -> marks.pictureAtMs
        PlaybackStep.SOUND -> marks.soundAtMs
    }

    /** The end of the step this one directly follows (both PICTURE and SOUND follow READ). */
    private fun startOf(step: PlaybackStep, marks: StepMarks): Long? = when (step) {
        PlaybackStep.SOURCES -> null
        PlaybackStep.LINK -> marks.startedAtMs
        PlaybackStep.CONNECT -> marks.linkAtMs
        PlaybackStep.READ -> marks.connectAtMs
        PlaybackStep.PICTURE, PlaybackStep.SOUND -> marks.tracksAtMs
    }

    /** The latest step end before [step] (the attempt's start when none), for a gap in the marks. */
    private fun latestEndBefore(step: PlaybackStep, marks: StepMarks): Long {
        val order = listOf(PlaybackStep.LINK, PlaybackStep.CONNECT, PlaybackStep.READ)
        return order.filter { it.ordinal < step.ordinal }.mapNotNull { endOf(it, marks) }.maxOrNull() ?: marks.startedAtMs
    }
}

/**
 * The live marks of the attempt now opening (Spec 75 S3): [start] at each player build, the
 * player's own events for tracks, picture and sound, and the playback HTTP client's events for
 * link and connect (OkHttp threads, through [PlaybackConnectionEvents.stepClock]). Each mark is
 * first-wins per attempt, except the link, which moves with each redirect until the serving host
 * answers. Only a call for the attempt's own stream URL (or the URL it redirected to) counts, so a
 * subtitle download on the same client does not (a network test before the play restarts the
 * attempt after it); and a call carries the
 * [attempt] it started in, so one left over from an earlier attempt (a released player's chunk, a
 * prewarm of the previous stream) never marks this one. A prewarm of this same URL during the
 * attempt can mark the link a little early; it reaches the same server, so the times stay true.
 */
internal class PlaybackStepClock {
    @Volatile private var marks: StepMarks? = null

    /** Which attempt is opening; 0 before the first [start]. */
    @Volatile var attempt: Long = 0L
        private set

    /** The attempt's stream URL as OkHttp writes it, null when it has none (not HTTP, or no URL). */
    @Volatile var url: String? = null
        private set

    @Synchronized
    fun start(nowMs: Long, sourcesMs: Long?, url: String?) {
        attempt += 1
        this.url = url?.toHttpUrlOrNull()?.toString()
        marks = StepMarks(startedAtMs = nowMs, sourcesMs = sourcesMs)
    }

    /**
     * The attempt a call to [requestUrl] belongs to, or null when it is not this stream's: the
     * stream URL itself, or where it redirected to (a prewarm at the press resolved it before the
     * attempt; the player then asks the resolved URL directly, [PlaybackConnectionEvents.originOf]).
     * One read under the lock, so a [start] in between cannot pair the old URL with the new attempt.
     */
    @Synchronized
    fun attemptFor(requestUrl: String): Long? {
        val own = url ?: return null
        return if (requestUrl == own || PlaybackConnectionEvents.originOf(requestUrl) == own) attempt else null
    }

    /** A playback request of [ofAttempt] left: the link is known by now unless a redirect follows. */
    @Synchronized
    fun requestStarted(ofAttempt: Long, nowMs: Long) = update(ofAttempt) {
        if (it.linkAtMs == null && it.connectAtMs == null) it.copy(linkAtMs = nowMs) else it
    }

    /** A redirect answered: the link resolved one hop further. */
    @Synchronized
    fun redirectAnswered(ofAttempt: Long, nowMs: Long) = update(ofAttempt) {
        if (it.connectAtMs == null) it.copy(linkAtMs = nowMs) else it
    }

    /** The serving host answered (any status). */
    @Synchronized
    fun servingAnswered(ofAttempt: Long, nowMs: Long) = update(ofAttempt) {
        if (it.connectAtMs == null) it.copy(connectAtMs = nowMs) else it
    }

    @Synchronized
    fun tracksRead(nowMs: Long) = update(attempt) { if (it.tracksAtMs == null) it.copy(tracksAtMs = nowMs) else it }

    @Synchronized
    fun pictureShown(nowMs: Long) = update(attempt) { if (it.pictureAtMs == null) it.copy(pictureAtMs = nowMs) else it }

    @Synchronized
    fun soundOpened(nowMs: Long) = update(attempt) { if (it.soundAtMs == null) it.copy(soundAtMs = nowMs) else it }

    /** The current attempt's marks, null before the first [start]. */
    fun snapshot(): StepMarks? = marks

    private inline fun update(ofAttempt: Long, change: (StepMarks) -> StepMarks) {
        if (ofAttempt != attempt) return
        val current = marks ?: return
        marks = change(current)
    }
}

/** The step line for a failure of [cause] now, or null before the first attempt started. */
internal fun PlayerRuntimeController.stepLineFor(cause: FailureCause): StepLine? {
    val marks = stepClock.snapshot() ?: return null
    val failed = PlaybackSteps.stepOf(cause) ?: PlaybackSteps.reachedStep(marks)
    return PlaybackSteps.line(marks, failed, android.os.SystemClock.elapsedRealtime())
}
