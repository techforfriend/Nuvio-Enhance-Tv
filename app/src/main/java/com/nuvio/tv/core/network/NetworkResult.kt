package com.nuvio.tv.core.network

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    /**
     * [garbage] = the untrusted host served wrong-shape/malformed JSON (W2.3): not retryable.
     * [certificate] = the addon server's certificate was refused, or the guard skipped a host whose
     * last failure was one (Spec 68 P6); the message is fixed.
     */
    data class Error(
        val message: String,
        val code: Int? = null,
        val garbage: Boolean = false,
        val certificate: Boolean = false
    ) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()

    companion object {
        /**
         * Sentinel error code used by MetaRepository to signal that the requested
         * metadata is already available from the catalog source addon, so no
         * additional network request was made. Callers should treat the existing
         * catalog data as sufficient and mark the item as resolved.
         */
        const val SOURCE_SUFFICIENT_CODE = -9999

        /**
         * T9 (0.9.0 relay): sentinel error code used by MetaRepository when every
         * addon answered and none of them carries the requested item. The lookup did
         * not fail, so callers should treat the result as final and not retry it; a
         * transport failure returns a plain Error instead.
         */
        const val META_NOT_FOUND_CODE = -9998

        /**
         * Spec 52 T1: sentinel error code used by TmdbCollectionSourceResolver when no TMDB key
         * is set, so nothing was requested; the folder shows "This page needs a TMDB key".
         */
        const val NO_TMDB_KEY_CODE = -9997
    }
}
