package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import kotlin.math.roundToLong

/**
 * M3U `tvg-shift` (Spec 94 T2), as Kodi's IPTV Simple reads it: on `#EXTINF` the channel's guide
 * shift in hours (fractional and negative allowed), on `#EXTM3U` the default for channels without
 * one; programme times move by +shift. Playlist values are untrusted: only a plain decimal within
 * [MAX_HOURS] counts, anything else reads as no shift.
 */
object GuideShift {
    const val ATTR = "tvg-shift"
    private const val MAX_HOURS = 24
    // Kodi reads the value with atof: `.5` and `2.` count too.
    private val DECIMAL = Regex("""[+-]?(\d{1,2}(\.\d{0,6})?|\.\d{1,6})""")

    /** [raw] hours as milliseconds (whole seconds); null when blank, malformed or out of range. */
    fun parseMs(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { DECIMAL.matches(it) } ?: return null
        val hours = s.toDouble()
        if (hours < -MAX_HOURS || hours > MAX_HOURS) return null
        return (hours * 3600).roundToLong() * 1000
    }

    /** [ch]'s shift: its own `tvg-shift` (an explicit 0 included), else [headerShiftMs], its playlist's. */
    fun of(ch: Channel, headerShiftMs: Long): Long = parseMs(ch.attrs?.get(ATTR)) ?: headerShiftMs

    /** The header shift of each source whose `#EXTM3U` attributes carry a non-zero one ([attrs] by source id). */
    fun headerShifts(attrs: Map<String, Map<String, String>?>): Map<String, Long> =
        attrs.mapNotNull { (id, a) -> parseMs(a?.get(ATTR))?.takeIf { it != 0L }?.let { id to it } }.toMap()
}
