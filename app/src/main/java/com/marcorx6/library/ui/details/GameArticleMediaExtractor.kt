package com.marcorx6.library.ui.details

import java.net.URI

internal object GameArticleMediaExtractor {
    data class ArticleMedia(val images: List<String>, val youtubeVideoId: String?)

    private val imageTag = Regex("(?is)<img\\b[^>]*>")
    private val attribute = Regex("""(?is)([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))""")
    private val youtubeId = Regex("^[A-Za-z0-9_-]{11}$")

    fun extract(html: String, coverUrl: String = ""): ArticleMedia {
        if (html.isBlank()) return ArticleMedia(emptyList(), null)
        val coverKey = canonicalImage(coverUrl)
        val images = LinkedHashMap<String, String>()
        imageTag.findAll(html).forEach tagLoop@{ tagMatch ->
            val attrs = attributes(tagMatch.value)
            val width = attrs["width"]?.toIntOrNull()
            val height = attrs["height"]?.toIntOrNull()
            if ((width != null && width < 80) || (height != null && height < 80)) return@tagLoop
            val candidates = listOf("src", "data-src", "data-original", "data-lazy-src", "data-original-src")
                .mapNotNull { attrs[it] } + attrs["srcset"].orEmpty()
                .split(',')
                .map { it.trim().substringBefore(' ').trim() }
            candidates.forEach candidateLoop@{ raw ->
                val url = safeHttpsUrl(raw) ?: return@candidateLoop
                val key = canonicalImage(url)
                if (key.isBlank() || key == coverKey || isTrackingOrIcon(url)) return@candidateLoop
                images.putIfAbsent(key, url)
            }
        }
        return ArticleMedia(images.values.toList(), extractYoutubeVideoId(html))
    }

    fun extractYoutubeVideoId(html: String): String? {
        if (html.isBlank()) return null
        val values = mutableListOf<String>()
        Regex("""(?is)(?:src|href)\\s*=\\s*["']([^"']+)["']""").findAll(html)
            .forEach { values += decode(it.groupValues[1]) }
        Regex("""https?://[^\\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(html)
            .forEach { values += decode(it.value).trimEnd(')', ']', ',', ';') }
        for (candidate in values) {
            val uri = runCatching { URI(candidate) }.getOrNull() ?: continue
            val host = uri.host?.lowercase() ?: continue
            val id = when {
                host == "youtu.be" -> uri.path.orEmpty().trim('/').substringBefore('/')
                host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" ->
                    when {
                        uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&')
                            .firstOrNull { it.substringBefore('=') == "v" }?.substringAfter('=', "")
                        uri.path?.startsWith("/embed/") == true -> uri.path.substringAfter("/embed/").substringBefore('/')
                        uri.path?.startsWith("/shorts/") == true -> uri.path.substringAfter("/shorts/").substringBefore('/')
                        else -> null
                    }
                host == "youtube-nocookie.com" || host == "www.youtube-nocookie.com" ->
                    if (uri.path?.startsWith("/embed/") == true) uri.path.substringAfter("/embed/").substringBefore('/') else null
                else -> null
            }
            if (id != null && youtubeId.matches(id)) return id
        }
        return null
    }

    private fun attributes(tag: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        attribute.findAll(tag).forEach { match ->
            val value = (1..3).firstNotNullOfOrNull { match.groups[it]?.value } ?: ""
            result[match.groupValues[1].lowercase()] = decode(value)
        }
        return result
    }

    private fun safeHttpsUrl(raw: String): String? {
        val uri = runCatching { URI(decode(raw.trim())) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.host.contains("..")) return null
        if (uri.host.equals("localhost", true) || uri.host == "127.0.0.1") return null
        return uri.toASCIIString()
    }

    private fun canonicalImage(url: String): String {
        if (url.isBlank()) return ""
        return runCatching {
            val uri = URI(url)
            val path = uri.path.orEmpty().replace(Regex("/s[0-9]+(?:-c)?/"), "/s0/")
            URI(uri.scheme?.lowercase(), uri.authority?.lowercase(), path, null, null).toString()
        }.getOrDefault(url.substringBefore('?').substringBefore('#'))
    }

    private fun isTrackingOrIcon(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        val name = path.substringAfterLast('/')
        return name.contains("pixel") || name.contains("tracker") ||
            name.contains("spacer") || name.contains("blank.gif") ||
            name.contains("icon") || name.contains("logo") ||
            name.endsWith(".svg") || name.endsWith(".gif")
    }

    private fun decode(value: String): String = value
        .replace("&amp;", "&", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
}
