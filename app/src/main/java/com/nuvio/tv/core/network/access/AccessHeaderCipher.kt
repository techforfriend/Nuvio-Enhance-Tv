package com.nuvio.tv.core.network.access

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the saved-site list at rest (Spec 97 A2); behind an interface so JVM tests use a fake. */
internal interface AccessHeaderCipher {
    /**
     * Makes the key when there is none only if [mayCreateKey] (D24: no saved file on disk); else a
     * missing key throws [AccessKeyMissingException]. A transient Keystore fault can report an existing
     * key as missing, and replacing it would orphan the saved file if the write then failed.
     */
    fun encrypt(plain: ByteArray, mayCreateKey: Boolean): ByteArray

    fun decrypt(blob: ByteArray): ByteArray

    /** "Remove saved sites" (D23). */
    fun deleteKey()
}

/** No Keystore key for the file (a wiped Keystore, or a transient fault: D23 treats both alike). */
internal class AccessKeyMissingException : GeneralSecurityException("access header key missing")

/** The file is not a blob this version wrote. */
internal class AccessBlobException : GeneralSecurityException("access header file malformed")

/**
 * AES-256-GCM under an Android Keystore key. Blob: one version byte, the 12-byte IV the cipher chose
 * (Keystore keys require randomized encryption, so `init` takes no IV), then ciphertext + 16-byte tag.
 */
internal class KeystoreAccessHeaderCipher(private val alias: String = ALIAS) : AccessHeaderCipher {

    override fun encrypt(plain: ByteArray, mayCreateKey: Boolean): ByteArray {
        // With no saved file, an entry that can't even be read holds nothing: replace it rather than fail forever.
        val existing = if (mayCreateKey) runCatching { existingKey() }.getOrNull() else existingKey()
        val key = existing ?: if (mayCreateKey) generateKey() else throw AccessKeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected GCM IV size")
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < HEADER_BYTES + TAG_BYTES || blob[0] != VERSION) throw AccessBlobException()
        val key = existingKey() ?: throw AccessKeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, blob, 1, IV_BYTES))
        return cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
    }

    override fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()

    private companion object {
        const val ALIAS = "nuvio_access_headers_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER_BYTES = 1 + IV_BYTES
    }
}
