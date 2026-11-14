package com.nuvio.tv.ui.screens.detail

import android.text.format.DateFormat
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Full episode release date (episode lists on Details and in the player side panel).
 * Not the same formatter as extractYearText (ui/util/AirDateUtils.kt); see the note there.
 */
fun formatReleaseDate(isoDate: String): String {
    val releaseDate = parseEpisodeReleaseLocalDate(isoDate) ?: return ""
    return formatLocalDate(releaseDate, "dMMMMy")
}

/** Short date, "26 Sep 2012" in the device locale's order (About sheet releases, post-play card). */
fun formatShortDate(date: LocalDate): String = formatLocalDate(date, "dMMMy")

/** [formatShortDate] for an episode's release string; null when it does not parse. */
fun formatShortReleaseDate(isoDate: String?): String? =
    parseEpisodeReleaseLocalDate(isoDate)?.let(::formatShortDate)

/** Day and month, "2 Oct" in the device locale's order (Library's "Added" eyebrow). */
fun formatDayMonth(date: LocalDate): String = formatLocalDate(date, "dMMM")

/** Weekday, day and month, "Mon 9 Nov" in the device locale's order (Live TV schedule's day chip). */
fun formatWeekdayDayMonth(date: LocalDate): String = formatLocalDate(date, "EEEdMMM")

/** Time of day, "21:14" or "9:14 PM" by the device locale (Library's History, today). */
fun formatTimeOfDay(time: LocalDateTime): String = formatLocalDate(time, "jmm")

/** Weekday and time, "Thu 21:14" (Library's History, this week). */
fun formatWeekdayTime(time: LocalDateTime): String = formatLocalDate(time, "EEEjmm")

/** Month name, with the year when [withYear] ("September", "September 2025"; Library's History rows). */
fun formatMonth(month: YearMonth, withYear: Boolean): String =
    formatLocalDate(month.atDay(1), if (withYear) "MMMMy" else "LLLL")

/** [date] in the device locale's best pattern for [skeleton]. */
private fun formatLocalDate(date: TemporalAccessor, skeleton: String): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}
