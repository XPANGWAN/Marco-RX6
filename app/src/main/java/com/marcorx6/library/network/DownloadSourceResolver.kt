package com.marcorx6.library.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Separates provider/landing-page discovery from the byte-transfer engines.
 * Known providers get deterministic URL normalization; unknown HTML pages use
 * conservative link extraction and otherwise remain available in the in-app browser.
 */
internal object DownloadSourceResolver {
    private val fileExtension = Regex(
        """.(apk|zip|7z|rar|iso|pkg|bin|cue|chd|img|pbp|cso|nsp|xci|pdf|mp4|m4v|mkv|webm|mov|avi|flv|3gp|mp3|m4a|aac|wav|flac|ogg)(?:$|[?#])""",
        RegexOption.IGNORE_CASE
    )

    fun normalizeKnownProviderUrl(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return rawUrl.trim()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host != "drive.google.com" && host != "docs.google.com") return rawUrl.trim()

        val pathId = Regex("""/file/d/([A-Za-z0-9_-]{10,})""").find(uri.path.orEmpty())?.groupValues?.get(1)
        val queryId = queryParameter(uri.rawQuery.orEmpty(), "id")
        val id = pathId ?: queryId ?: return rawUrl.trim()
        return "https://drive.google.com/uc?export=download&id=$id"
    }

    /** Returns a direct candidate only when page markup provides download evidence. */
    fun extractDownloadUrl(pageUrl: String, rawHtml: String): String? {
        if (rawHtml.isBlank()) return null
        val html = decodeEscapes(rawHtml.take(MAX_HTML_CHARS))
        val candidates = mutableListOf<Pair<Int, String>>()

        val anchor = Regex(
            """<a\b([^>]*?)href\s*=\\s*["']([^"'<>]+)["']([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        anchor.findAll(html).forEach { match ->
            val before = match.groupValues[1]
            val href = match.groupValues[2]
            val after = match.groupValues[3]
            val label = stripTags(match.groupValues[4])
            val attrs = before + " " + after
            val score = scoreCandidate(href, label, attrs)
            if (score > 0) candidates += score to href
        }

        // MediaFire and similar hosts often expose a stable download-button href.
        val buttonPatterns = listOf(
            Regex("""id\s*=\\s*["']downloadButton["'][^>]*href\s*=\\s*["']([^"'<>]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""href\s*=\\s*["']([^"'<>]+)["'][^>]*id\s*=\\s*["']downloadButton["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:downloadUrl|download_url|downloadLink)\s*[:=]\\s*["']([^"'<>]+)["']""", RegexOption.IGNORE_CASE)
        )
        buttonPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { candidates += 100 to it.groupValues[1] }
        }

        // Google Drive confirmation pages use a hidden confirm token or a generated download href.
        val driveConfirm = Regex("""(?:href|action)\\s*=\\s*["']([^"'<>]*(?:export=download|confirm=)[^"'<>]*)["']""", RegexOption.IGNORE_CASE)
        driveConfirm.findAll(html).forEach { candidates += 95 to it.groupValues[1] }

        return candidates
            .sortedByDescending { it.first }
            .asSequence()
            .mapNotNull { (_, value) -> resolveCandidate(pageUrl, value) }
            .firstOrNull()
    }

    private fun scoreCandidate(href: String, label: String, attrs: String): Int {
        val value = href.trim()
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) return 0
        val combined = "$label $attrs $value"
        var score = 0
        if (fileExtension.containsMatchIn(value)) score += 80
        if (Regex("""downloadButton|download-link|download_url|data-download""", RegexOption.IGNORE_CASE).containsMatchIn(attrs)) score += 90
        if (Regex("""download|تحميل|تنزيل|direct|file""", RegexOption.IGNORE_CASE).containsMatchIn(label)) score += 45
        if (Regex("""(?:^|/)(?:download|attachment|export)(?:/|$)|[?&](?:download|export)=(""|1|true|yes)""", RegexOption.IGNORE_CASE).containsMatchIn(value)) score += 35
        if (Regex("""logout|sign.?in|login|register|privacy|terms|javascript:""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) score = 0
        return score
    }

    private fun resolveCandidate(base: String, candidate: String): String? {
        val cleaned = candidate.trim()
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("\\/", "/")
        val resolved = runCatching { URI(base).resolve(cleaned).toString() }.getOrNull() ?: return null
        val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        if (uri.host.equals(URI(base).host, true) && !fileExtension.containsMatchIn(uri.path.orEmpty()) &&
            !Regex("""(?:download|export|attachment)""", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawQuery.orEmpty())
        ) return null
        return resolved
    }

    private fun decodeEscapes(value: String): String = value
        .replace("\\u0026", "&", true)
        .replace("\\u003d", "=", true)
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")

    private fun stripTags(value: String): String =
        value.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun queryParameter(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (!key.equals(name, true)) null else runCatching {
                URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name())
            }.getOrNull()
        }

    private const val MAX_HTML_CHARS = 1_500_000
}
