package com.marcorx6.library.network

import java.net.URI
import java.util.Locale

/**
 * URL policy for downloads. HTTPS is preferred everywhere; cleartext is permitted
 * only for Sony's official PlayStation CDN hosts used by NoPayStation redirects.
 */
internal object SecureUrlPolicy {
    private val sonyCdnHosts = setOf(
        "zeus.dl.playstation.net",
        "ares.dl.playstation.net"
    )

    fun toHttpsUrl(rawUrl: String): String? {
        val value = rawUrl.trim()
        return when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> "https://" + value.substringAfter("://")
            else -> null
        }
    }

    fun isAllowedDownloadUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (uri.userInfo != null) return false
        return scheme == "https" || (scheme == "http" && host in sonyCdnHosts)
    }

    fun upgradeToHttps(rawUrl: String): String {
        return toHttpsUrl(rawUrl) ?: rawUrl.trim()
    }
}
