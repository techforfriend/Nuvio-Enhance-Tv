package com.nuvio.tv.core.network.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

class AccessHeaderStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Reversible stand-in for the Keystore cipher; [failDecrypt] makes the next decrypts throw. */
    private class FakeCipher : AccessHeaderCipher {
        @Volatile var failDecrypt: (() -> Throwable)? = null
        @Volatile var blockDecrypt: CountDownLatch? = null
        val decryptEntered = CountDownLatch(1)
        @Volatile var decrypts = 0
        @Volatile var keyDeletes = 0
        @Volatile var keyPresent = false
        @Volatile var keysMade = 0
        @Volatile var failEncrypt = false

        override fun encrypt(plain: ByteArray, mayCreateKey: Boolean): ByteArray {
            if (failEncrypt) throw java.io.IOException("disk")
            if (!keyPresent) {
                if (!mayCreateKey) throw AccessKeyMissingException()
                keyPresent = true
                keysMade++
            }
            return byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            decrypts++
            decryptEntered.countDown()
            blockDecrypt?.await(10, TimeUnit.SECONDS)
            failDecrypt?.let { throw it() }
            if (blob.isEmpty() || blob[0] != 7.toByte()) throw AccessBlobException()
            return blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        @Volatile var failDeleteKey = false

        override fun deleteKey() {
            keyDeletes++
            if (failDeleteKey) throw KeyStoreException("busy")
            keyPresent = false
        }
    }

    private var now = 1_000_000L
    private val dir by lazy { tmp.newFolder("network_privacy") }
    private val file by lazy { dir.resolve("access_headers.bin") }

    /** The JVM stand-in for `Os.stat`; [unknownState] makes the check unable to decide. */
    @Volatile private var unknownState = false

    private fun store(cipher: AccessHeaderCipher) = AccessHeaderStore(file, cipher, { now }) {
        when {
            unknownState -> FileState.UNKNOWN
            it.exists() -> FileState.PRESENT
            else -> FileState.ABSENT
        }
    }

    private fun site(host: String, value: String = "v-$host", port: Int = 443) =
        AccessSite(host, port, listOf(AccessHeader("X-Key", value)))

    @Test
    fun `no file reads as no sites, and a save survives a new process`() {
        val cipher = FakeCipher()
        val first = store(cipher)
        assertEquals(emptyList<AccessSite>(), first.sitesOrNull())
        assertEquals(AccessSaveResult.SAVED, first.put(site("a.example")))
        assertFalse("never plain text on disk", file.readText(Charsets.ISO_8859_1).contains("v-a.example"))
        assertEquals(listOf(site("a.example")), store(cipher).snapshot())
    }

    @Test
    fun `a save replaces its host and port, and the cap counts sites`() {
        val s = store(FakeCipher())
        repeat(AccessSiteRules.MAX_SITES) { assertEquals(AccessSaveResult.SAVED, s.put(site("h$it.example"))) }
        assertEquals(AccessSaveResult.TOO_MANY_SITES, s.put(site("new.example")))
        assertEquals(AccessSaveResult.SAVED, s.put(site("h3.example", value = "replaced")))
        assertEquals("another port is another site", AccessSaveResult.TOO_MANY_SITES, s.put(site("h3.example", port = 8443)))
        assertEquals("replaced", s.snapshot().single { it.host == "h3.example" && it.port == 443 }.headers.single().value)
        assertEquals(AccessSiteRules.MAX_SITES, s.snapshot().size)
    }

    @Test
    fun `removing the last site deletes the file`() {
        val s = store(FakeCipher())
        s.put(site("a.example"))
        assertEquals(AccessSaveResult.SAVED, s.remove("a.example", 443))
        assertFalse(file.exists())
        assertEquals(emptyList<AccessSite>(), s.snapshot())
    }

    @Test
    fun `an invalid site is refused`() {
        val s = store(FakeCipher())
        assertEquals(AccessSaveResult.INVALID, s.put(AccessSite("a.example", 443, listOf(AccessHeader("X-Key", "a\r\nb")))))
        assertEquals(AccessSaveResult.INVALID, s.put(AccessSite("A.example", 443, listOf(AccessHeader("X-Key", "v")))))
        assertEquals(AccessSaveResult.INVALID, s.put(AccessSite("a.example", 443, emptyList())))
        assertFalse(file.exists())
    }

    @Test
    fun `any read failure leaves the file untouched, refuses saves and retries every 30 s`() {
        // D23: no error is trusted to mean the file is lost, the "permanent" key errors included.
        listOf<() -> Throwable>(
            { KeyStoreException("busy") },
            { UnrecoverableKeyException("Failed to obtain information about key") },
            { AEADBadTagException() },
            { AccessKeyMissingException() },
            { AccessBlobException() }
        ).forEach { failure ->
            val cipher = FakeCipher()
            file.delete()
            store(cipher).put(site("a.example"))
            val saved = file.readBytes()
            cipher.failDecrypt = failure
            val s = store(cipher)
            assertNull(s.sitesOrNull())
            assertEquals(emptyList<AccessSite>(), s.snapshot())
            val tried = cipher.decrypts
            now += AccessHeaderStore.RETRY_MS - 1
            s.snapshot()
            assertEquals("no retry inside 30 s", tried, cipher.decrypts)
            assertEquals(AccessSaveResult.UNREADABLE, s.put(site("b.example")))
            assertEquals(AccessSaveResult.UNREADABLE, s.remove("a.example", 443))
            assertTrue("file untouched", saved.contentEquals(file.readBytes()))
            cipher.failDecrypt = null
            now += 1
            assertNull("the refused save tried a read, restarting the 30 s wait", s.sitesOrNull())
            now += AccessHeaderStore.RETRY_MS
            assertEquals(listOf(site("a.example")), s.sitesOrNull())
        }
    }

    @Test
    fun `a list that does not parse is unreadable, never overwritten`() {
        val cipher = FakeCipher()
        file.writeBytes(cipher.encrypt("not json".toByteArray(), mayCreateKey = true))
        val s = store(cipher)
        assertNull(s.sitesOrNull())
        assertEquals(AccessSaveResult.UNREADABLE, s.put(site("a.example")))
        assertEquals("not json", String(cipher.decrypt(file.readBytes())))
    }

    @Test
    fun `a missing key with a file on disk fails the save and touches nothing`() {
        val cipher = FakeCipher()
        store(cipher).put(site("a.example"))
        val saved = file.readBytes()
        val s = store(cipher)
        assertEquals(listOf(site("a.example")), s.snapshot())
        cipher.keyPresent = false // a transient Keystore fault reporting the key as missing
        assertEquals(AccessSaveResult.FAILED, s.put(site("b.example")))
        assertEquals(1, cipher.keysMade)
        assertTrue(saved.contentEquals(file.readBytes()))
        assertEquals(listOf(site("a.example")), s.snapshot())
        cipher.keyPresent = true
        assertEquals(AccessSaveResult.SAVED, s.put(site("b.example")))
    }

    @Test
    fun `a file whose presence can't be checked is unreadable, and never gets a new key`() {
        val cipher = FakeCipher()
        unknownState = true
        val s = store(cipher)
        assertNull(s.sitesOrNull())
        assertEquals(AccessSaveResult.UNREADABLE, s.put(site("a.example")))
        assertEquals(0, cipher.keysMade)

        unknownState = false
        val ready = store(cipher)
        assertEquals(emptyList<AccessSite>(), ready.sitesOrNull())
        unknownState = true // the check fails between the read and the save
        assertEquals(AccessSaveResult.FAILED, ready.put(site("a.example")))
        assertEquals(0, cipher.keysMade)
        assertFalse(file.exists())
        unknownState = false
        assertEquals(AccessSaveResult.SAVED, ready.put(site("a.example")))
        assertEquals(1, cipher.keysMade)
    }

    @Test
    fun `a failed temp write leaves the file and the list as they were`() {
        val cipher = FakeCipher()
        val s = store(cipher)
        s.put(site("a.example"))
        val saved = file.readBytes()
        dir.resolve("access_headers.bin.tmp").apply { mkdir(); resolve("x").writeText("x") }
        assertEquals(AccessSaveResult.FAILED, s.put(site("b.example")))
        assertEquals(listOf(site("a.example")), s.snapshot())
        assertTrue(saved.contentEquals(file.readBytes()))
    }

    @Test
    fun `remove all that can't delete the file fails and changes nothing`() {
        val cipher = FakeCipher()
        file.mkdirs()
        file.resolve("child").writeText("x") // a non-empty directory: delete() fails
        val s = store(cipher)
        assertNull(s.sitesOrNull())
        assertEquals(AccessSaveResult.FAILED, s.removeAll())
        assertTrue(file.exists())
        assertEquals(0, cipher.keyDeletes)
        assertNull(s.sitesOrNull())
    }

    @Test
    fun `a failed write leaves the file and the list as they were`() {
        val cipher = FakeCipher()
        val s = store(cipher)
        s.put(site("a.example"))
        val saved = file.readBytes()
        cipher.failEncrypt = true
        assertEquals(AccessSaveResult.FAILED, s.put(site("b.example")))
        assertEquals(listOf(site("a.example")), s.snapshot())
        assertTrue(saved.contentEquals(file.readBytes()))
    }

    @Test
    fun `an oversized file is unreadable and untouched`() {
        val cipher = FakeCipher()
        file.writeBytes(ByteArray((AccessHeaderStore.MAX_FILE_BYTES + 1).toInt()))
        val s = store(cipher)
        assertNull(s.sitesOrNull())
        assertEquals(AccessSaveResult.UNREADABLE, s.put(site("a.example")))
        assertEquals(AccessHeaderStore.MAX_FILE_BYTES + 1, file.length())
        assertEquals(0, cipher.decrypts)
    }

    @Test
    fun `remove all succeeds when the key can't be deleted, and clears a stray temp file`() {
        val cipher = FakeCipher()
        val s = store(cipher)
        s.put(site("a.example"))
        val temp = dir.resolve("access_headers.bin.tmp").apply { writeBytes(byteArrayOf(1)) }
        cipher.failDeleteKey = true
        assertEquals(AccessSaveResult.SAVED, s.removeAll())
        assertFalse(file.exists())
        assertFalse(temp.exists())
        assertEquals(emptyList<AccessSite>(), s.snapshot())
        assertEquals("the leftover key is reused", AccessSaveResult.SAVED, s.put(site("b.example")))
        assertEquals(1, cipher.keysMade)
    }

    @Test
    fun `remove all deletes the file and the key, readable or not, and saving works again`() {
        val cipher = FakeCipher()
        store(cipher).put(site("a.example"))
        cipher.failDecrypt = { UnrecoverableKeyException() }
        val s = store(cipher)
        assertNull(s.sitesOrNull())
        assertEquals(AccessSaveResult.SAVED, s.removeAll())
        assertFalse(file.exists())
        assertEquals(1, cipher.keyDeletes)
        assertEquals(emptyList<AccessSite>(), s.sitesOrNull())
        cipher.failDecrypt = null
        assertEquals(AccessSaveResult.SAVED, s.put(site("b.example")))
        assertEquals(listOf(site("b.example")), store(cipher).snapshot())
    }

    @Test
    fun `the first load waits at most two seconds, then callers go on without headers`() {
        val cipher = FakeCipher()
        store(cipher).put(site("a.example"))
        val gate = CountDownLatch(1)
        cipher.blockDecrypt = gate
        val s = store(cipher)
        val loader = thread { s.snapshot() }
        assertTrue(cipher.decryptEntered.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertNull(s.sitesOrNull())
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("waited $waitedMs ms", waitedMs in 1_500..10_000)
        val again = System.nanoTime()
        assertNull(s.sitesOrNull())
        assertTrue("later callers stop waiting", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 1_500)
        gate.countDown()
        loader.join(5_000)
        assertEquals(listOf(site("a.example")), s.snapshot())
    }

    @Test(timeout = 15_000)
    fun `a due retry runs on one caller while the others go on at once`() {
        val cipher = FakeCipher()
        store(cipher).put(site("a.example"))
        cipher.failDecrypt = { KeyStoreException("busy") }
        val s = store(cipher)
        assertNull(s.sitesOrNull())
        cipher.failDecrypt = null
        val gate = CountDownLatch(1)
        cipher.blockDecrypt = gate
        val entered = cipher.decrypts
        now += AccessHeaderStore.RETRY_MS
        val retrier = thread { s.sitesOrNull() }
        while (cipher.decrypts == entered) Thread.sleep(5)
        val started = System.nanoTime()
        assertNull(s.sitesOrNull())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
        gate.countDown()
        retrier.join(5_000)
        assertEquals(listOf(site("a.example")), s.snapshot())
    }

    @Test
    fun `an Error from the cipher never escapes a read`() {
        val cipher = FakeCipher()
        store(cipher).put(site("a.example"))
        cipher.failDecrypt = { StackOverflowError() }
        assertEquals(emptyList<AccessSite>(), store(cipher).snapshot())
    }
}
