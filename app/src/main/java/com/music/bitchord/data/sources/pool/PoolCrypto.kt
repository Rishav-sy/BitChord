package com.music.bitchord.data.sources.pool

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts sensitive credential values shared by the ArchivePool community service.
 * Derives AES-256-GCM key material using SHA-256 over the domain-separated read key.
 */
object PoolCrypto {
    private const val PREFIX = "enc:1:"
    private const val GCM_TAG_BITS = 128
    private const val CLIENT_KEY_DOMAIN = "archivepool-client:"

    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    fun deriveClientKey(readKey: String): SecretKeySpec? {
        val trimmed = readKey.trim()
        if (trimmed.isEmpty()) return null
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest((CLIENT_KEY_DOMAIN + trimmed).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(bytes, "AES")
    }

    fun decryptWith(
        blob: String,
        secret: SecretKeySpec?,
    ): String? {
        if (secret == null) return null
        if (!blob.startsWith(PREFIX)) return blob
        return runCatching {
            val body = blob.substring(PREFIX.length)
            val parts = body.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val data = Base64.decode(parts[1], Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun maybeDecryptWith(
        value: String?,
        secret: SecretKeySpec?,
    ): String? {
        if (value == null) return null
        if (!value.startsWith(PREFIX)) return value
        return decryptWith(value, secret)
    }
}
