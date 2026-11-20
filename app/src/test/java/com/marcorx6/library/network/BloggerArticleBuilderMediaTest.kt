package com.marcorx6.library.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BloggerArticleBuilderMediaTest {
    @Test fun buildsOptionalGalleryAndYoutubeEmbed() {
        val html = BloggerArticleBuilder.build(
            title = "Example Game", categories = "PS3", genre = "", releaseYear = "",
            description = "", coverUrl = "https://lh3.googleusercontent.com/d/cover123",
            downloadUrl = "https://example.com/game.pkg",
            galleryImageUrls = listOf("https://lh3.googleusercontent.com/d/screenshot1", "https://lh3.googleusercontent.com/d/screenshot2"),
            youtubeVideoId = "AbCdEfGhI12"
        )
        assertTrue(html.contains("screenshot1"))
        assertTrue(html.contains("screenshot2"))
        assertTrue(html.contains("youtube-nocookie.com/embed/AbCdEfGhI12"))
    }

    @Test fun omitsOptionalMediaWhenNoneWasProvided() {
        val html = BloggerArticleBuilder.build(
            title = "Example Game", categories = "PS3", genre = "", releaseYear = "",
            description = "", coverUrl = "", downloadUrl = "https://example.com/game.pkg"
        )
        assertFalse(html.contains("marcorx6-gallery"))
        assertFalse(html.contains("youtube-nocookie.com"))
    }

    @Test fun rejectsNonHttpsImagesAndMalformedVideoIds() {
        val html = BloggerArticleBuilder.build(
            title = "Example Game", categories = "", genre = "", releaseYear = "",
            description = "", coverUrl = "", downloadUrl = "",
            galleryImageUrls = listOf("http://example.com/insecure.jpg"), youtubeVideoId = "bad-id"
        )
        assertFalse(html.contains("insecure.jpg"))
        assertFalse(html.contains("youtube-nocookie.com"))
    }
}
