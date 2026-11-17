package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** U5h/U5j source row: the text under the name (QU5-6, addon formatting) and the languages line (P7). */
class StreamSourceRowTest {

    @Test
    fun `file name wins over the addon description, AIOStreams first`() {
        val stream = stream(
            description = "4K DV | 58 GB | cached",
            hintFilename = "Movie.2012.2160p.Hint-GRP.mkv",
            aioFilename = "Movie.2012.2160p.UHD.BluRay.REMUX-GRP.mkv"
        )
        assertEquals("Movie.2012.2160p.UHD.BluRay.REMUX-GRP.mkv", badgesText(stream))
    }

    @Test
    fun `hint, then resolver file names when AIOStreams sends none or a blank one`() {
        assertEquals(
            "Movie.2012.1080p.Hint-GRP.mkv",
            badgesText(stream(aioFilename = " ", hintFilename = "Movie.2012.1080p.Hint-GRP.mkv"))
        )
        assertEquals(
            "Movie.2012.1080p.Resolve-GRP.mkv",
            badgesText(stream(resolveFilename = "Movie.2012.1080p.Resolve-GRP.mkv"))
        )
        assertEquals(
            "Movie.2012.1080p.Raw-GRP.mkv",
            badgesText(stream(rawFilename = "Movie.2012.1080p.Raw-GRP.mkv"))
        )
    }

    @Test
    fun `no file name falls back to the description, else the title`() {
        assertEquals("Movie 2012 1080p WEB-DL", badgesText(stream(description = "Movie 2012 1080p WEB-DL")))
        assertEquals("Movie 2012 720p", badgesText(stream(title = "Movie 2012 720p")))
    }

    @Test
    fun `a description that only repeats the name, or is blank, gives no line`() {
        assertNull(badgesText(stream(description = NAME)))
        assertNull(badgesText(stream(description = "  ")))
        assertNull(badgesText(stream()))
    }

    @Test
    fun `badges mode flows a multi-line description onto one line`() {
        assertEquals(
            "Movie.2012.1080p.mkv 12 seeders 2 GB",
            badgesText(stream(description = "Movie.2012.1080p.mkv\n  12 seeders \n\n2 GB"))
        )
    }

    @Test
    fun `addon formatting shows the description as written, never the file name`() {
        val block = "WEB-DL  HEVC\n2160p  DV\nEnglish"
        assertEquals(
            block,
            addonText(stream(description = block, aioFilename = "Movie.2012.2160p.mkv"))
        )
        assertNull(addonText(stream(description = NAME, aioFilename = "Movie.2012.2160p.mkv")))
        assertNull(addonText(stream(aioFilename = "Movie.2012.2160p.mkv")))
        assertNull(addonText(stream(description = " \n ", aioFilename = "Movie.2012.2160p.mkv")))
    }

    @Test
    fun `addon formatting trims outer blank lines, so a padded copy of the name is still a repeat`() {
        assertEquals("WEB-DL\nEnglish", addonText(stream(description = "\nWEB-DL\nEnglish\n\n")))
        assertNull(addonText(stream(description = "$NAME\n")))
    }

    @Test
    fun `languages join with a middle dot, MULTI stays one token, none is null`() {
        assertEquals("EN · NL", streamRowLanguages(listOf("EN", "NL")))
        assertEquals("MULTI", streamRowLanguages(listOf("MULTI")))
        assertNull(streamRowLanguages(emptyList()))
    }

    // Spec 42 D6: the kind ends the languages line.
    @Test
    fun `the stream kind is the debrid short code, WEB or P2P, else nothing`() {
        fun kind(type: String?, service: String? = null) = streamKindLabel(
            AioStreamData(size = null, filename = null, cached = null, parsedFile = null, type = type, serviceId = service)
        )
        assertEquals("PM", kind("debrid", "premiumize"))
        assertEquals("RD", kind("debrid", "realdebrid"))
        assertEquals("DEBRID", kind("debrid", "seedr"))
        assertEquals("DEBRID", kind("debrid"))
        assertEquals("WEB", kind("http"))
        assertEquals("P2P", kind("p2p"))
        assertNull(kind("usenet"))
        assertNull(kind(null))
        assertNull(streamKindLabel(null))
    }

