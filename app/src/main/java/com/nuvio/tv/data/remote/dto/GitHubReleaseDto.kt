package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The fields of GitHub's release object the update check and the changelog read (REST API 2022-11-28). */
@Serializable
data class GitHubReleaseDto(
    @SerialName("name") val name: String? = null,
    @SerialName("tag_name") val tagName: String? = null,
    /** The list endpoint includes pre-releases (and drafts, for a token with push access); the changelog skips both. */
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("body") val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GitHubAssetDto> = emptyList()
)

/** [digest] = "sha256:<64 hex>", set on every asset GitHub stores since June 2025. */
@Serializable
data class GitHubAssetDto(
    @SerialName("name") val name: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("digest") val digest: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null
)
