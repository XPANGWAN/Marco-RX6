package com.marcorx6.library.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSourceResolverTest {

    @Test
    fun normalizesGoogleDriveFilePagesToDownloadEndpoint() {
        assertEquals(
            "https://drive.google.com/uc?export=download&id=1AbcDEF_234567890",
            DownloadSourceResolver.normalizeKnownProviderUrl(
                "https://drive.google.com/file/d/1AbcDEF_234567890/view?usp=sharing"
            )
        )
    }

    @Test
    fun preservesNonGoogleDriveSources() {
        val url = "https://www.mediafire.com/file/abc/game.zip/file"
        assertEquals(url, DownloadSourceResolver.normalizeKnownProviderUrl(url))
    }

    @Test
    fun extractsMediaFireDownloadButtonUrl() {
        val html = """<html><a class="input popsok" id="downloadButton" href="https://download123.mediafire.com/token/game.pkg">Download</a></html>"""
        assertEquals(
            "https://download123.mediafire.com/token/game.pkg",
            DownloadSourceResolver.extractDownloadUrl("https://www.mediafire.com/file/abc/game/file", html)
        )
    }

    @Test
    fun extractsGenericDirectFileLinkAndResolvesRelativeUrl() {
        val html = """<html><a href="../files/patch.zip?token=abc">Download patch</a></html>"""
        assertEquals(
            "https://files.example.org/files/patch.zip?token=abc",
            DownloadSourceResolver.extractDownloadUrl("https://files.example.org/share/page/", html)
        )
    }

    @Test
    fun ignoresUnrelatedHtmlLinks() {
        val html = """<a href="/login">Sign in</a><a href="/privacy">Privacy</a>"""
        assertNull(DownloadSourceResolver.extractDownloadUrl("https://example.org/share", html))
    }

    @Test
    fun rejectsInsecureOrCredentialBearingExtractedLinks() {
        val html = """<a href="http://cdn.example.org/game.zip">Download</a>"""
        assertNull(DownloadSourceResolver.extractDownloadUrl("https://example.org/share", html))
        val credentials = """<a href="https://user:pass@cdn.example.org/game.zip">Download</a>"""
        assertNull(DownloadSourceResolver.extractDownloadUrl("https://example.org/share", credentials))
    }
}