    @Test
    fun `the languages line puts the kind last`() {
        assertEquals("EN · NL · PM", streamRowLanguageLine("EN · NL", "PM"))
        assertEquals("MULTI", streamRowLanguageLine("MULTI", null))
        assertEquals("WEB", streamRowLanguageLine(null, "WEB"))
        assertNull(streamRowLanguageLine(null, null))
    }

    // Spec 97 X6: hidden names show only a release file name, whatever the formatting setting.
    @Test
    fun `hidden names keep the file name and never show the addon description`() {
        val named = stream(description = "[RD+] Torrentio 4K | 58 GB", aioFilename = "Movie.2012.2160p.REMUX-GRP.mkv")
        assertEquals("Movie.2012.2160p.REMUX-GRP.mkv", hiddenText(named, useAddonFormatting = false))
        assertEquals("Movie.2012.2160p.REMUX-GRP.mkv", hiddenText(named, useAddonFormatting = true))
        assertNull(hiddenText(stream(description = "[RD+] Torrentio 4K | 58 GB"), useAddonFormatting = false))
        assertNull(hiddenText(stream(description = "[RD+] Torrentio 4K | 58 GB"), useAddonFormatting = true))
    }

    @Test
    fun `hidden names fall back through the hint and resolver file names, never past them`() {
        assertEquals("Movie.2012.1080p.Hint-GRP.mkv", hiddenText(stream(aioFilename = " ", hintFilename = "Movie.2012.1080p.Hint-GRP.mkv"), false))
        assertEquals("Movie.2012.1080p.Resolve-GRP.mkv", hiddenText(stream(resolveFilename = "Movie.2012.1080p.Resolve-GRP.mkv"), false))
        assertEquals("Movie.2012.1080p.Raw-GRP.mkv", hiddenText(stream(rawFilename = "Movie.2012.1080p.Raw-GRP.mkv"), false))
        // No file name: no line, not the title or description (D8).
        assertNull(hiddenText(stream(title = "Torrentio 720p", description = "AIOStreams 720p"), false))
    }

    @Test
    fun `hidden names drop the debrid code and keep WEB and P2P`() {
        fun kind(type: String?, service: String? = null) = streamKindLabel(
            AioStreamData(size = null, filename = null, cached = null, parsedFile = null, type = type, serviceId = service),
            hideNames = true
        )
        assertNull(kind("debrid", "premiumize"))
        assertNull(kind("debrid"))
        assertEquals("WEB", kind("http"))
        assertEquals("P2P", kind("p2p"))
    }

    private fun hiddenText(stream: Stream, useAddonFormatting: Boolean): String? =
        streamRowDetailText(stream, NAME, useAddonFormatting, hideNames = true)

    private fun badgesText(stream: Stream): String? = streamRowDetailText(stream, NAME, useAddonFormatting = false)

    private fun addonText(stream: Stream): String? = streamRowDetailText(stream, NAME, useAddonFormatting = true)

    private fun stream(
        description: String? = null,
        title: String? = null,
        hintFilename: String? = null,
        aioFilename: String? = null,
        resolveFilename: String? = null,
        rawFilename: String? = null
    ): Stream = Stream(
        name = NAME,
        title = title,
        description = description,
        url = "https://example.net/stream",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = hintFilename
        ),
        addonName = "Example Addon",
        addonLogo = null,
        clientResolve = if (resolveFilename != null || rawFilename != null) {
            StreamClientResolve(
                type = null, infoHash = null, fileIdx = null, sources = null, torrentName = null,
                filename = resolveFilename, mediaType = null, title = null, season = null,
                episode = null, service = null,
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        torrentName = null, filename = rawFilename, size = null, network = null, parsed = null
                    )
                )
            )
        } else {
            null
        },
        streamData = aioFilename?.let { AioStreamData(size = null, filename = it, cached = true, parsedFile = null) }
    )

    private companion object {
        const val NAME = "Example 2160p"
    }
}
