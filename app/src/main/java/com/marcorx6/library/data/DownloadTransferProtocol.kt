package com.marcorx6.library.data

/** Strict, Android-independent validation for HTTP byte-range responses. */
internal object DownloadTransferProtocol {
    data class ContentRange(val start: Long, val endInclusive: Long, val total: Long) {
        val length: Long get() = endInclusive - start + 1L
    }
    private val pattern = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
    fun parseContentRange(value: String): ContentRange? {
        val match = pattern.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end || end - start == Long.MAX_VALUE) return null
        return ContentRange(start, end, total)
    }

    fun canAppend(requestedOffset: Long, responseStatus: Int, range: ContentRange?, contentLength: Long,
                  expectedEtag: String, responseEtag: String, expectedLastModified: String, responseLastModified: String): Boolean {
        if (requestedOffset <= 0L || responseStatus != 206 || range == null || range.start != requestedOffset) return false
        if (contentLength >= 0L && contentLength != range.length) return false
        // If-Range requires a strong ETag. A weak validator (W/...) only
        // identifies semantically equivalent representations and is not safe
        // for byte-for-byte resume. Fall back to Last-Modified when available.
        val expectedStrongEtag = expectedEtag.takeIf {
            it.isNotBlank() && !it.trimStart().startsWith("W/", ignoreCase = true)
        }
        val responseStrongEtag = responseEtag.takeIf {
            it.isNotBlank() && !it.trimStart().startsWith("W/", ignoreCase = true)
        }
        val validatorMatches = when {
            expectedStrongEtag != null ->
                responseStrongEtag != null && responseStrongEtag == expectedStrongEtag
            expectedLastModified.isNotBlank() ->
                responseLastModified.isNotBlank() && responseLastModified == expectedLastModified
            else -> false
        }
        return validatorMatches
    }
}