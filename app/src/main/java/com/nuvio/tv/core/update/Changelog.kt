package com.nuvio.tv.core.update

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The changelog lists this many releases (Spec 80 D4). */
internal const val CHANGELOG_SIZE = 20

/** A release video is at most this big; Nuvio's player streams it, nothing is stored. */
internal const val MAX_VIDEO_BYTES = 1024L * 1024 * 1024

/** A release poster is at most this big (Coil decodes it for the video tile). */
internal const val MAX_POSTER_BYTES = 5L * 1024 * 1024

private val POSTER_NAME = Regex("""^[^/\\]+\.(png|jpe?g|webp)$""", RegexOption.IGNORE_CASE)

private val VIDEO_NAME = Regex("""^[^/\\]+\.(mp4|mkv|webm)$""", RegexOption.IGNORE_CASE)

/**
 * A Markdown ATX heading line: group 1 its `#`s (the level), group 2 its text. Setext headings
 * (text underlined with `===`) are not read: release bodies use `#` headings. One home for both the
 * What's new split and the plain-text notes (releaseNotesText).
 */
internal val MARKDOWN_HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)(\s+#+)?\s*$""")

/** A code fence opening or closing line: inside one, a `#` line is code, not a heading. */
private val FENCE = Regex("""^\s{0,3}(```|~~~)""")

/** "What's new", "What’s new in 1.0.0", "WHATS NEW": the section D3 puts first in every release body. */
private val WHATS_NEW_TITLE = Regex("""^what['’]?s\s+new\b.*$""", RegexOption.IGNORE_CASE)

/**
 * The release's video (Spec 80 D2): an uploaded `.mp4`, `.mkv` or `.webm` asset GitHub calls video,
 * served over https, played by URL in Nuvio's own player.
 */
data class ReleaseVideo(
    val url: String,
    val sizeBytes: Long,
    /** An uploaded png / jpeg / webp image asset of the same release, shown on the video tile; null = a plain tile. */
    val posterUrl: String? = null
)

/**
 * One changelog entry (Spec 80 D2-D4). [versionName] / [versionCode] come from the release's APK
 * names (`NuvioKE-<name>-<code>-<abi>.apk`), so a release without one has neither; [title] is then
 * GitHub's name for it. [whatsNew] is the body's leading What's new section (empty when the body has
 * none, as 0.9.0-ke52's), [notes] the rest; both are the body's Markdown, untrusted and shown only as
 * plain text.
 */
data class ChangelogRelease(
    val title: String,
    val versionName: String?,
    val versionCode: Int?,
    /** ISO 8601, as GitHub sends it. */
    val publishedAt: String?,
    val whatsNew: String,
    val notes: String,
    val video: ReleaseVideo?
)

/** The body split at its leading What's new section; [whatsNew] is the section's lines without its heading. */
internal data class ReleaseBodyParts(val whatsNew: String, val notes: String)

/**
 * D3: when the first non-blank line of [body] is a "What's new" heading, its section (up to the next
 * heading of the same or a higher level) is [ReleaseBodyParts.whatsNew] and the rest the notes;
 * otherwise the whole body is the notes.
 */
internal fun splitReleaseBody(body: String): ReleaseBodyParts {
    // GitHub's web editor saves CRLF.
    val lines = body.replace("\r\n", "\n").lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    val heading = lines.getOrNull(first)?.let { MARKDOWN_HEADING.matchEntire(it) }
    if (heading == null || !WHATS_NEW_TITLE.matches(heading.groupValues[2].trim())) {
        return ReleaseBodyParts(whatsNew = "", notes = lines.joinToString("\n").trim())
    }
    val level = heading.groupValues[1].length
    var inFence = false
    val end = (first + 1 until lines.size).firstOrNull { index ->
        val line = lines[index]
        if (FENCE.containsMatchIn(line)) inFence = !inFence
        !inFence && MARKDOWN_HEADING.matchEntire(line)?.let { it.groupValues[1].length <= level } == true
    } ?: lines.size
    return ReleaseBodyParts(
        whatsNew = lines.subList(first + 1, end).joinToString("\n").trim(),
        notes = lines.subList(end, lines.size).joinToString("\n").trim()
    )
}

/** [release] as a changelog entry; null for a draft or a pre-release (the update check never offers those). */
internal fun changelogRelease(release: GitHubReleaseDto): ChangelogRelease? {
    if (release.draft || release.prerelease) return null
    val apk = release.assets.firstNotNullOfOrNull { asset -> asset.name?.let { ASSET_NAME.matchEntire(it) } }
    val title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName?.takeIf { it.isNotBlank() }
    val versionName = apk?.groupValues?.get(1)
    if (versionName == null && title == null) return null
    val parts = splitReleaseBody(release.body.orEmpty().take(MAX_NOTES_CHARS))
    return ChangelogRelease(
        title = title ?: versionName.orEmpty(),
        versionName = versionName,
        versionCode = apk?.groupValues?.get(2)?.toIntOrNull(),
        publishedAt = release.publishedAt,
        whatsNew = parts.whatsNew,
        notes = parts.notes,
        video = releaseVideoOf(release)
    )
}

/** [release]'s video with its poster, if it has one (Spec 80 D2). */
internal fun releaseVideoOf(release: GitHubReleaseDto): ReleaseVideo? =
    release.assets.firstNotNullOfOrNull(::releaseVideo)
        ?.copy(posterUrl = release.assets.firstNotNullOfOrNull(::releasePoster))

/**
 * The update's release as a changelog entry (Spec 80 B1: the update panel opens its page), from
 * what the check stored, so it needs no second request.
 */
fun KnownRelease.toChangelogRelease(): ChangelogRelease {
    val parts = splitReleaseBody(notes)
    return ChangelogRelease(
        title = versionName,
        versionName = versionName,
        versionCode = versionCode,
        publishedAt = publishedAt,
        whatsNew = parts.whatsNew,
        notes = parts.notes,
        video = videoUrl?.let { ReleaseVideo(it, videoSizeBytes, posterUrl) }
    )
}

internal fun releasePoster(asset: GitHubAssetDto): String? {
    if (asset.state != "uploaded") return null
    if (asset.name?.let(POSTER_NAME::matches) != true) return null
    if (asset.contentType?.startsWith("image/", ignoreCase = true) != true) return null
    if (asset.size?.let { it in 1..MAX_POSTER_BYTES } != true) return null
    return asset.browserDownloadUrl?.takeIf { it.toHttpUrlOrNull()?.isHttps == true }
}

internal fun releaseVideo(asset: GitHubAssetDto): ReleaseVideo? {
    if (asset.state != "uploaded") return null
    if (asset.name?.let(VIDEO_NAME::matches) != true) return null
    if (asset.contentType?.startsWith("video/", ignoreCase = true) != true) return null
    val size = asset.size?.takeIf { it in 1..MAX_VIDEO_BYTES } ?: return null
    val url = asset.browserDownloadUrl?.takeIf { it.toHttpUrlOrNull()?.isHttps == true } ?: return null
    return ReleaseVideo(url, size)
}

/** One changelog load: the entries newest first, or nothing usable (no network, GitHub down, rate limit). */
sealed interface ChangelogLoad {
    data class Loaded(val releases: List<ChangelogRelease>) : ChangelogLoad
    data object Failed : ChangelogLoad
}

/**
 * The public releases for the changelog (Spec 80 D4): fetched each time it is asked, never stored.
 * Every build reads them, with or without the update check: reading installs nothing.
 */
@Singleton
class ChangelogRepository internal constructor(
    private val api: GitHubReleaseApi,
    private val owner: String,
    private val repo: String
) {
    @Inject constructor(api: GitHubReleaseApi) : this(api, BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO)

    suspend fun load(): ChangelogLoad = try {
        val response = api.releases(owner, repo, CHANGELOG_SIZE)
        val body = response.body()
        when {
            response.isSuccessful && body != null -> ChangelogLoad.Loaded(body.mapNotNull(::changelogRelease))
            // 404: no release yet (or no such repo): an empty changelog, not an error.
            response.code() == 404 -> ChangelogLoad.Loaded(emptyList())
            else -> ChangelogLoad.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        ChangelogLoad.Failed
    } catch (e: Exception) {
        // An answer that did not decode (SerializationException) or a converter failure.
        Log.w(TAG, "changelog answer unusable: ${e.javaClass.simpleName}")
        ChangelogLoad.Failed
    }

    private companion object {
        const val TAG = "Changelog"
    }
}
