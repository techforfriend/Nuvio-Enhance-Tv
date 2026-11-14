package com.nuvio.tv.data.remote.api

import com.nuvio.tv.core.di.UntrustedBodyCap
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.data.remote.dto.SubtitleResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Tag
import retrofit2.http.Url

interface AddonApi {

    @GET
    suspend fun getManifest(@Url manifestUrl: String): Response<AddonManifestDto>

    @GET
    suspend fun getCatalog(@Url catalogUrl: String): Response<CatalogResponseDto>

    /** [bodyCap] raises the untrusted-response guard's 2 MB cap for meta only (Spec 82). */
    @GET
    suspend fun getMeta(
        @Url metaUrl: String,
        @Tag bodyCap: UntrustedBodyCap = UntrustedBodyCap.META
    ): Response<MetaResponseDto>

    /**
     * [userAgent] null sends the app's own UA (Retrofit omits a null @Header). [bodyCap]
     * raises the 2 MB cap for stream lists (Spec 84 A5).
     */
    @GET
    suspend fun getStreams(
        @Url streamUrl: String,
        @Header("User-Agent") userAgent: String?,
        @Tag bodyCap: UntrustedBodyCap = UntrustedBodyCap.STREAMS
    ): Response<StreamResponseDto>

    @GET
    suspend fun getSubtitles(@Url subtitleUrl: String): Response<SubtitleResponseDto>
}
