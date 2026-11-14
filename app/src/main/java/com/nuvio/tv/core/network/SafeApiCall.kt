package com.nuvio.tv.core.network

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.tls.isAddonCertificateFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response

/**
 * Default deadline for addon-facing JSON requests (manifests, the stream screen's
 * inline-meta fallback). Stream lists (25 s, StreamRepositoryImpl) and meta
 * lookups (28 s, MetaRepositoryImpl) set their own. A Stremio JSON endpoint should
 * answer in low single-digit seconds; 20 s is generous headroom.
 */
const val ADDON_REQUEST_TIMEOUT_MS = 20_000L

/** [safeApiCall] with a hard per-request deadline, for addon-facing endpoints. */
suspend fun <T> safeAddonApiCall(
    context: Context,
    timeoutMs: Long = ADDON_REQUEST_TIMEOUT_MS,
    apiCall: suspend () -> Response<T>
): NetworkResult<T> {
    return withTimeoutOrNull(timeoutMs) { safeApiCall(context, apiCall) }
        ?: NetworkResult.Error(context.getString(R.string.network_error_addon_timeout))
}

suspend fun <T> safeApiCall(
    context: Context,
    apiCall: suspend () -> Response<T>
): NetworkResult<T> {
    return try {
        val response = apiCall()
        if (response.isSuccessful) {
            response.body()?.let {
                NetworkResult.Success(it)
            } ?: NetworkResult.Error(context.getString(R.string.network_error_empty_response_body))
        } else {
            NetworkResult.Error(response.message(), response.code())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isDecodeGarbage()) {
            // W2.3: decode failures embed response snippets in their messages
            // (kotlinx prints the JSON path + input) — classify as garbage and
            // never carry the raw message into logs or user-visible errors.
            NetworkResult.Error(
                context.getString(R.string.network_error_malformed_json),
                garbage = true
            )
        } else if (e.isAddonCertificateFailure()) {
            NetworkResult.Error(context.getString(R.string.network_error_addon_certificate), certificate = true)
        } else {
            NetworkResult.Error(e.message ?: context.getString(R.string.network_error_unknown))
        }
    }
}

/**
 * W2.3: true when the exception means the untrusted host served garbage
 * (wrong-shape or malformed JSON) rather than a transport failure. Garbage
 * is not retryable and should mark the source bad; IO is transient.
 */
fun Throwable.isDecodeGarbage(): Boolean =
    this is kotlinx.serialization.SerializationException

/**
 * W2.3: a message safe to log and surface for a failed fetch. Decode
 * failures embed remote-input snippets until serialization 1.11.0 — replace
 * them with a fixed string; everything else keeps its own message (or class
 * name when null).
 */
fun Throwable.sanitizedFetchMessage(): String = when {
    isDecodeGarbage() -> "Malformed response from source"
    else -> message ?: javaClass.simpleName
}
