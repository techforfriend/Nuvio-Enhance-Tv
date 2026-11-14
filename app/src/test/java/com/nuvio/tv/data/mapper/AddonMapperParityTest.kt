package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.local.decodeManifestCache
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.domain.model.Addon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moshi retirement M4 dispositions: parity pins for parseResources /
 * parseCatalogExtras — the addon-manifest rewrites that foreign manifests flow
 * through on every load. Driven through the public toDomain() path.
 */
class AddonMapperParityTest {

    private val json = NetworkModule.provideWireJson()

    private fun manifest(resources: String, catalogs: String): Addon =
        json.decodeFromString<AddonManifestDto>(
            """{"id":"a","name":"A","version":"1","types":["movie","series"],
                "resources":$resources,"catalogs":$catalogs}"""
        ).toDomain(baseUrl = "https://addon.example")

    @Test
    fun `resources accept string and complex forms`() {
        val addon = manifest(
            resources = """["meta",{"name":"stream","types":["movie"],"idPrefixes":["tt"]},
                            {"name":"sub", "types": "not-a-list", "idPrefixes": [7]}]""",
            catalogs = """[{"type":"movie","id":"c","name":"C"}]"""
        )
        assertEquals(3, addon.resources.size)
        assertEquals("meta", addon.resources[0].name)
        assertEquals(listOf("movie", "series"), addon.resources[0].types) // defaultTypes fallback
        assertEquals(listOf("movie"), addon.resources[1].types)
        assertEquals(listOf("tt"), addon.resources[1].idPrefixes)
        // Complex form with non-list types keeps the manifest default; non-string
        // idPrefixes contribute nothing (Moshi's filterIsInstance<String>).
        assertEquals(listOf("movie", "series"), addon.resources[2].types)
        assertEquals(emptyList<String>(), addon.resources[2].idPrefixes)
    }

    @Test
    fun `empty-but-present types array stays empty - absent falls back`() {
        val addon = manifest(
            resources = """[{"name":"stream","types":[]},{"name":"catalog"}]""",
            catalogs = """[{"type":"movie","id":"c","name":"C"}]"""
        )
        assertEquals(emptyList<String>(), addon.resources[0].types)
        assertEquals(listOf("movie", "series"), addon.resources[1].types)
    }

    @Test
    fun `catalog extras cover the lenient isRequired and optionsLimit branches`() {
        val addon = manifest(
            resources = "[]",
            catalogs = """[{"type":"movie","id":"c","name":"C","extra":[
                "genre",
                {"name":"search","isRequired":true},
                {"name":"skip","isRequired":false},
                {"name":"num1","isRequired":1},
                {"name":"num0","isRequired":0},
                {"name":"str","isRequired":"TRUE"},
                {"name":"strNo","isRequired":"yes"},
                {"name":"nullReq","isRequired":null},
                {"name":"lim5","optionsLimit":5},
                {"name":"limStr","optionsLimit":"5"},
                {"name":"lim57","optionsLimit":5.7},
                {"name":"limNull","optionsLimit":null},
                {"name":"bad"},{"name":"  "}
            ]}]"""
        )
        val extras = addon.catalogs[0].extra
        assertEquals(13, extras.size) // "bad" has a name and stays; only the blank string drops
        assertEquals("genre", extras[0].name)
        assertTrue(extras[1].isRequired)
        assertEquals(false, extras[2].isRequired)
        assertTrue(extras[3].isRequired)      // number 1 -> non-zero
        assertEquals(false, extras[4].isRequired)
        assertTrue(extras[5].isRequired)      // "TRUE" case-insensitive
        assertEquals(false, extras[6].isRequired) // "yes" is not "true"
        assertEquals(false, extras[7].isRequired) // explicit null
        assertEquals(5, extras[8].optionsLimit)
        assertEquals(5, extras[9].optionsLimit)
        assertEquals(5, extras[10].optionsLimit)  // 5.7 truncates like Moshi toInt()
        assertNull(extras[11].optionsLimit)        // explicit null
        assertEquals("bad", extras[12].name)      // name-present object survives
    }

