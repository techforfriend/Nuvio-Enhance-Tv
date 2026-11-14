package com.nuvio.tv.core.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Query pair [toSubtitleConfiguration] appends (last) to sidecar subtitle URIs. */
internal const val SUBTITLE_URI_MARKER = "nuvio_type"
private const val SUBTITLE_URI_MARKER_PAIR = "$SUBTITLE_URI_MARKER=subtitle"

/**
 * The URL to fetch a sidecar subtitle with no stream credentials, or null when [uri] is not one
 * of this media item's [subtitleUris] or the credentials may go to it
 * ([shouldSendStreamCredentials]). Membership, not the URL text, says "subtitle": an addon URL
 * can carry its own `nuvio_type`. Only the pair we appended is stripped.
 */
internal fun credentialFreeSubtitleUrl(
    uri: String,
    streamUrl: String,
    subtitleUris: Set<String>,
    registrableDomain: (String) -> String? = ::pslRegistrableDomain
): String? {
    if (uri !in subtitleUris) return null
    if (shouldSendStreamCredentials(uri, streamUrl, registrableDomain)) return null
    val url = uri.toHttpUrlOrNull() ?: return uri // fails closed: credential-free, unchanged
    val query = url.encodedQuery ?: return url.toString()
    val stripped = when {
        query == SUBTITLE_URI_MARKER_PAIR -> null
        query.endsWith("&$SUBTITLE_URI_MARKER_PAIR") -> query.dropLast(SUBTITLE_URI_MARKER_PAIR.length + 1)
        else -> query
    }
    return url.newBuilder().encodedQuery(stripped).build().toString()
}

/**
 * Media-reload sidecar subtitles load through the stream's data source, whose default headers
 * (and Authorization interceptor) carry the stream's credentials. This routes each of the item's
 * subtitle URIs on a foreign site to [credentialFree] instead (Spec 68 X1); everything else opens on
 * [upstream] exactly as before.
 */
@OptIn(UnstableApi::class)
internal class SubtitleCredentialScopeDataSourceFactory(
    private val upstream: DataSource.Factory,
    credentialFree: () -> DataSource.Factory,
    private val streamUrl: String,
    private val subtitleUris: Set<String>
) : DataSource.Factory {
    private val credentialFreeFactory by lazy(credentialFree)

    override fun createDataSource(): DataSource = Scoped()

    /** Each delegate is created once and reused across opens, like the plain upstream source. */
    private inner class Scoped : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var upstreamSource: DataSource? = null
        private var credentialFreeSource: DataSource? = null
        private var delegate: DataSource? = null

        private fun created(source: DataSource): DataSource =
            source.also { listeners.forEach(it::addTransferListener) }

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            upstreamSource?.addTransferListener(transferListener)
            credentialFreeSource?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri.toString()
            if (uri.endsWith(SUBTITLE_URI_MARKER_PAIR) && uri !in subtitleUris) {
                // Routing keys on exact URI equality with the SubtitleConfiguration; a marked
                // URI outside the set means the engine rewrote it and routing fails open.
                Log.w("SubtitleCredScope", "marked subtitle URI not in the item's set; credential scope bypassed")
            }
            val foreignUrl = credentialFreeSubtitleUrl(uri, streamUrl, subtitleUris)
            return if (foreignUrl != null) {
                val source = credentialFreeSource
                    ?: created(credentialFreeFactory.createDataSource()).also { credentialFreeSource = it }
                delegate = source
                source.open(dataSpec.withUri(Uri.parse(foreignUrl)))
            } else {
                val source = upstreamSource
                    ?: created(upstream.createDataSource()).also { upstreamSource = it }
                delegate = source
                source.open(dataSpec)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "read before open" }.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            val source = delegate ?: return
            delegate = null
            source.close()
        }
    }
}
