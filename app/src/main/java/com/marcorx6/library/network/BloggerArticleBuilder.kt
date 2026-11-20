package com.marcorx6.library.network

import org.json.JSONArray
import java.net.URI

/**
 * Builds the Blogger article from the MARCO RX6 structured publishing template.
 * Preview and publishing use this same HTML to keep their output identical.
 */
internal object BloggerArticleBuilder {
    private val youtubeIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun safeImageUrl(raw: String): String {
        val normalized = BloggerFeedParser.normalizeBloggerImageUrl(raw.trim())
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return ""
        return if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && !uri.host.contains("..")) uri.toASCIIString() else ""
    }

    private fun imageKey(raw: String): String = raw.substringBefore('?').substringBefore('#')

    fun build(
        title: String,
        categories: String,
        genre: String,
        releaseYear: String,
        description: String,
        coverUrl: String,
        downloadUrl: String,
        packageSize: String = "",
        version: String = "",
        fileFormat: String = "PKG",
        language: String = "الإنجليزية",
        password: String = "",
        rights: String = "",
        downloadLinksJson: String = "",
        galleryImageUrls: List<String> = emptyList(),
        youtubeVideoId: String? = null
    ): String {
        val safeTitle = escape(title)
        val metadata = listOf(
            "الحجم" to packageSize,
            "النسخة" to version,
            "الصيغة" to fileFormat.ifBlank { "PKG" },
            "اللغة" to language.ifBlank { "الإنجليزية" },
            "كلمة السر " to password,
            "الحقوق" to rights
        ).joinToString("\n") { (label, value) ->
            "<li><strong>$label:</strong> ${escape(value)}</li>"
        }

        val links = parseLinks(downloadLinksJson, downloadUrl)
        val linksHtml = links.joinToString("\n") { item ->
            "<p><a href=\"${escape(item.second)}\" target=\"_blank\">${escape(item.first)}</a></p>"
        }
        val coverUrlSafe = safeImageUrl(coverUrl)
        val safeCover = escape(coverUrlSafe)
        val coverHtml = if (safeCover.isNotBlank()) {
            "<p style=\"text-align:center\"><a href=\"$safeCover\" target=\"_blank\"><img src=\"$safeCover\" alt=\"$safeTitle\" style=\"max-width:100%;height:auto;\"/></a></p>"
        } else ""
        val gallery = galleryImageUrls.mapNotNull { safeImageUrl(it).takeIf(String::isNotBlank) }
            .filterNot { coverUrlSafe.isNotBlank() && imageKey(it) == imageKey(coverUrlSafe) }
            .distinctBy(::imageKey)
        val galleryHtml = if (gallery.isEmpty()) "" else {
            val images = gallery.joinToString("\\n") { url ->
                "<p style=\\"text-align:center;margin:12px 0\\"><img src=\\""+escape(url)+"\\" alt=\\""+escape(title)+" screenshot\\" loading=\\"lazy\\" style=\\"display:block;width:100%;max-width:100%;height:auto;margin:auto;border-radius:12px;\\"/></p>"
            }
            "<h3>معرض صور اللعبة:</h3><div class=\\"marcorx6-gallery\\">$images</div>"
        }
        val safeYoutubeId = youtubeVideoId?.takeIf { youtubeIdPattern.matches(it) }
        val youtubeHtml = safeYoutubeId?.let { id ->
            "<h3>فيديو اللعبة:</h3><div style=\\"position:relative;width:100%;aspect-ratio:16/9;overflow:hidden;border-radius:12px;\\"><iframe src=\\"https://www.youtube-nocookie.com/embed/$id\\" title=\\""+escape(title)+" video\\" loading=\\"lazy\\" style=\\"position:absolute;inset:0;width:100%;height:100%;border:0;\\" allow=\\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share\\" allowfullscreen></iframe></div><p><a href=\\"https://www.youtube.com/watch?v=$id\\" target=\\"_blank\\" rel=\\"noopener\\">مشاهدة الفيديو على YouTube</a></p>"
        }.orEmpty()

        return """
            <div class="marcorx6-post">
              <p>أهلاً بكم متابعي مدونة MARCO_RX6 الكرام. إليكم لعبة جديدة من إضافاتنا الحصرية بروابط مباشرة وسريعة.</p>
              $coverHtml
              <h3 style="color: #8b5cf6;">معلومات اللعبة:</h3>
              <ul>
                $metadata
              </ul>
              $galleryHtml
              $youtubeHtml
              <h3 style="color: #8b5cf6;">روابط التحميل المباشرة:</h3>
              $linksHtml
            </div>
        """.trimIndent()
    }

    private fun parseLinks(json: String, fallbackUrl: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val array = JSONArray(json)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val label = item.optString("label").trim()
                val url = item.optString("url").trim()
                if (label.isNotBlank() && (url.startsWith("https://", true) || url.startsWith("http://", true))) {
                    result += label to url
                }
            }
        } catch (_: Exception) {
            // Older posts may not have structured download links.
        }
        if (result.isEmpty() && fallbackUrl.isNotBlank() &&
            (fallbackUrl.startsWith("https://", true) || fallbackUrl.startsWith("http://", true))
        ) result += "تحميل اللعبة" to fallbackUrl
        return result
    }
}
