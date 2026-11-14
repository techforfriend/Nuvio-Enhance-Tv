package com.nuvio.tv.core.player

import okhttp3.Request

/**
 * Request for a side probe of a playback URL (AFR warm-up, range fetch and length, the
 * OpenSubtitles hash, the network mime probe): the stream's headers as the playback path
 * sanitizes them (trimmed; blank values, Range and headers OkHttp would reject dropped), plus the
 * player's default User-Agent when they carry none. Callers add their own Range or HEAD and
 * judge the response status themselves.
 */
internal fun probeRequestBuilder(url: String, headers: Map<String, String>): Request.Builder {
    val builder = Request.Builder().url(url)
    val sanitized = PlayerMediaSourceFactory.sanitizeHeaders(headers)
    sanitized.forEach { (name, value) -> builder.header(name, value) }
    PlayerMediaSourceFactory.defaultUserAgentUnlessSet(sanitized)?.let { builder.header("User-Agent", it) }
    return builder
}
