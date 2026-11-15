package com.marcorx6.library.data

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Verifies response-body integrity when the server supplies a standard digest.
 * Servers without a supported digest continue to use strict range, validator,
 * and byte-count checks.
 */
internal class DownloadIntegrityVerifier private constructor(
    private val digest: MessageDigest,
    private val expected: ByteArray
) {
    fun update(bytes: ByteArray, count: Int) {
        digest.update(bytes, 0, count)
    }

    @Throws(IOException::class)
    fun verify() {
        if (!MessageDigest.isEqual(expected, digest.digest())) {
            throw IOException("The server response checksum did not match")
        }
    }

    companion object {
        fun from(connection: HttpURLConnection): DownloadIntegrityVerifier? {
            val contentDigest = connection.getHeaderField("Content-Digest").orEmpty()
            val sha256 = contentDigest.split(',')
                .firstOrNull { it.substringBefore('=').trim().equals("sha-256", ignoreCase = true) }
                ?.substringAfter('=', "")
                ?.trim()
                ?.removePrefix(":")
                ?.removeSuffix(":")
            val contentMd5 = connection.getHeaderField("Content-MD5")?.trim()
            val (algorithm, encoded) = when {
                !sha256.isNullOrBlank() -> "SHA-256" to sha256
                !contentMd5.isNullOrBlank() -> "MD5" to contentMd5
                else -> return null
            }
            val expected = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                ?: return null
            val digest = runCatching { MessageDigest.getInstance(algorithm) }.getOrNull()
                ?: return null
            return DownloadIntegrityVerifier(digest, expected)
        }
    }
}
