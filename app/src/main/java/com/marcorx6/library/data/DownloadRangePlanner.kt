package com.marcorx6.library.data

/**
 * Produces a gap-free, non-overlapping partition of a known HTTP representation.
 * The plan is immutable for the lifetime of a download so each resume uses the
 * same byte ownership boundaries.
 */
internal object DownloadRangePlanner {
    data class Range(
        val index: Int,
        val start: Long,
        val endInclusive: Long
    ) {
        val length: Long get() = endInclusive - start + 1L
    }

    fun create(
        totalBytes: Long,
        requestedSegments: Int,
        minimumFileSize: Long
    ): List<Range>? {
        if (totalBytes < minimumFileSize || totalBytes <= 0L || requestedSegments < 2) return null
        val count = minOf(requestedSegments, totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (count < 2) return null
        val baseLength = totalBytes / count
        val remainder = totalBytes % count
        var nextStart = 0L
        return List(count) { index ->
            val length = baseLength + if (index.toLong() < remainder) 1L else 0L
            val end = nextStart + length - 1L
            Range(index, nextStart, end).also { nextStart = end + 1L }
        }.also { ranges ->
            check(nextStart == totalBytes)
            check(ranges.zipWithNext().all { (left, right) -> left.endInclusive + 1L == right.start })
        }
    }

    fun remaining(range: Range, completedBytes: Long): Range? {
        if (completedBytes < 0L || completedBytes > range.length) return null
        if (completedBytes == range.length) return null
        val start = range.start + completedBytes
        return Range(range.index, start, range.endInclusive)
    }
}
