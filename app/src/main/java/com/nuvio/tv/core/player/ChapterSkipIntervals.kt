package com.nuvio.tv.core.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.fillMissingSkipCategories
import com.nuvio.tv.data.repository.withPlausiblePositions

/**
 * Spec 61 Q6 (NM PR #4346 `ChapterSkipIntervals`): skip intervals from the file's chapter names,
 * for a category no online source covers.
 *
 * Titles are matched case-insensitively, and only when the title is essentially the segment
 * label itself (with an optional number), so scene-named chapters like "The Opening" or
 * "Alternate Ending" never match. Matched: OP/Opening, ED/Ending/Credits, Recap/Previously,
 * Intro. "Intro" counts as the opening only when no OP/Opening chapter exists. Post-, mid- and
 * after-credits chapters are never skipped.
 */
internal object ChapterSkipIntervals {

    private const val PROVIDER = "chapters"

    private const val MIN_DURATION_SEC = 5.0
    private const val MAX_DURATION_SEC = 240.0 // 4 minutes max for OP/ED/recap

    private val OPENING_PATTERN = Regex("^\\s*(op(ening)?)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val ENDING_PATTERN = Regex("^\\s*(ed|ending)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val CREDITS_PATTERN = Regex("^\\s*(end|closing)?\\s*credits\\s*$", RegexOption.IGNORE_CASE)
    private val RECAP_PATTERN = Regex("^\\s*(recap|previously(\\s+on.*)?)\\s*$", RegexOption.IGNORE_CASE)
    private val INTRO_PATTERN = Regex("^\\s*intro\\s*$", RegexOption.IGNORE_CASE)

    // A film's scene chapters are often named just "Opening", "Ending" or "Intro", or after a
    // character ("Ed"); outside an episode only the forms that cannot be a scene name count:
    // the upper-case abbreviations and the labels that say credits or theme.
    private val MOVIE_OPENING_PATTERN =
        Regex("^\\s*(OP|(?i:opening\\s*(credits|theme)))\\s*\\d*\\s*$")
    private val MOVIE_ENDING_PATTERN =
        Regex("^\\s*(ED|(?i:ending\\s*(credits|theme)))\\s*\\d*\\s*$")

    private val POST_CREDITS_PATTERN = Regex("(post|mid|after)[\\s-]credits", RegexOption.IGNORE_CASE)

    /**
     * [chapters] sorted by start ([PlaybackChapter.normalized]). A chapter ends where the next
     * starts; the last one ends at [durationMs] and is left out while that is unknown (<= 0).
     */
    fun fromChapters(
        chapters: List<PlaybackChapter>,
        durationMs: Long,
        isEpisode: Boolean = true
    ): List<SkipInterval> {
        if (chapters.size < 2) return emptyList()

        val classified = chapters.mapIndexed { index, chapter ->
            Classified(
                startMs = chapter.startMs,
                endMs = chapters.getOrNull(index + 1)?.startMs ?: durationMs,
                type = classifyTitle(chapter.title, isEpisode)
            )
        }
        val hasExplicitOpening = classified.any { it.type == ChapterType.OPENING }

        return classified.mapNotNull { c ->
            val skipType = when (c.type) {
                null, ChapterType.POST_CREDITS -> return@mapNotNull null
                ChapterType.INTRO -> if (hasExplicitOpening) return@mapNotNull null else "intro"
                ChapterType.OPENING -> "intro"
                ChapterType.ENDING -> "outro"
                ChapterType.RECAP -> "recap"
            }
            val durationSec = (c.endMs - c.startMs) / 1000.0
            if (durationSec < MIN_DURATION_SEC || durationSec > MAX_DURATION_SEC) return@mapNotNull null
            SkipInterval(
                startTime = c.startMs / 1000.0,
                endTime = c.endMs / 1000.0,
                type = skipType,
                provider = PROVIDER
            )
        }
    }

    private fun classifyTitle(title: String?, isEpisode: Boolean): ChapterType? {
        if (title.isNullOrBlank()) return null
        // Post-credits first: the credits pattern must not see it.
        if (POST_CREDITS_PATTERN.containsMatchIn(title)) return ChapterType.POST_CREDITS
        if ((if (isEpisode) OPENING_PATTERN else MOVIE_OPENING_PATTERN).matches(title)) return ChapterType.OPENING
        if ((if (isEpisode) ENDING_PATTERN else MOVIE_ENDING_PATTERN).matches(title)) return ChapterType.ENDING
        if (CREDITS_PATTERN.matches(title)) return ChapterType.ENDING
        if (RECAP_PATTERN.matches(title)) return ChapterType.RECAP
        if (isEpisode && INTRO_PATTERN.matches(title)) return ChapterType.INTRO
        return null
    }

    private enum class ChapterType { OPENING, ENDING, RECAP, INTRO, POST_CREDITS }

    private class Classified(val startMs: Long, val endMs: Long, val type: ChapterType?)
}

/**
 * The skip intervals playback acts on: the online ones ([PlayerRuntimeController.skipIntervals]),
 * less any implausibly placed one, plus chapter-name intervals for the categories they lack
 * (Spec 61 D2). Kept per (online list, chapter list, duration, episode or film), so the progress
 * tick does not re-match titles; a stream switch replaces the lists and with them the result.
 * Until the online reply lands every category is open, so a chapter interval can act first.
 */
internal class ChapterSkipMerge {
    private var online: List<SkipInterval>? = null
    private var chapters: List<PlaybackChapter>? = null
    private var durationMs = -1L
    private var isEpisode = true
    private var merged: List<SkipInterval> = emptyList()

    fun merged(
        online: List<SkipInterval>,
        chapters: List<PlaybackChapter>,
        durationMs: Long,
        isEpisode: Boolean = true
    ): List<SkipInterval> {
        if (online !== this.online || chapters !== this.chapters ||
            durationMs != this.durationMs || isEpisode != this.isEpisode
        ) {
            this.online = online
            this.chapters = chapters
            this.durationMs = durationMs
            this.isEpisode = isEpisode
            merged = fillMissingSkipCategories(
                online.withPlausiblePositions(durationMs),
                ChapterSkipIntervals.fromChapters(chapters, durationMs, isEpisode)
            )
        }
        return merged
    }
}

internal fun PlayerRuntimeController.effectiveSkipIntervals(): List<SkipInterval> {
    if (!skipIntroEnabled) return emptyList()
    return chapterSkipMerge.merged(
        online = skipIntervals,
        chapters = _uiState.value.chapters,
        durationMs = currentPlaybackDurationMs().coerceAtLeast(0L),
        // An episode number makes it an episode (absolute-numbered anime has no season);
        // anything else gets the film's stricter titles.
        isEpisode = currentEpisode != null
    )
}
