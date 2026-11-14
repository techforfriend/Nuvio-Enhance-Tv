package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/** The public repo's releases, for the update check (Spec 52 P6) and the changelog (Spec 80). Unauthenticated: 60 requests/h/IP, shared. */
interface GitHubReleaseApi {

    /**
     * The newest published release (drafts and pre-releases excluded by GitHub); 404 when none.
     * no-cache: the validated client's HTTP cache revalidates by ETag (GitHub sends max-age=60), so a
     * check is never answered from disk, and a 304 does not count against the rate limit.
     */
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28",
        "Cache-Control: no-cache"
    )
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubReleaseDto>

    /** The newest [perPage] releases, newest first (Spec 80 D4: the changelog); pre-releases included. */
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28",
        "Cache-Control: no-cache"
    )
    @GET("repos/{owner}/{repo}/releases")
    suspend fun releases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int
    ): Response<List<GitHubReleaseDto>>
}
