package com.marcorx6.library.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureUrlPolicyTest {

    @Test
    fun upgradesHttpToHttpsWithoutChangingTheRemainingUrl() {
        assertEquals(
            "https://example.com/path?q=1",
            SecureUrlPolicy.toHttpsUrl("http://example.com/path?q=1")
        )
    }

    @Test
    fun preservesAlreadySecureUrl() {
        val url = "HTTPS://example.com/image.jpg"
        assertEquals(url, SecureUrlPolicy.toHttpsUrl(url))
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertNull(SecureUrlPolicy.toHttpsUrl("file:///tmp/image.jpg"))
        assertNull(SecureUrlPolicy.toHttpsUrl("javascript:alert(1)"))
    }

    @Test
    fun upgradeFallbackOnlyTrimsUnsupportedInput() {
        assertEquals("file:///tmp/image.jpg", SecureUrlPolicy.upgradeToHttps(" file:///tmp/image.jpg "))
    }
    @Test
    fun permitsHttpOnlyForExactSonyCdnHosts() {
        assertEquals(true, SecureUrlPolicy.isAllowedDownloadUrl("http://zeus.dl.playstation.net/pkg/file.pkg"))
        assertEquals(true, SecureUrlPolicy.isAllowedDownloadUrl("http://ares.dl.playstation.net/pkg/file.pkg"))
        assertEquals(false, SecureUrlPolicy.isAllowedDownloadUrl("http://example.com/file.pkg"))
        assertEquals(false, SecureUrlPolicy.isAllowedDownloadUrl("http://zeus.dl.playstation.net.example.com/file.pkg"))
    }

    @Test
    fun permitsHttpsAndRejectsUntrustedOrMalformedRedirectTargets() {
        assertEquals(true, SecureUrlPolicy.isAllowedDownloadUrl("https://example.com/file.pkg"))
        assertEquals(false, SecureUrlPolicy.isAllowedDownloadUrl("file:///tmp/file.pkg"))
        assertEquals(false, SecureUrlPolicy.isAllowedDownloadUrl("http://user@zeus.dl.playstation.net/file.pkg"))
    }

}
