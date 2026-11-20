package com.marcorx6.library.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFileNamesTest {
    @Test
    fun parsesRfc5987FilenameWithLanguageAndPreservesLiteralPlus() {
        val result = DownloadFileNames.fromResponse(
            "attachment; filename*=UTF-8'en'Marco+RX6%20%E2%9C%93.zip",
            "https://example.org/download"
        )
        assertEquals("Marco+RX6 ✓.zip", result)
    }

    @Test
    fun parsesQuotedFilenameContainingSpacesAndSemicolon() {
        val result = DownloadFileNames.fromResponse(
            """attachment; filename="Game Collection; Disc 1.iso"""",
            "https://example.org/download"
        )
        assertEquals("Game Collection; Disc 1.iso", result)
    }

    @Test
    fun fallsBackToRegularFilenameWhenExtendedValueIsMalformed() {
        val result = DownloadFileNames.fromResponse(
            "attachment; filename*=UTF-8''%ZZ; filename=backup.zip",
            "https://example.org/download"
        )
        assertEquals("backup.zip", result)
    }
    @Test
    fun addsPkgExtensionFromFinalUrlWhenServerUsesOctetStream() {
        assertEquals(
            "Game.pkg",
            DownloadFileNames.safeName(
                "Game", "application/octet-stream", 1,
                "https://cdn.example.org/download/123.pkg?token=abc"
            )
        )
    }

    @Test
    fun recognizesExtensionOnlyNopaystationPkgPathWithQueryString() {
        assertEquals(
            "GTA IV.pkg",
            DownloadFileNames.safeName(
                "GTA IV", "application/octet-stream", 5,
                "https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1.06/pkg?version=1.01"
            )
        )
    }

    @Test
    fun extensionOnlyEndpointIsNotMistakenForTheFilename() {
        val url = "https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1.06/pkg?version=1.01"
        assertEquals(
            "GTA IV.pkg",
            DownloadFileNames.safeName(
                DownloadFileNames.fromResponse(null, url) ?: "GTA IV",
                "application/octet-stream",
                6,
                url
            )
        )
    }

    @Test
    fun preservesConsoleArchiveAndTorrentExtensionsFromDownloadUrls() {
        assertEquals(
            "Game.3ds",
            DownloadFileNames.safeName(
                "Game", "application/octet-stream", 81,
                "https://cdn.example.org/files/Game.3ds?token=abc"
            )
        )
        assertEquals(
            "Game.torrent",
            DownloadFileNames.safeName(
                "Game", "application/octet-stream", 82,
                "https://cdn.example.org/download/Game.torrent"
            )
        )
    }

    @Test
    fun addsApkExtensionFromMimeWhenTitleHasNoExtension() {
        assertEquals(
            "Marco.apk",
            DownloadFileNames.safeName(
                "Marco", "application/vnd.android.package-archive", 2
            )
        )
    }

    @Test
    fun doesNotDuplicateAnExistingExtension() {
        assertEquals(
            "Game.zip",
            DownloadFileNames.safeName(
                "Game.zip", "application/zip", 3,
                "https://cdn.example.org/Game.zip"
            )
        )
    }

    @Test
    fun responseFilenameWithoutExtensionGetsExtensionFromUrl() {
        assertEquals(
            "download.pkg",
            DownloadFileNames.safeName(
                DownloadFileNames.fromResponse(
                    "attachment; filename=download",
                    "https://cdn.example.org/file.pkg"
                ) ?: "download",
                "application/octet-stream",
                4,
                "https://cdn.example.org/file.pkg"
            )
        )
    }


    @Test
    fun keepsGameTitleWhenServerReturnsContradictoryFilename() {
        assertEquals(
            "Marco.pkg",
            DownloadFileNames.resolvePreferredName(
                "Marco.pkg",
                "Empty.bin",
                "application/octet-stream",
                77,
                "https://cdn.example.org/download?id=123"
            )
        )
    }

    @Test
    fun usesExplicitUrlExtensionWhenServerFilenameIsContradictory() {
        assertEquals(
            "Marco.pkg",
            DownloadFileNames.resolvePreferredName(
                "Marco",
                "Empty.bin",
                "application/octet-stream",
                78,
                "https://cdn.example.org/files/game.pkg?token=abc"
            )
        )
    }

    @Test
    fun acceptsServerFilenameWhenThereIsNoConflictingEvidence() {
        assertEquals(
            "Game.pkg",
            DownloadFileNames.resolvePreferredName(
                "Game",
                "Game.pkg",
                "application/octet-stream",
                79,
                "https://cdn.example.org/download?id=123"
            )
        )
    }
}
