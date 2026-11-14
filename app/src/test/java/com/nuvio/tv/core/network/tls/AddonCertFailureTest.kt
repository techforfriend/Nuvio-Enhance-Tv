package com.nuvio.tv.core.network.tls

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import retrofit2.Response

/** Spec 68 X7 P6: which failures are certificate failures (shapes from S0). */
class AddonCertFailureTest {

    private fun handshake(cause: Throwable?) = SSLHandshakeException("handshake").apply { cause?.let(::initCause) }

    @Test
    fun `an untrusted or expired chain and a wrong name are certificate failures`() {
        assertTrue(handshake(CertificateException("untrusted")).isAddonCertificateFailure())
        assertTrue(handshake(CertificateException(CertificateExpiredException("expired"))).isAddonCertificateFailure())
        assertTrue(SSLPeerUnverifiedException("Hostname addon.example not verified").isAddonCertificateFailure())
        assertTrue(AddonCertificateSkipException().isAddonCertificateFailure())
    }

    @Test
    fun `a certificate failure behind another route's error is found in suppressed`() {
        val timeout = SocketTimeoutException("connect timed out")
        timeout.addSuppressed(handshake(CertificateException("untrusted")))
        assertTrue(timeout.isAddonCertificateFailure())
        val first = handshake(CertificateException("untrusted"))
        first.addSuppressed(SocketTimeoutException("other route"))
        assertTrue(first.isAddonCertificateFailure())
    }

    @Test
    fun `other TLS and network failures are not`() {
        assertFalse(handshake(null).isAddonCertificateFailure())
        assertFalse(handshake(java.io.EOFException("closed")).isAddonCertificateFailure())
        assertFalse(SocketTimeoutException("timeout").isAddonCertificateFailure())
    }

    @Test
    fun `a cyclic exception graph ends the walk`() {
        val a = java.io.IOException("a")
        val b = java.io.IOException("b", a)
        a.addSuppressed(b)
        assertFalse(a.isAddonCertificateFailure())
    }

    @Test
    fun `safeApiCall returns the typed certificate error with the fixed message`() = runBlocking {
        val context = mockk<Context> { every { getString(R.string.network_error_addon_certificate) } returns "cert" }
        val result = safeApiCall<String>(context) { throw handshake(CertificateException("x")) }
        assertEquals(NetworkResult.Error("cert", certificate = true), result)
        val skipped = safeApiCall<String>(context) { throw AddonCertificateSkipException() }
        assertTrue((skipped as NetworkResult.Error).certificate)
        val ok = safeApiCall(mockk<Context>()) { Response.success("ok") }
        assertEquals(NetworkResult.Success("ok"), ok)
    }
}
