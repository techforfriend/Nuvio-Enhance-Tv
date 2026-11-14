package com.nuvio.tv.core.stream

import com.nuvio.tv.core.stream.ContentIdentityGuard.Rejection
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P3 (D6): reject only on a hard SxxEyy or film-year mismatch; everything unclear passes. */
class ContentIdentityGuardTest {

    private val daredevil = episode("Daredevil", 2015, 2, 6)

    @Test
    fun `wrong season or episode is rejected, ranges and packs pass`() {
        val severance = episode("Severance", 2022, 2, 3)
        assertEquals(Rejection.WRONG_EPISODE, eval("Severance.S02E04.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX.mkv", severance))
        assertEquals(Rejection.WRONG_SEASON, eval("Severance.S01E03.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX.mkv", severance))
        assertEquals(Rejection.WRONG_EPISODE, eval("severance s02 e07 1080p", severance))
        listOf(
            "Severance.S02E02E03.1080p.WEB.H264-GRP.mkv",
            "Severance.S02E01E02E03.1080p.WEB.H264-GRP.mkv",
            "Severance.S02E02-E04.1080p.WEB.H264-GRP.mkv",
            "Severance.S02E03-04.1080p.WEB.H264-GRP.mkv",
            "Severance.S02E01-S02E04.1080p.WEB.H264-GRP.mkv",
            "Severance.S01E01-S03E10.Complete.1080p.WEB.H264-GRP",
            "Severance.S02.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX",
            "Severance.S02E03.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX.mkv"
        ).forEach { assertNull(it, eval(it, severance)) }
        assertEquals(Rejection.WRONG_SEASON, eval("Severance.S03E01-S04E10.1080p.WEB", severance))
    }

    @Test
    fun `tags after an episode are not range ends`() {
        val severance = episode("Severance", 2022, 2, 3)
        listOf(
            "Severance.S02E05-720p.WEB.mkv",
            "Severance.S02E05-4K.WEB.mkv",
            "Severance.S02E05-10bit.WEB.mkv"
        ).forEach { assertEquals(it, Rejection.WRONG_EPISODE, eval(it, severance)) }
        assertNull(eval("Severance.S02E03-10bit.WEB.mkv", severance))
    }

    @Test
    fun `revision tags, other separators, specials and long episode numbers (review round 3)`() {
        val e6 = episode("Show", 2020, 1, 6)
        listOf(
            "Show.S01E05E06v2.1080p.mkv",
            "Show.S01E05-06v2.1080p.mkv",
            "Show.S01E05+E06.1080p.mkv",
            "Show.S01E05 - E06.1080p.mkv",
            "Show S01 E05-E06 1080p",
            "Show.S01E06v2.1080p.mkv"
        ).forEach { assertNull(it, eval(it, e6)) }
        assertNull(eval("Show.S01E01-S03E13v2.1080p", episode("Show", 2020, 2, 4)))
        // A reversed range reads as its first episode.
        assertNull(eval("Show.S01E06-03.1080p.mkv", e6))
        assertEquals(Rejection.WRONG_EPISODE, eval("Show.S01E07-03.1080p.mkv", e6))
        assertNull(eval("Show.S00E05.1080p.mkv", episode("Show", 2020, 0, 5)))
        assertEquals(Rejection.WRONG_SEASON, eval("Show.S01E05.1080p.mkv", episode("Show", 2020, 0, 5)))
        // A 4-digit episode is unparseable here, so it passes.
        assertNull(eval("One.Piece.S01E1085.1080p.mkv", episode("One Piece", 1999, 1, 1085)))
        assertNull(eval("S.W.A.T.2017.S05E03.1080p.mkv", episode("S.W.A.T.", 2017, 5, 3)))
        assertNull(eval("Station.19.S05E03.1080p.mkv", episode("Station 19", 2018, 5, 3)))
    }

    @Test
    fun `every candidate rejected stays rejected (D7, no escape)`() {
        val e6 = episode("Show", 2020, 1, 6)
        val mismatched = listOf(named("Show.S02E01.1080p.mkv"), named("Show.S03E04.1080p.mkv"))
        assertEquals(2, ContentIdentityGuard.rejections(mismatched, e6).size)
        // Equal duplicate streams share one map key; AutoPlaySelection filters each by equality.
        val dup = named("Show.S02E01.1080p.mkv")
        assertEquals(setOf(dup), ContentIdentityGuard.rejections(listOf(dup, dup.copy()), e6).keys)
        assertEquals(1, ContentIdentityGuard.rejections(listOf(named("Dune.1984.2160p.mkv")), film("Dune", 2021)).size)
    }

