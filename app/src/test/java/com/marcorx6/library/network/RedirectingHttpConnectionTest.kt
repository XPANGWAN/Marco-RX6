package com.marcorx6.library.network

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectingHttpConnectionTest {

    @Test
    fun recognizesStandardRedirectStatuses() {
        listOf(301, 302, 303, 307, 308).forEach {
            assertTrue(RedirectingHttpConnection.isRedirectStatus(it))
        }
        listOf(200, 206, 300, 304, 400, 500).forEach {
            assertFalse(RedirectingHttpConnection.isRedirectStatus(it))
        }
    }

    @Test
    fun resolvesRelativeAndAbsoluteRedirectLocations() {
        val origin = URL("https://download.example.com/path/start.pkg")
        assertEquals(
            "https://download.example.com/final.pkg",
            RedirectingHttpConnection.resolveRedirect(origin, "../final.pkg").toExternalForm()
        )
        assertEquals(
            "https://cdn.example.net/game.pkg",
            RedirectingHttpConnection.resolveRedirect(origin, "https://cdn.example.net/game.pkg").toExternalForm()
        )
    }

    @Test(expected = SecurityException::class)
    fun rejectsUntrustedCleartextRedirectTargets() {
        RedirectingHttpConnection.resolveRedirect(
            URL("https://download.example.com/start"),
            "http://untrusted.example.net/file.pkg"
        )
    }

    @Test
    fun permitsExplicitSonyCleartextCdnRedirects() {
        assertEquals(
            "http://zeus.dl.playstation.net/pkg/file.pkg",
            RedirectingHttpConnection.resolveRedirect(
                URL("https://nopaystation.com/start"),
                "http://zeus.dl.playstation.net/pkg/file.pkg"
            ).toExternalForm()
        )
    }
}
