package com.marcorx6.library.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Follows HTTP redirects explicitly so every hop is validated before a socket is
 * opened. HTTPS remains the default; cleartext is limited by SecureUrlPolicy.
 */
internal object RedirectingHttpConnection {
    private const val MAX_REDIRECTS = 10
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    internal fun isRedirectStatus(code: Int): Boolean = code in redirectCodes

    internal fun resolveRedirect(current: URL, location: String): URL {
        val next = try {
            URL(current, location.trim())
        } catch (error: Exception) {
            throw IOException("The server returned an invalid redirect location", error)
        }
        val safeNext = if (SecureUrlPolicy.isAllowedDownloadUrl(next.toExternalForm())) {
            next
        } else if (next.protocol.equals("http", ignoreCase = true)) {
            // Upgrade ordinary HTTP redirect hops to HTTPS instead of enabling cleartext.
            val upgraded = SecureUrlPolicy.toHttpsUrl(next.toExternalForm())
                ?: throw SecurityException("Redirect target is not allowed: " + next.host)
            URL(upgraded)
        } else {
            throw SecurityException("Redirect target is not allowed: " + next.host)
        }
        return safeNext
    }

    fun open(
        initialUrl: String,
        onResponse: (connection: HttpURLConnection, currentUrl: URL) -> Unit = { _, _ -> },
        maxDurationMillis: Long = 180_000L,
        configure: (connection: HttpURLConnection, currentUrl: URL, isInitialRequest: Boolean) -> Unit
    ): HttpURLConnection {
        require(maxDurationMillis > 0L)
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxDurationMillis)
        var current = URL(initialUrl)
        val visited = linkedSetOf<String>()

        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!SecureUrlPolicy.isAllowedDownloadUrl(current.toExternalForm())) {
                throw SecurityException("Redirect target is not allowed: " + current.host)
            }
            if (!visited.add(current.toExternalForm())) throw IOException("The server returned a redirect loop")

            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30000
                readTimeout = 120000
            }
            try {
                configure(connection, current, hop == 0)
                val remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMillis <= 0L) throw SocketTimeoutException("Download source resolution timed out")
                val timeoutMillis = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                connection.connectTimeout = minOf(connection.connectTimeout, timeoutMillis)
                connection.readTimeout = minOf(connection.readTimeout, timeoutMillis)
                val code = connection.responseCode
                onResponse(connection, current)
                if (!isRedirectStatus(code)) return connection
                if (hop == MAX_REDIRECTS) {
                    throw IOException("The server exceeded the maximum of " + MAX_REDIRECTS + " redirects")
                }
                val location = connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("The server returned HTTP " + code + " without a Location header")
                val next = resolveRedirect(current, location)
                connection.disconnect()
                current = next
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Could not resolve the download URL")
    }

    fun isSameOrigin(first: URL, second: URL): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int =
        if (url.port >= 0) url.port else if (url.protocol.lowercase(Locale.ROOT) == "https") 443 else 80
}
