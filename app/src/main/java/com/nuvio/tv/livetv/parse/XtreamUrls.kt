package com.nuvio.tv.livetv.parse

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Xtream Codes stream URL shapes (Spec 78; pvr.iptvsimple `GenerateXtreamCodesCatchupSource`,
 * research 2026-10-17 r10): live `host/[live/]user/pass/id[.ts|.m3u8]`, movie
 * `host/movie/user/pass/id.ext`, series episode `host/series/user/pass/id.ext`; `id` is numeric.
 * Only the path is read; the URL is never logged (its path carries the account's password, KT-SEC-02).
 */
internal object XtreamUrls {
    private val NUMERIC_ID = Regex("""\d+""")
    private val NUMERIC_FILE = Regex("""\d+(\.[A-Za-z0-9]+)?""")

    /**
     * A movie or series-episode item of a full `m3u_plus` list (D1: not a channel). Shape only: a
     * non-Xtream URL of exactly this shape is skipped too (accepted in D1).
     */
    fun isVod(url: String): Boolean {
        // Cheap screen first: the parser asks this of every playlist item.
        if (!url.contains("/movie/") && !url.contains("/series/")) return false
        val segments = url.toHttpUrlOrNull()?.pathSegments ?: return false
        return segments.size == 4 && segments[0].let { it == "movie" || it == "series" } &&
            hasAccount(segments, 1) && NUMERIC_FILE.matches(segments[3])
    }

    /**
     * A live stream with no extension and no query (`host/[live/]user/pass/<digits>`): Xtream
     * serves MPEG-TS there, so it plays as progressive without a sniff request (D3). Shape only:
     * a non-Xtream adaptive stream of this exact shape that its path tokens do not type fails
     * as progressive (accepted in D3).
     */
    fun isBareLive(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        if (http.encodedQuery != null || http.encodedFragment != null) return false
        val segments = http.pathSegments
        val from = when (segments.size) {
            3 -> 0
            4 -> if (segments[0] == "live") 1 else return false
            else -> return false
        }
        return hasAccount(segments, from) && NUMERIC_ID.matches(segments[from + 2])
    }

    /** `user/pass` at [from]: both present. */
    private fun hasAccount(segments: List<String>, from: Int): Boolean =
        segments[from].isNotBlank() && segments[from + 1].isNotBlank()
}
