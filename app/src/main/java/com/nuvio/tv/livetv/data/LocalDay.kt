package com.nuvio.tv.livetv.data

import java.time.Instant
import java.time.ZoneId

/**
 * Epoch ms of local midnight of [ms]'s date in [zone], [plusDays] calendar days later: one home for
 * the day boundary the guide window (Spec 67 G1c) and the day schedule (G2) both use. Calendar
 * days, so a DST day is 23 or 25 h, never shifted by an hour.
 */
internal fun localDayStartMs(ms: Long, zone: ZoneId, plusDays: Long = 0): Long =
    // atZone().toLocalDate(), not LocalDate.ofInstant (Java 9; minSdk 24).
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().plusDays(plusDays).atStartOfDay(zone).toInstant().toEpochMilli()
