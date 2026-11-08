package com.nuvio.tv.core.update

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 52 P6/P7: asset pick, the public-build gate, the outcome mapping and the daily throttle. */
class UpdateReleaseTest {

    private val digest = "sha256:" + "AB".repeat(32)

    private fun asset(
        name: String,
        state: String? = "uploaded",
        size: Long? = 41_000_000L,
        digest: String? = this.digest,
        url: String? = "https://github.com/Arshad-Kamal/Nuvio-AK/releases/download/v1/$name"
    ) = GitHubAssetDto(name = name, state = state, size = size, digest = digest, browserDownloadUrl = url)

    private fun release(vararg assets: GitHubAssetDto, body: String? = "Fixes") =
        GitHubReleaseDto(name = "Nuvio AK 0.9.0-ak51", body = body, publishedAt = "2026-11-03T10:00:00Z", assets = assets.toList())

    private val v7a = "NuvioAK-0.9.0-ak51-1392-armeabi-v7a.apk"
    private val v8a = "NuvioAK-0.9.0-ak51-1392-arm64-v8a.apk"

    @Test
    fun `picks the first supported ABI's APK and reads name, code and digest`() {
        val offer = releaseOffer(release(asset(v8a), asset(v7a)), listOf("armeabi-v7a", "armeabi"))!!
        assertEquals("armeabi-v7a", offer.abi)
        assertEquals("0.9.0-ak51", offer.versionName)
        assertEquals(1392, offer.versionCode)
        assertEquals("ab".repeat(32), offer.sha256)
        assertEquals(41_000_000L, offer.sizeBytes)
        assertEquals("Fixes", offer.notes)
        assertEquals("2026-11-03T10:00:00Z", offer.publishedAt)
        assertEquals("arm64-v8a", releaseOffer(release(asset(v7a), asset(v8a)), listOf("arm64-v8a", "armeabi-v7a"))!!.abi)
        assertNull(releaseOffer(release(asset(v8a)), listOf("armeabi-v7a")))
        assertNull(releaseOffer(release(asset(v7a)), listOf("x86_64")))
    }

    @Test
    fun `an asset counts only when name, state, size, digest and URL all hold`() {
        fun offered(asset: GitHubAssetDto) = releaseOffer(release(asset), listOf("armeabi-v7a")) != null
        assertTrue(offered(asset(v7a)))
        assertFalse(offered(asset("NuvioAK-0.9.0-ak51-armeabi-v7a.apk")))
        assertFalse(offered(asset("NuvioAK-0.9.0-ak51-1392-universal.apk")))
        assertFalse(offered(asset("Other-0.9.0-ak51-1392-armeabi-v7a.apk")))
        assertFalse(offered(asset("NuvioAK-0.9.0-ak51-99999999999-armeabi-v7a.apk")))
        assertFalse(offered(asset(v7a, state = "open")))
        assertFalse(offered(asset(v7a, size = 0L)))
        assertFalse(offered(asset(v7a, size = MAX_ASSET_BYTES + 1)))
        assertTrue(offered(asset(v7a, size = MAX_ASSET_BYTES)))
        assertFalse(offered(asset(v7a, digest = null)))
        assertFalse(offered(asset(v7a, digest = "sha512:" + "ab".repeat(32))))
        assertFalse(offered(asset(v7a, digest = "sha256:abc")))
        assertFalse(offered(asset(v7a, url = "http://github.com/x/$v7a")))
        assertFalse(offered(asset(v7a, url = null)))
    }

    @Test
    fun `of two APKs for one ABI the higher code wins, and notes are capped`() {
        val older = asset("NuvioAK-0.9.0-ak50-1391-armeabi-v7a.apk")
        val offer = releaseOffer(release(older, asset(v7a), body = "x".repeat(MAX_NOTES_CHARS + 10)), listOf("armeabi-v7a"))!!
        assertEquals(1392, offer.versionCode)
        assertEquals(MAX_NOTES_CHARS, offer.notes.length)
    }

    @Test
    fun `outcome per answer`() {
        val abis = listOf("armeabi-v7a")
        assertEquals(1392, (checkOutcome(200, release(asset(v7a)), abis) as CheckOutcome.Answered).offer?.versionCode)
        assertEquals(CheckOutcome.Answered(null), checkOutcome(200, release(), abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(200, null, abis))
        assertEquals(CheckOutcome.NoRelease, checkOutcome(404, null, abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(403, null, abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(429, null, abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(500, null, abis))
    }

    @Test
    fun `auto check at most once a day, and when the clock went back`() {
        val day = AUTO_CHECK_INTERVAL_MS
        assertTrue(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 0L))
        assertFalse(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 10 * day - day + 1))
        assertTrue(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 9 * day))
        assertTrue(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 10 * day + 1))
    }

    @Test
    fun `badge, rail dot and pill show only for a known newer release with Check automatically on`() {
        val release = releaseOffer(release(asset(v7a)), listOf("armeabi-v7a"))!!
        val shown = UpdateState(supported = true, autoCheck = true, available = release)
        assertTrue(shown.noticeShown)
        assertFalse(shown.copy(autoCheck = false).noticeShown)
        assertFalse(shown.copy(available = null).noticeShown)
        assertFalse(shown.copy(supported = false).noticeShown)
    }

    @Test
    fun `only a keyless release build under the AK id checks`() {
        assertTrue(updateCheckSupported("", debugBuild = false, applicationId = "com.nuvio.tv.ak"))
        assertFalse(updateCheckSupported("0123abcd", debugBuild = false, applicationId = "com.nuvio.tv.ak"))
        assertFalse(updateCheckSupported("", debugBuild = true, applicationId = "com.nuvio.tv.ak"))
        assertFalse(updateCheckSupported("", debugBuild = false, applicationId = "com.nuvio.tv.ak.debug"))
    }
}