    @Test
    fun `identity of a request`() {
        assertEquals(
            ContentIdentity(title = "Dune", year = 2021, season = null, episode = null, film = true),
            ContentIdentity.of("movie", "Dune", "2021", null, null, "tt1160419")
        )
        assertEquals(
            ContentIdentity(title = "Severance", year = 2022, season = 2, episode = 3, film = false),
            ContentIdentity.of("series", "Severance", "2022-", 2, 3, "tt11280740:2:3")
        )
        // Anime databases number episodes per entry: season and episode unknown (Spec 84 A9).
        for (videoId in listOf("kitsu:46474:4", "mal:52991:4", "anilist:154587:4", "anidb:17617:4", "MAL:52991:4")) {
            assertEquals(
                videoId,
                ContentIdentity(title = "Frieren", year = null, season = null, episode = null, film = false),
                ContentIdentity.of("series", "Frieren", null, 1, 4, videoId)
            )
        }
        // Only those databases: another prefixed id keeps its numbers.
        assertEquals(
            ContentIdentity(title = "Game of Thrones", year = null, season = 3, episode = 9, film = false),
            ContentIdentity.of("series", "Game of Thrones", null, 3, 9, "tmdb:1399:3:9")
        )
        assertEquals(null, ContentIdentity.of("movie", " ", "n/a", null, null, null).title)
    }

    @Test
    fun `date seasons are read as seasons`() {
        assertNull(eval("Show.S2024E01.1080p.WEB.mkv", episode("Show", 2024, 2024, 1)))
        assertEquals(Rejection.WRONG_EPISODE, eval("Show.S2024E02.1080p.WEB.mkv", episode("Show", 2024, 2024, 1)))
    }

    @Test
    fun `another title sharing the start is not checked (D6)`() {
        val names = listOf(
            "Daredevil.Born.Again.2026.S02E06.Requiem.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv",
            "Daredevil.S02E06.Regrets.Only.2160p.NF.WEB-DL.DDP5.1.HDR.HEVC-NTb.mkv",
            "Marvels.Daredevil.S02E06.1080p.WEB.x264-GRP.mkv",
            "Daredevil.2015.S02E06.1080p.WEB.x264-GRP.mkv"
        )
        assertTrue(ContentIdentityGuard.rejections(names.map(::named), daredevil).isEmpty())
        assertTrue(ContentIdentityGuard.rejections(listOf(named("Spartacus.Vengeance.S02E01.1080p.BluRay.mkv")), episode("Spartacus", 2010, 2, 1)).isEmpty())
    }

    @Test
    fun `a film year straight after the title and off by more than one is rejected`() {
        val dune = film("Dune", 2021)
        assertEquals(Rejection.WRONG_YEAR, eval("Dune.1984.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-HD.MA.5.1-GRP.mkv", dune))
        assertNull(eval("Dune.2021.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FraMeSToR.mkv", dune))
        assertNull(eval("Dune.2022.2160p.WEB-DL.mkv", dune))
        assertNull(eval("Dune.2020.2160p.WEB-DL.mkv", dune))
        assertEquals(Rejection.WRONG_YEAR, eval("Dune.2023.2160p.WEB-DL.mkv", dune))
    }

    @Test
    fun `years after more words, numbers in titles and packs pass`() {
        assertNull(eval("Dune.Part.Two.2024.2160p.WEB-DL.mkv", film("Dune", 2021)))
        assertNull(eval("Blade.Runner.The.Final.Cut.2007.2160p.UHD.BluRay.mkv", film("Blade Runner", 1982)))
        assertNull(eval("1917.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1.Atmos-GRP.mkv", film("1917", 2019)))
        assertNull(eval("1917.2160p.UHD.BluRay.x265-GRP.mkv", film("1917", 2019)))
        assertNull(eval("2001.A.Space.Odyssey.1968.2160p.UHD.BluRay.REMUX.HDR.HEVC-GRP.mkv", film("2001: A Space Odyssey", 1968)))
        assertNull(eval("Blade.Runner.2049.2017.2160p.UHD.BluRay.REMUX-GRP.mkv", film("Blade Runner 2049", 2017)))
        assertNull(eval("Wonder.Woman.1984.2020.1080p.WEB-DL.mkv", film("Wonder Woman 1984", 2020)))
        assertNull(eval("The.Thing.1982.Remastered.2016.2160p.BluRay.mkv", film("The Thing", 1982)))
        assertNull(eval("Harry.Potter.Complete.Collection.2001-2011.1080p.BluRay", film("Harry Potter and the Sorcerer's Stone", 2001)))
    }