    @Test
    fun `options list skips nulls and keeps strings`() {
        val addon = manifest(
            resources = "[]",
            catalogs = """[{"type":"movie","id":"c","name":"C","extra":[
                {"name":"skip","options":[null,"50",100,{"x":1}],"optionsLimit":2}
            ]}]"""
        )
        val extra = addon.catalogs[0].extra[0]
        // Non-string options stringify as JSON text — numbers now render as
        // "50"/"100" (Moshi rendered doubles as "50.0"): recorded favorable delta,
        // toIntOrNull() in skipStep actually parses these now.
        assertEquals(listOf("50", "100", "{\"x\":1}"), extra.options)
    }

    @Test
    fun `duplicate extras are deduplicated`() {
        val addon = manifest(
            resources = "[]",
            catalogs = """[{"type":"movie","id":"c","name":"C","extra":["genre","genre",
                          {"name":"search"},{"name":"search"}]}]"""
        )
        assertEquals(listOf("genre", "search"), addon.catalogs[0].extra.map { it.name })
    }

    private fun hints(behaviorHints: String) =
        json.decodeFromString<AddonManifestDto>(
            """{"id":"a","name":"A","version":"1","behaviorHints":$behaviorHints}"""
        ).toDomain(baseUrl = "https://addon.example").behaviorHints!!

    @Test
    fun `behaviorHints flags read leniently and never drop the manifest`() {
        assertEquals(true, hints("""{"p2p":true,"adult":"true"}""").p2p)
        assertEquals(true, hints("""{"adult":"TRUE"}""").adult)
        assertEquals(true, hints("""{"p2p":1}""").p2p)
        assertEquals(false, hints("""{"p2p":0}""").p2p)
        assertEquals(false, hints("""{"adult":"false"}""").adult)
        assertNull(hints("""{"p2p":"yes","adult":{"x":1}}""").p2p)
        assertNull(hints("""{"adult":[true]}""").adult)
        assertNull(hints("""{"p2p":null}""").p2p)
        assertNull(hints("{}").adult)
        val quoted = hints("""{"configurable":"true","configurationRequired":1,"newEpisodeNotifications":false}""")
        assertEquals(true, quoted.configurable)
        assertEquals(true, quoted.configurationRequired)
        assertEquals(false, quoted.newEpisodeNotifications)
    }

    @Test
    fun `a catalog without a name is labelled with its addon's name`() {
        // Spec 84 A2: Stremio's YouTube addon (v3-channels.strem.io) names neither catalog.
        val addon = json.decodeFromString<AddonManifestDto>(
            """{"id":"com.linvo.stremiochannels","version":"1.30.7","name":"YouTube",
                "resources":["catalog","meta"],"types":["channel"],"idPrefixes":["yt_id:"],
                "catalogs":[{"type":"channel","id":"top"},{"type":"channel","id":"videos","name":"Videos"}]}"""
        ).toDomain(baseUrl = "https://v3-channels.strem.io")
        assertEquals(listOf("YouTube", "Videos"), addon.catalogs.map { it.name })
        assertEquals(listOf("top", "videos"), addon.catalogs.map { it.id })
    }

    @Test
    fun `manifest cache written before p2p and adult still decodes`() {
        val cached = """{"https://a.example":{"id":"a","name":"A","version":"1","description":null,"logo":null,
            "baseUrl":"https://a.example","catalogs":[],"types":[],"resources":[],
            "behaviorHints":{"configurable":true}}}"""
        val addon = decodeManifestCache(cached)!!.getValue("https://a.example")
        assertEquals(true, addon.behaviorHints?.configurable)
        assertNull(addon.behaviorHints?.p2p)
        assertNull(addon.behaviorHints?.adult)
    }
}
