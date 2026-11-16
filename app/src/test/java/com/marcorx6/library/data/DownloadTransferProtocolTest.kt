package com.marcorx6.library.data

import org.junit.Assert.*
import org.junit.Test

class DownloadTransferProtocolTest {
    @Test fun parsesValidRange() {
        val range = DownloadTransferProtocol.parseContentRange("bytes 1048576-2097151/4194304")
        assertNotNull(range); assertEquals(1048576L, range!!.start); assertEquals(2097151L, range.endInclusive)
        assertEquals(4194304L, range.total); assertEquals(1048576L, range.length)
    }
    @Test fun rejectsUnknownOrMalformedTotals() {
        assertNull(DownloadTransferProtocol.parseContentRange("bytes 0-99/*"))
        assertNull(DownloadTransferProtocol.parseContentRange("bytes 0-99/100"))
        assertNull(DownloadTransferProtocol.parseContentRange("bytes 20-10/100"))
        assertNull(DownloadTransferProtocol.parseContentRange("items 0-9/10"))
    }
    @Test fun onlyAppendsVerifiedRangeWithMatchingEtag() {
        val range = DownloadTransferProtocol.parseContentRange("bytes 500-999/1000")
        assertTrue(DownloadTransferProtocol.canAppend(500, 206, range, 500, "\"v1\"", "\"v1\"", "", ""))
        assertFalse(DownloadTransferProtocol.canAppend(500, 206, range, 500, "\"v1\"", "\"v2\"", "", ""))
    }
    @Test fun rejectsIgnoredRangeAndWrongOffset() {
        val range = DownloadTransferProtocol.parseContentRange("bytes 0-499/1000")
        assertFalse(DownloadTransferProtocol.canAppend(500, 200, range, 500, "\"v1\"", "\"v1\"", "", ""))
        assertFalse(DownloadTransferProtocol.canAppend(500, 206, range, 500, "\"v1\"", "\"v1\"", "", ""))
    }
    @Test fun usesLastModifiedWhenEtagIsUnavailable() {
        val range = DownloadTransferProtocol.parseContentRange("bytes 100-199/200")
        assertTrue(DownloadTransferProtocol.canAppend(100, 206, range, 100, "", "", "date", "date"))
        assertFalse(DownloadTransferProtocol.canAppend(100, 206, range, 100, "", "", "old", "new"))
    }
}