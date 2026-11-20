package com.marcorx6.library.network

import java.net.URI
import java.util.Locale

/**
 * URL policy for downloads. HTTPS is preferred everywhere; cleartext is permitted
 * only for Sony's official PlayStation CDN hosts used by legacy package links.
 */
internal object SecureUrlPolicy {
    private val sonyCdnHosts = setOf(
        "zeus.dl.playstation.net",
        "ares.dl.playstation.net"
    )

    fun toHttpsUrl(rawUrl: String): String? {
        val value = rawUrl.trim()
        val normalized = when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> "https://" + value.substringAfter("://")
            else -> return null
        }
        return normalized.takeIf(::isAllowedDownloadUrl)
    }

    fun isAllowedDownloadUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']') ?: return false
        if (uri.userInfo != null || isLocalOrPrivateHost(host)) return false
        return scheme == "https" || (scheme == "http" && host in sonyCdnHosts)
    }

    fun upgradeToHttps(rawUrl: String): String {
        val value = rawUrl.trim()
        return when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> "https://" + value.substringAfter("://")
            else -> value
        }
    }

    private fun isLocalOrPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trimEnd('.').lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".lan") || host.endsWith(".home") ||
            host.endsWith(".test") || !host.contains('.')
        ) return true

        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ipv4.size == 4 && ipv4.joinToString(".") == host) {
            val (a, b, c, _) = ipv4
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 198 && b in 18..19) ||
                a >= 224
        }
        // Reject non-canonical numeric IPv4 spellings such as 127.1 or
        // octal dotted forms; URL stacks may resolve these to loopback/private IPs.
        if (host.all { it.isDigit() || it == '.' }) return true

        if (host.contains(':')) {
            // IPv4-mapped IPv6 literals (for example ::ffff:127.0.0.1)
            // must receive the same private-address checks as ordinary IPv4.
            val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
            if (address != null && (
                    address.isAnyLocalAddress || address.isLoopbackAddress ||
                        address.isLinkLocalAddress || address.isSiteLocalAddress ||
                        address.isMulticastAddress
                    )
            ) return true
            return host == "::" || host == "::1" ||
                host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb")
        }
        return false
    }
}
