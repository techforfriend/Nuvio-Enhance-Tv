package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.CatalogAppExtrasDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 64 K1a: a catalog item's app_extras.certification becomes MetaPreview.ageRating. */
class CatalogAgeRatingMapperTest {

    private val json = NetworkModule.provideWireJson()

    private fun decode(payload: String) =
        json.decodeFromString<CatalogResponseDto>(payload).metas.filterNotNull().map { it.toDomainOrNull("movie")!! }

    @Test
    fun `certification is mapped trimmed, and the rest of app_extras is ignored`() {
        val items = decode(
            """
            {"metas":[
              {"id":"tt1","type":"movie","name":"Rated","app_extras":{
                "certification":" PG ","certifications":{"GB":"U","US":"PG"},"certificationLocal":"PG",
                "cast":[{"name":"Actor A","tmdbId":1}],"seasonPosters":[]}},
              {"id":"tt2","type":"movie","name":"Blank","app_extras":{"certification":"  "}},
              {"id":"tt3","type":"movie","name":"No extras"},
              {"id":"tt4","type":"movie","name":"Unrated","app_extras":{"certification":"NR"}}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf("PG", null, null, "NR"), items.map { it.ageRating })
    }

    @Test
    fun `the disk-cache round-trip keeps the certification and nothing else of app_extras`() {
        // As CatalogRepositoryImpl does: decode the addon response, re-encode it for the disk cache.
        val fromAddon = json.decodeFromString<CatalogResponseDto>(
            """{"metas":[{"id":"tt1","type":"movie","name":"Rated","app_extras":{
              "certification":"TV-PG","certifications":{"US":"TV-PG"},"cast":[{"name":"Actor A"}]}}]}"""
        )
        val encoded = json.encodeToString(fromAddon)

        assertEquals("TV-PG", json.decodeFromString<CatalogResponseDto>(encoded).metas.single()!!.toDomainOrNull("movie")!!.ageRating)
        assertEquals(true, encoded.contains(""""app_extras":{"certification":"TV-PG"}"""))
        assertEquals(false, encoded.contains("cast"))
        assertEquals(false, encoded.contains("certifications"))
    }

    @Test
    fun `an odd app_extras or certification shape costs that rating, never the catalog`() {
        val items = decode(
            """
            {"metas":[
              {"id":"tt1","type":"movie","name":"Numeric","app_extras":{"certification":12}},
              {"id":"tt2","type":"movie","name":"Array","app_extras":{"certification":["PG"]}},
              {"id":"tt3","type":"movie","name":"Object","app_extras":{"certification":{"US":"PG"}}},
              {"id":"tt4","type":"movie","name":"Not an object","app_extras":"PG"},
              {"id":"tt5","type":"movie","name":"Null","app_extras":{"certification":null}},
              {"id":"tt6","type":"movie","name":"Fine","app_extras":{"certification":"G"}}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf(null, null, null, null, null, "G"), items.map { it.ageRating })
    }

    @Test
    fun `a Kotlin-built dto encodes only the certification`() {
        val encoded = json.encodeToString(
            MetaPreviewDto(id = "tt1", type = "movie", name = "Rated", appExtras = CatalogAppExtrasDto("PG"))
        )

        assertEquals(true, encoded.contains(""""app_extras":{"certification":"PG"}"""))
    }

    @Test
    fun `an item without app_extras encodes without the key`() {
        val encoded = json.encodeToString(MetaPreviewDto(id = "tt1", type = "movie", name = "Plain"))

        assertEquals(false, encoded.contains("app_extras"))
        assertNull(json.decodeFromString<MetaPreviewDto>(encoded).toDomainOrNull("movie")!!.ageRating)
    }
}
