package com.nuvio.tv.livetv.parse

/**
 * Xtream Codes `get.php?type=m3u_plus` shapes (Spec 78 S1 inventory I1-I4), written from the
 * documented conventions (pvr.iptvsimple Omega README and `Channel.cpp`
 * `GenerateXtreamCodesCatchupSource`), not from a real provider list. Placeholder credentials only.
 */
internal object XtreamFixtures {
    const val HOST = "http://xt.example:8080"

    const val LIVE_TS = "$HOST/user1/pass1/1001.ts"
    const val LIVE_M3U8 = "$HOST/live/user1/pass1/1002.m3u8"
    const val LIVE_BARE = "$HOST/user1/pass1/1003"
    const val LIVE_BARE_LIVE_PREFIX = "$HOST/live/user1/pass1/1004"
    const val MOVIE = "$HOST/movie/user1/pass1/2001.mkv"
    const val SERIES = "$HOST/series/user1/pass1/3001.mp4"
    const val GUIDE = "$HOST/xmltv.php?username=user1&password=pass1"

    /** Live entries in each URL form, then a movie and a series episode, as one full m3u_plus list. */
    val M3U_PLUS = """
        #EXTM3U x-tvg-url="$GUIDE"
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="UK: BBC One HD" tvg-logo="$HOST/logo/bbc1.png" group-title="UK | General" catchup="xc" catchup-days="7",UK: BBC One HD
        $LIVE_TS
        #EXTINF:-1 tvg-id="itv1.uk" tvg-name="UK: ITV 1" tvg-logo="" group-title="UK | General" catchup="xc" catchup-days="3",UK: ITV 1
        $LIVE_M3U8
        #EXTINF:-1 tvg-id="sky.news.uk" tvg-name="UK: Sky News" tvg-logo="" group-title="UK | News",UK: Sky News
        $LIVE_BARE
        #EXTINF:-1 tvg-id="" tvg-name="UK: Channel 4" tvg-logo="" group-title="UK | General",UK: Channel 4
        $LIVE_BARE_LIVE_PREFIX
        #EXTINF:-1 tvg-id="" tvg-name="The Matrix (1999)" tvg-logo="$HOST/images/2001.jpg" group-title="VOD | Action",The Matrix (1999)
        $MOVIE
        #EXTINF:-1 tvg-id="" tvg-name="Doctor Who S01 E01" tvg-logo="$HOST/images/3001.jpg" group-title="Series | Sci-Fi",Doctor Who S01 E01
        $SERIES
    """.trimIndent()
}
