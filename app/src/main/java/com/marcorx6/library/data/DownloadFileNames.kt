package com.marcorx6.library.data

import android.webkit.MimeTypeMap
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Resolves a stable, sanitized filename and preserves a usable file extension. */
internal object DownloadFileNames {
    private val knownExtensions = setOf(
        "apk", "zip", "7z", "rar", "iso", "pkg", "bin", "cue", "chd", "img",
        "pbp", "cso", "nsp", "xci", "pdf", "mp4", "m4v", "mkv", "webm",
        "mov", "avi", "flv", "3gp", "mp3", "m4a", "aac", "wav", "flac", "ogg"
    )

    fun fromResponse(disposition: String?, url: String): String? {
        val header = disposition.orEmpty()
        val encodedMatch = Regex(
            """filename\*\s*=\s*([^']*)'[^']*'([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(header)
        if (encodedMatch != null) {
            val charset = encodedMatch.groupValues[1].ifBlank { "UTF-8" }
            val encoded = encodedMatch.groupValues[2].trim().trim('"')
            runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return clean(it) }
        }
        val regularMatch = Regex(
            """filename\s*=\s*(?:"([^"]*)"|([^;]+))""",
            RegexOption.IGNORE_CASE
        ).find(header)
        val regular = regularMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: regularMatch?.groupValues?.getOrNull(2)?.trim()
        if (!regular.isNullOrBlank()) return clean(regular)
        return urlExtensionName(url)
    }

    /** Generic octet-stream responses are not reliable MIME extension sources. */
    fun safeName(value: String, mimeType: String, id: Long, sourceUrl: String = ""): String {
        var name = clean(value).replace(Regex("""[\u0000-\u001F\u007F/\\:*?"<>|]"""), "_")
            .trim(' ', '.', '_')
            .take(180)
        if (name.isBlank()) name = "download_$id"

        val existingExtension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (existingExtension !in knownExtensions) {
            val extension = extensionFromUrl(sourceUrl) ?: extensionFromMime(mimeType)
            if (!extension.isNullOrBlank()) name = name.trimEnd('.') + ".$extension"
        }
        return name
    }

    private fun extensionFromUrl(url: String): String? {
        if (url.isBlank()) return null
        val decoded = decodePathSegment(lastPathSegment(url))
        val extension = decoded.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension.takeIf { it in knownExtensions }
    }

    private fun urlExtensionName(url: String): String? {
        val decoded = decodePathSegment(lastPathSegment(url))
        return decoded.takeIf { extensionFromUrl(url) != null }?.let(::clean)
    }

    /**
     * Use java.net.URI rather than android.net.Uri so filename resolution also
     * works in local JVM unit tests (where Android framework methods are stubs).
     */
    private fun lastPathSegment(url: String): String {
        val rawPath = runCatching { URI(url).rawPath }.getOrNull()
            ?: url.substringBefore('#').substringBefore('?').substringAfter("://", url)
        return rawPath.substringAfterLast('/')
    }

    private fun decodePathSegment(segment: String): String =
        runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(segment)

    private fun extensionFromMime(mimeType: String): String? {
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.isBlank() || mime == "application/octet-stream") return null
        val knownMime = when (mime) {
            "application/vnd.android.package-archive" -> "apk"
            "application/zip", "application/x-zip-compressed" -> "zip"
            else -> null
        }
        return knownMime ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase(Locale.ROOT)?.takeIf { it in knownExtensions }
    }

    private fun clean(value: String): String = value
        .replace("\r", "")
        .replace("\n", "")
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
}
