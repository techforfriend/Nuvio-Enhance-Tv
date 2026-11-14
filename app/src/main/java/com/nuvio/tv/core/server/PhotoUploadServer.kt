package com.nuvio.tv.core.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nuvio.tv.data.local.MyPhotoKind
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** A validated upload waiting for the TV's OK (Spec 44 D4); [jpeg] is the re-encoded file to store. */
class PendingPhoto(val id: String, val kind: MyPhotoKind, val jpeg: ByteArray)

/** An uploaded image's header facts. */
data class PhotoBounds(val mimeType: String?, val width: Int, val height: Int)

/** The image decoder seam: BitmapFactory on the TV ([AndroidPhotoCodec]); unit tests fake it (no Robolectric). */
interface PhotoCodec {
    /** The header only (no pixels); null when [bytes] is not an image the platform reads. */
    fun bounds(bytes: ByteArray): PhotoBounds?

    /**
     * Full decode and a fresh JPEG q90 (no EXIF/GPS, nothing after the image); null when the decode
     * fails or the pixels are not [width] x [height].
     */
    fun reencodeJpeg(bytes: ByteArray, width: Int, height: Int): ByteArray?
}

object AndroidPhotoCodec : PhotoCodec {
    override fun bounds(bytes: ByteArray): PhotoBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return PhotoBounds(options.outMimeType, options.outWidth, options.outHeight)
    }

    override fun reencodeJpeg(bytes: ByteArray, width: Int, height: Int): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            if (bitmap.width != width || bitmap.height != height) return null
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }

    private const val JPEG_QUALITY = 90
}

/**
 * The LAN upload page behind the editor's QR card (Spec 44 Design "Upload server"). One instance
 * is one session: its own 128-bit [token] (every route lives under `/u/<token>/`) and a single
 * pending slot, so at most one photo waits for the TV's OK.
 *
 * Routes: `GET /u/<t>/` the phone page ([pageHtml]); `POST /u/<t>/photo?kind=avatar|background`
 * a raw `image/jpeg` body. The POST answers in the Design's order: guard, token, kind (400; a
 * cheap check added between the token and the gallery, it claims nothing), gallery full
 * (409 [FULL]), claim the slot (409 [BUSY]), Content-Length (411 / 413), read, validate (exact
 * D6 size, JPEG, re-encoded by [codec]), then [onPending] with the session, so a caller can drop
 * a result from a session it has stopped. Error bodies are generic words; the page maps them to
 * its own text.
 */
class PhotoUploadServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val isGalleryFull: () -> Boolean,
    private val codec: PhotoCodec,
    private val onPending: (PhotoUploadServer, PendingPhoto) -> Unit,
    hostname: String?,
    port: Int
) : NanoHTTPD(hostname, port) {

    private sealed interface Slot {
        data object Free : Slot
        data object Receiving : Slot
        class Waiting(val photo: PendingPhoto) : Slot
    }

    private val slot = AtomicReference<Slot>(Slot.Free)

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    /** Frees the slot held by pending [id] (confirmed or discarded), so the phone can send the next photo. */
    fun release(id: String) {
        val current = slot.get()
        if (current is Slot.Waiting && current.photo.id == id) slot.compareAndSet(current, Slot.Free)
    }

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            // A POST from a page whose card has closed or reopened: drained, so its browser reads "expired".
            ?: return if (session.method == Method.POST) session.earlyAnswer(Response.Status.NOT_FOUND, NOT_FOUND) else notFoundResponse()
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.POST && path == "photo" -> receive(session)
            else -> notFoundResponse()
        }
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", PAGE_CSP)
        }

    private fun receive(session: IHTTPSession): Response {
        val requested = session.parameters["kind"]?.singleOrNull()
        val kind = MyPhotoKind.entries.firstOrNull { it.prefix == requested }
            ?: return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        if (isGalleryFull()) return session.earlyAnswer(Response.Status.CONFLICT, FULL)
        if (!slot.compareAndSet(Slot.Free, Slot.Receiving)) return session.earlyAnswer(Response.Status.CONFLICT, BUSY)
        var published = false
        try {
            val bytes = when (val body = session.readCappedBytes(MAX_PHOTO_BYTES)) {
                is CappedBody.Bytes -> body.bytes
                CappedBody.NoLength -> return plain(Response.Status.LENGTH_REQUIRED, BAD_REQUEST)
                CappedBody.TooLarge -> return plain(Response.Status.PAYLOAD_TOO_LARGE, BAD_REQUEST)
                CappedBody.Short -> return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
            }
            val jpeg = validated(kind, bytes) ?: return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
            val photo = PendingPhoto(UUID.randomUUID().toString(), kind, jpeg)
            // This request holds Receiving; nothing else moves the slot out of it.
            slot.set(Slot.Waiting(photo))
            published = true
            onPending(this, photo)
            return plain(Response.Status.OK, SENT)
        } catch (_: IOException) {
            return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        } finally {
            if (!published) slot.compareAndSet(Slot.Receiving, Slot.Free)
        }
    }

    /** Exactly the D6 size and a JPEG by its header, then re-encoded (the stored file is never the uploaded bytes). */
    private fun validated(kind: MyPhotoKind, bytes: ByteArray): ByteArray? {
        val bounds = codec.bounds(bytes) ?: return null
        if (bounds.mimeType != JPEG_MIME || bounds.width != kind.width || bounds.height != kind.height) return null
        return codec.reencodeJpeg(bytes, kind.width, kind.height)
    }

    /** An answer before the body is read (404, 409), after draining a body within the cap. */
    private fun IHTTPSession.earlyAnswer(status: Response.Status, body: String): Response {
        drainDeclaredBody(MAX_PHOTO_BYTES)
        return plain(status, body)
    }

    private fun plain(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        /** First port tried: clear of AddonConfigServer (8080-8089), TorrServer (8091) and StreamBadgeConfigServer (8092-8101). */
        const val DEFAULT_PORT = 8110

        /** POST order step 5: a D6 JPEG from the phone page is well under this. */
        const val MAX_PHOTO_BYTES = 3 * 1024 * 1024

        /** 409 bodies, mapped to the page's own text. */
        const val FULL = "full"
        const val BUSY = "busy"
        const val SENT = "sent"
        private const val BAD_REQUEST = "bad request"
        private const val NOT_FOUND = "Not found"

        private const val PATH_PREFIX = "/u/"
        private const val JPEG_MIME = "image/jpeg"

        /** The page is inline only; it reads the chosen photo through a blob: URL and posts back to itself. */
        private const val PAGE_CSP =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob: data:; " +
                "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        fun startOnAvailablePort(
            pageHtml: () -> String,
            isGalleryFull: () -> Boolean,
            onPending: (PhotoUploadServer, PendingPhoto) -> Unit,
            codec: PhotoCodec = AndroidPhotoCodec,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10
        ): PhotoUploadServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            PhotoUploadServer(newSessionToken(), pageHtml, isGalleryFull, codec, onPending, hostname = null, port = port)
        }
    }
}
