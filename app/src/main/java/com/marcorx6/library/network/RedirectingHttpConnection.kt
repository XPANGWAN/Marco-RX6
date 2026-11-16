package com.marcorx6.library.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Follows HTTP redirects explicitly so every hop is validated before a socket is
 * opened. HTTPS remains the default; cleartext is limited by SecureUrlPolicy.
 */
internal object RedirectingHttpConnection {
    private const val MAX_REDIRECTS = 10
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun open(
        initialUrl: String,
        configure: (connection: HttpURLConnection, currentUrl: URL, isInitialRequest: Boolean) -> Unit
    ): HttpURLConnection {
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
                val code = connection.responseCode
                if (code !in redirectCodes) return connection
                if (hop == MAX_REDIRECTS) {
                    throw IOException("The server exceeded the maximum of " + MAX_REDIRECTS + " redirects")
                }
                val location = connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("The server returned HTTP " + code + " without a Location header")
                val next = try {
                    URL(current, location)
                } catch (error: Exception) {
                    throw IOException("The server returned an invalid redirect location", error)
                }
                if (!SecureUrlPolicy.isAllowedDownloadUrl(next.toExternalForm())) {
                    throw SecurityException("Redirect target is not allowed: " + next.host)
                }
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
