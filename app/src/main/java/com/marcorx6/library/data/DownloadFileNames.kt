package com.marcorx6.library.data

import android.webkit.MimeTypeMap
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Resolves a stable, sanitized filename and preserves a usable file extension. */
internal object DownloadFileNames {
    // Keep filename recognition aligned with DownloadSourceResolver. A link may
    // be correctly identified as a file while the filename layer still drops its
    // real extension; that mismatch produces generic names or the wrong MIME suffix.
    private val knownExtensions = setOf(
        "apk", "xapk", "apks", "aab", "exe", "msi", "dmg", "deb", "rpm",
        "zip", "7z", "rar", "r00", "tar", "tgz", "gz", "bz2", "xz", "zst",
        "iso", "img", "bin", "cue", "chd", "pkg", "pbp", "cso", "nsp", "xci",
        "3ds", "cia", "wad", "wbfs", "rvz", "gcz", "nes", "sfc", "smc",
        "gba", "gbc", "gb", "nds", "n64", "z64", "v64", "torrent",
        "pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "csv", "json", "xml", "mp4", "m4v", "mkv", "webm", "mov",
        "avi", "flv", "3gp", "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"
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

    /**
     * Prefer the app's game title when the server reports a contradictory filename.
     * A response such as "Empty.bin" must not replace a known game title "Marco.pkg",
     * and a URL ending in .pkg must not inherit a conflicting .bin response suffix.
     */
    fun resolvePreferredName(
        preferredTitle: String,
        responseName: String?,
        mimeType: String,
        id: Long,
        sourceUrl: String = ""
    ): String {
        val preferred = clean(preferredTitle).ifBlank { "download_$id" }
        val response = responseName?.let(::clean).orEmpty()
        val preferredExtension = knownExtension(preferred)
        val responseExtension = knownExtension(response)
        val urlExtension = extensionFromUrl(sourceUrl)
        val chosen = when {
            preferredExtension != null && responseExtension != null &&
                preferredExtension != responseExtension -> preferred
            urlExtension != null && responseExtension != null &&
                urlExtension != responseExtension -> preferred
            response.isNotBlank() -> response
            else -> preferred
        }
        return safeName(chosen, mimeType, id, sourceUrl)
    }

    private fun knownExtension(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension.takeIf { it in knownExtensions }
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
        val lastSegment = decoded.substringAfter('/')
        val extension = lastSegment.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Some gateways encode the file type as the final path segment,
        // e.g. /get/.../pkg?version=1.01, rather than a filename suffix.
        return when {
            extension in knownExtensions -> extension
            lastSegment.lowercase(Locale.ROOT) in knownExtensions -> lastSegment.lowercase(Locale.ROOT)
            else -> null
        }
    }

    private fun urlExtensionName(url: String): String? {
        val decoded = decodePathSegment(lastPathSegment(url))
        // An extension-only endpoint such as /.../pkg is not a filename.
        // Let safeName append that extension to the game title instead.
        val suffix = decoded.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return decoded.takeIf { suffix in knownExtensions }?.let(::clean)
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
            "application/vnd.playstation.pkg", "application/x-pkg" -> "pkg"
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
