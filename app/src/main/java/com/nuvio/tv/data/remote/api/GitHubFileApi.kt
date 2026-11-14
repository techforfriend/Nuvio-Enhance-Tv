package com.nuvio.tv.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * One file of the public repo's default branch, raw (Spec 81 T1: `tip.json`). Same host, client and
 * unauthenticated budget (60 requests/h/IP, shared) as [GitHubReleaseApi].
 */
interface GitHubFileApi {

    /**
     * The raw bytes of [path]; 404 when there is no such file. Streaming, so the caller reads only as
     * much as it caps (`tip.json`: 4 KB) and must close the body. no-cache: always fetched from the
     * network, never answered from the HTTP cache.
     */
    @Headers(
        "Accept: application/vnd.github.raw+json",
        "X-GitHub-Api-Version: 2022-11-28",
        "Cache-Control: no-cache"
    )
    @Streaming
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun rawFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String
    ): Response<ResponseBody>
}
