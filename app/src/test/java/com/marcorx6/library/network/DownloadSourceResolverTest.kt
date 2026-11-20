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
    fun recognizesVikingFileLandingPagesWithoutMatchingOtherPaths() {
        assertEquals(
            true,
            DownloadSourceResolver.isKnownLandingPageUrl("https://vik1ngfile.site/f/AFklphNAHo")
        )
        assertEquals(
            true,
            DownloadSourceResolver.isKnownLandingPageUrl("https://vikingfile.com/f/AFklphNAHo")
        )
        assertEquals(
            false,
            DownloadSourceResolver.isKnownLandingPageUrl("https://vikingfile.com/")
        )
        assertEquals(
            false,
            DownloadSourceResolver.isKnownLandingPageUrl("https://example.com/f/AFklphNAHo")
        )
    }

    @Test
    fun recognizesMediaFireSharePagesAsBrowserLandingPagesNotDirectFiles() {
        val shareUrls = listOf(
            "https://www.mediafire.com/file/abc123/game.zip/file",
            "https://mediafire.com/file/abc123/game.pkg/file",
            "https://www.mediafire.com/file/abc123",
            "https://www.mediafire.com/download/abc123/game.iso"
        )
        shareUrls.forEach { url ->
            assertEquals(true, DownloadSourceResolver.isKnownLandingPageUrl(url))
            assertEquals(false, DownloadSourceResolver.isDirectDownloadEndpointUrl(url))
        }
    }

    @Test
    fun keepsMediaFireCdnFileEndpointsDirect() {
        assertEquals(
            true,
            DownloadSourceResolver.isDirectDownloadEndpointUrl(
                "https://download123.mediafire.com/token/game.pkg"
            )
        )
        assertEquals(
            false,
            DownloadSourceResolver.isKnownLandingPageUrl(
                "https://download123.mediafire.com/token/game.pkg"
            )
        )
    }

    @Test
    fun recognizesRootzShareLinksAsBrowserLandingPages() {
        assertEquals(
            true,
            DownloadSourceResolver.isKnownLandingPageUrl("https://www.rootz.so/d/1Kg8fY")
        )
        assertEquals(
            true,
            DownloadSourceResolver.isKnownLandingPageUrl("https://rootz.so/d/anotherId/")
        )
        assertEquals(
            false,
            DownloadSourceResolver.isKnownLandingPageUrl("https://rootz.so/")
        )
        assertEquals(
            false,
            DownloadSourceResolver.isKnownLandingPageUrl("https://rootz.so/d/1Kg8fY/file.zip")
        )
    }

    @Test
    fun recognizesLinkVaultCodePagesAsBrowserLandingPages() {
        val urls = listOf(
            "https://vault3.link-vault.org/c/a2JO2hqs",
            "https://link-vault.org/c/anotherCode/",
            "https://vault9.link-vault.org/c/ABC123"
        )
        urls.forEach { url ->
            assertEquals(true, DownloadSourceResolver.isKnownLandingPageUrl(url))
            assertEquals(false, DownloadSourceResolver.isDirectDownloadEndpointUrl(url))
        }
        assertEquals(
            false,
            DownloadSourceResolver.isKnownLandingPageUrl(
                "https://vault3.link-vault.org/c/a2JO2hqs/game.pkg"
            )
        )
    }

    @Test
    fun treatsUnknownOpaqueHttpsUrlsAsBrowserPagesWithoutHostAllowlist() {
        val links = listOf(
            "https://vault3.link-vault.org/c/a2JO2hqs",
            "https://fileshare.example.net/s/AbC123",
            "https://redirect.example.org/r/token-987",
            "https://download-gateway.example.com/go?id=abc"
        )
        links.forEach { url ->
            assertEquals(true, DownloadSourceResolver.shouldOpenInBrowserBeforeProbe(url))
        }
    }

    @Test
    fun keepsExplicitFileEndpointsOnTheDirectDownloadPath() {
        assertEquals(
            false,
            DownloadSourceResolver.shouldOpenInBrowserBeforeProbe(
                "https://cdn.example.net/files/game.pkg?token=abc"
            )
        )
        assertEquals(
            false,
            DownloadSourceResolver.shouldOpenInBrowserBeforeProbe(
                "https://example.net/download?id=123"
            )
        )
        assertEquals(
            false,
            DownloadSourceResolver.shouldOpenInBrowserBeforeProbe(
                "https://example.net/share/123",
                hintedMimeType = "application/octet-stream"
            )
        )
        assertEquals(
            false,
            DownloadSourceResolver.shouldOpenInBrowserBeforeProbe(
                "https://example.net/get",
                hintedDisposition = "attachment; filename=game.zip"
            )
        )
    }

    @Test
    fun preservesDirectNoPayStationPackageEndpoints() {
        val url = "https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1.06/pkg?version=1.01"
        assertEquals(url, DownloadSourceResolver.normalizeKnownProviderUrl(url))
        assertEquals(true, DownloadSourceResolver.isDirectDownloadEndpointUrl(url))
    }

    @Test
    fun recognizesGenericShareLandingPagesButNotDirectFileUrls() {
        assertEquals(false, DownloadSourceResolver.isKnownLandingPageUrl("https://files.example.org/f/AFklphNAHo"))
        assertEquals(false, DownloadSourceResolver.isKnownLandingPageUrl("https://files.example.org/download/game.pkg"))
        assertEquals(true, DownloadSourceResolver.isDirectDownloadEndpointUrl("https://cdn.example.org/files/game.pkg?token=abc"))
    }

    @Test
    fun recognizesMoreGenericGameAndArchiveFileEndpoints() {
        listOf(
            "https://cdn.example.org/game.3ds",
            "https://cdn.example.org/patch.tar.gz",
            "https://cdn.example.org/rom.nes",
            "https://cdn.example.org/installer.exe",
            "https://cdn.example.org/game.torrent"
        ).forEach { url ->
            assertEquals(true, DownloadSourceResolver.isDirectDownloadEndpointUrl(url))
        }
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
    fun extractsGoogleDriveLargeFileConfirmationForm() {
        val html = """<form action="https://drive.usercontent.google.com/download" method="get">
            <input type="hidden" name="id" value="1AbcDEF_234567890">
            <input type="hidden" name="confirm" value="t">
        </form>"""
        assertEquals(
            "https://drive.usercontent.google.com/download?export=download&id=1AbcDEF_234567890&confirm=t",
            DownloadSourceResolver.extractDownloadUrl("https://drive.google.com/uc?export=download&id=1AbcDEF_234567890", html)
        )
    }

    @Test
    fun extractsGenericDirectFileLinkAndResolvesRelativeUrl() {
        val html = """<html><a href="../../files/patch.zip?token=abc">Download patch</a></html>"""
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