    @Test
    fun `missing names, years and foreign titles pass (D2)`() {
        assertNull(eval(null, daredevil))
        assertNull(eval("   ", daredevil))
        assertNull(eval("La.Casa.de.Papel.S02E06.1080p.WEB.mkv", episode("Money Heist", 2017, 2, 6)))
        assertNull(eval("Dune.2160p.WEB-DL.mkv", film("Dune", 2021)))
        assertNull(eval("Dune.1984.2160p.mkv", ContentIdentity(title = "Dune", year = null, season = null, episode = null, film = true)))
        assertNull(eval("Dune.1984.2160p.mkv", ContentIdentity(title = "Dune", year = 2021, season = null, episode = null, film = false)))
        assertTrue(ContentIdentityGuard.rejections(listOf(named(null), named(" ")), daredevil).isEmpty())
        // An episode identity never applies the year check, whatever its year says.
        assertNull(eval("Doctor.Who.2005.S01E01.1080p.mkv", episode("Doctor Who", 1963, 1, 1)))
    }

    @Test
    fun `accents and apostrophes fold so the film title matches`() {
        assertEquals(Rejection.WRONG_YEAR, eval("Amelie.1990.1080p.BluRay.mkv", film("Amélie", 2001)))
        assertEquals(Rejection.WRONG_YEAR, eval("Schindlers.List.1960.1080p.mkv", film("Schindler's List", 1993)))
        assertNull(eval("Schindlers.List.1993.1080p.mkv", film("Schindler\u2019s List", 1993)))
    }

    @Test
    fun `rejections keeps every hard rejection with its reason`() {
        val severance = episode("Severance", 2022, 2, 3)
        val right = named("Severance.S02E03.1080p.mkv")
        val wrongEp = named("Severance.S02E04.1080p.mkv")
        val wrongSeason = named("Severance.S01E03.1080p.mkv")
        assertEquals(
            mapOf(wrongEp to Rejection.WRONG_EPISODE, wrongSeason to Rejection.WRONG_SEASON),
            ContentIdentityGuard.rejections(listOf(right, wrongEp, wrongSeason), severance)
        )
    }

    @Test
    fun `release name is the file or torrent name, never formatter text`() {
        fun resolve(filename: String?, torrentName: String?, rawFilename: String?, rawTorrent: String? = null) = StreamClientResolve(
            type = null, infoHash = null, fileIdx = null, sources = null, torrentName = torrentName,
            filename = filename, mediaType = null, title = null, season = null, episode = null, service = null,
            stream = StreamClientResolveStream(
                StreamClientResolveRaw(torrentName = rawTorrent, filename = rawFilename, size = null, network = null, parsed = null)
            )
        )
        assertEquals("hint.mkv", ContentIdentityGuard.releaseNameOf(named("hint.mkv").copy(clientResolve = resolve("cr.mkv", "t", "raw.mkv"))))
        assertEquals("cr.mkv", ContentIdentityGuard.releaseNameOf(named(null).copy(clientResolve = resolve("cr.mkv", "t", "raw.mkv"))))
        assertEquals("raw.mkv", ContentIdentityGuard.releaseNameOf(named(" ").copy(clientResolve = resolve(null, "t", "raw.mkv"))))
        assertEquals("t", ContentIdentityGuard.releaseNameOf(named(null).copy(clientResolve = resolve(null, "t", null))))
        assertEquals("rt", ContentIdentityGuard.releaseNameOf(named(null).copy(clientResolve = resolve(null, null, null, "rt"))))
        assertNull(ContentIdentityGuard.releaseNameOf(named(null).copy(title = "Daredevil.S02E07", description = "x")))
    }

    private fun episode(title: String, year: Int, season: Int, episode: Int) =
        ContentIdentity(title = title, year = year, season = season, episode = episode, film = false)

    private fun film(title: String, year: Int) =
        ContentIdentity(title = title, year = year, season = null, episode = null, film = true)

    private fun eval(name: String?, identity: ContentIdentity) = ContentIdentityGuard.evaluate(name, identity)

    private var urlCounter = 0

    private fun named(filename: String?) = Stream(
        name = "n", title = null, description = null, url = "https://x/${urlCounter++}.mkv", ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = filename
        ),
        addonName = "Addon", addonLogo = null
    )
}
