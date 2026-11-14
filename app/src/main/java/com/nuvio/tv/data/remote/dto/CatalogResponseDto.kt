package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class CatalogResponseDto(
    @SerialName("metas") val metas: List<MetaPreviewDto?> = emptyList()
)

@Serializable
data class MetaPreviewDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("poster") val poster: String? = null,
    @SerialName("posterShape") val posterShape: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("genres") val genres: List<String>? = null,
    @SerialName("runtime") val runtime: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("released") val released: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("landscapePoster") val landscapePoster: String? = null,
    @SerialName("_rawPosterUrl") val rawPosterUrl: String? = null,
    @SerialName("director") val director: JsonElement? = null,
    @SerialName("writer") val writer: JsonElement? = null,
    @SerialName("writers") val writers: JsonElement? = null,
    @SerialName("links") val links: List<MetaLinkDto>? = null,
    @SerialName("trailers") val trailers: List<MetaTrailerDto>? = null,
    @SerialName("behaviorHints") val behaviorHints: MetaBehaviorHintsDto? = null,
    @SerialName("trailerStreams") val trailerStreams: List<TrailerStreamDto>? = null,
    @SerialName("app_extras")
    @Serializable(with = CatalogAppExtrasSerializer::class)
    val appExtras: CatalogAppExtrasDto? = null
)

/**
 * The one `app_extras` field a catalog item needs (Spec 64 K1a). Deliberately not [AppExtrasDto]:
 * catalog items also carry the full cast/directors/writers lists (K0: ~2 MB across the household
 * Home pool vs 39 KB for certification alone), which would be decoded for every item and
 * re-encoded into the catalog disk cache. Unknown keys are dropped by the wire Json.
 */
@Serializable
data class CatalogAppExtrasDto(
    @SerialName("certification") val certification: String? = null
)

/**
 * Untrusted addon input: keeps only a string `certification` and turns any other shape (a
 * non-object app_extras, a numeric or array certification) into an empty object, so one odd item
 * never fails the whole catalog decode. app_extras used to be an ignored unknown key.
 */
internal object CatalogAppExtrasSerializer :
    JsonTransformingSerializer<CatalogAppExtrasDto>(CatalogAppExtrasDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val certification = ((element as? JsonObject)?.get("certification") as? JsonPrimitive)
            ?.takeIf { it.isString }
        return if (certification == null) JsonObject(emptyMap()) else JsonObject(mapOf("certification" to certification))
    }
}
