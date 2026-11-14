package com.nuvio.tv.core.network.tls

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Spec 68 X7 test fixtures (`resources/x7tls/`, see its README) and the TLS servers built from them. */
internal object X7TlsFixtures {
    private val PASSWORD = "x7tls-test".toCharArray()

    /** Every fake host name resolves to loopback, so "another host" is a different name, not a port. */
    val loopbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    fun keyStore(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        X7TlsFixtures::class.java.classLoader!!.getResourceAsStream("x7tls/$name.p12").use { load(it, PASSWORD) }
    }

    fun leaf(name: String): X509Certificate = keyStore(name).getCertificate("leaf") as X509Certificate

    /** An HTTPS server presenting [fixture]; the caller shuts it down. */
    fun server(fixture: String, http2: Boolean = false): MockWebServer {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(fixture), PASSWORD)
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return MockWebServer().apply {
            useHttps(context.socketFactory, false)
            if (http2) protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            repeat(4) { enqueue(MockResponse().setBody("ok")) }
            start()
        }
    }

    /** A platform that trusts [fixture] as an anchor: a "CA-valid" certificate for its own names. */
    fun platformTrusting(fixture: String): X509TrustManager {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("anchor", leaf(fixture))
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(anchors)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    fun pin(host: String, fixture: String, port: Int = 443) =
        AddonCertPin(host = host, port = port, sha256 = leafSha256(leaf(fixture)), trustedAtMs = 0L)

    fun url(host: String, server: MockWebServer): HttpUrl =
        HttpUrl.Builder().scheme("https").host(host).port(server.port).build()
}
