package com.nuvio.tv.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * TheIntroDB (https://theintrodb.org/docs), Spec 61 Q7: crowd-sourced skip segments for episodes
 * and movies, no key. A movie is asked without season and episode. 404 = the title or episode
 * is not in the database (checked 2026-11-10: `media_not_found` / `episode_not_found`).
 */
interface TheIntroDbApi {
    @GET("v4/media")
    suspend fun getMedia(
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null
    ): Response<TheIntroDbMediaResponse>
}

@Serializable
data class TheIntroDbMediaResponse(
    @SerialName("intro") val intro: List<TheIntroDbStamp>? = null,
    @SerialName("recap") val recap: List<TheIntroDbStamp>? = null,
    @SerialName("credits") val credits: List<TheIntroDbStamp>? = null,
    @SerialName("preview") val preview: List<TheIntroDbStamp>? = null
)

/** A null start is the start of the file; a null end is its end. */
@Serializable
data class TheIntroDbStamp(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null
)
