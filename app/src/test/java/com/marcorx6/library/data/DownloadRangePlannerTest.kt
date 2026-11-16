package com.marcorx6.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRangePlannerTest {
    @Test fun partitionsWholeFileWithoutGapsOrOverlap() {
        val total = 10_000_003L
        val ranges = DownloadRangePlanner.create(total, 4, 4L)!!
        assertEquals(4, ranges.size)
        assertEquals(0L, ranges.first().start)
        assertEquals(total - 1L, ranges.last().endInclusive)
        assertEquals(total, ranges.sumOf { it.length })
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endInclusive + 1L, right.start)
        }
    }

    @Test fun rejectsFilesBelowThresholdAndInvalidSegmentCount() {
        assertNull(DownloadRangePlanner.create(3_999_999L, 4, 4_000_000L))
        assertNull(DownloadRangePlanner.create(5_000_000L, 1, 4_000_000L))
    }

    @Test fun resumeStartsAtFirstUncommittedByte() {
        val range = DownloadRangePlanner.create(8_000_000L, 4, 4L)!!.first()
        val remaining = DownloadRangePlanner.remaining(range, 1234L)!!
        assertEquals(range.start + 1234L, remaining.start)
        assertEquals(range.endInclusive, remaining.endInclusive)
        assertEquals(range.length - 1234L, remaining.length)
    }

    @Test fun rejectsInvalidResumeCheckpoint() {
        val range = DownloadRangePlanner.create(8_000_000L, 4, 4L)!!.first()
        assertNull(DownloadRangePlanner.remaining(range, -1L))
        assertNull(DownloadRangePlanner.remaining(range, range.length + 1L))
        assertNull(DownloadRangePlanner.remaining(range, range.length))
    }

    @Test fun supportsLongFilesWithoutIntSizeConversion() {
        val total = 300L * 1024L * 1024L * 1024L
        val ranges = DownloadRangePlanner.create(total, 4, 4L)!!
        assertEquals(total, ranges.sumOf { it.length })
        assertTrue(ranges.all { it.length > 0L })
    }
}
