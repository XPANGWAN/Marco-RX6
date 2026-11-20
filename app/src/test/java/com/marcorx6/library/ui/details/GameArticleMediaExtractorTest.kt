package com.marcorx6.library.ui.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameArticleMediaExtractorTest {
    @Test fun extractsMultipleLazyImagesAndSkipsCoverDuplicates() {
        val html = """<img src="https://blogger.googleusercontent.com/img/s320/cover.jpg" width="640" height="480"><img data-src="https://blogger.googleusercontent.com/img/s960/shot1.jpg" width="800"><img data-lazy-src="https://cdn.example.com/game/shot2.jpg"><img src="https://cdn.example.com/icon.png" width="32" height="32">"""
        val result = GameArticleMediaExtractor.extract(html, "https://blogger.googleusercontent.com/img/s747/cover.jpg")
        assertEquals(2, result.images.size)
        assertTrue(result.images.any { it.contains("shot1.jpg") })
        assertTrue(result.images.any { it.contains("shot2.jpg") })
    }

    @Test fun extractsSupportedYoutubeUrlForms() {
        listOf(
            """<iframe src="https://www.youtube.com/embed/AbCdEfGhI12"></iframe>""",
            """<a href="https://youtu.be/AbCdEfGhI12">watch</a>""",
            """<a href="https://youtube.com/watch?v=AbCdEfGhI12">watch</a>""",
            """<iframe src="https://www.youtube-nocookie.com/embed/AbCdEfGhI12"></iframe>"""
        ).forEach { assertEquals("AbCdEfGhI12", GameArticleMediaExtractor.extractYoutubeVideoId(it)) }
    }

    @Test fun rejectsUntrustedAndMalformedVideoLinks() {
        assertNull(GameArticleMediaExtractor.extractYoutubeVideoId("""<iframe src="https://evil.example/embed/AbCdEfGhI12"></iframe>"""))
        assertNull(GameArticleMediaExtractor.extractYoutubeVideoId("""<a href="https://youtube.com/watch?v=short">x</a>"""))
        assertNull(GameArticleMediaExtractor.extractYoutubeVideoId("""<a href="javascript:alert(1)">x</a>"""))
    }

    @Test fun parsesDirectYoutubeUrlsAndRejectsLookalikeHosts() {
        assertEquals("AbCdEfGhI12", GameArticleMediaExtractor.parseYoutubeVideoId("https://youtu.be/AbCdEfGhI12"))
        assertEquals("AbCdEfGhI12", GameArticleMediaExtractor.parseYoutubeVideoId("https://www.youtube.com/watch?v=AbCdEfGhI12"))
        assertNull(GameArticleMediaExtractor.parseYoutubeVideoId("https://youtube.com.evil.example/watch?v=AbCdEfGhI12"))
        assertNull(GameArticleMediaExtractor.parseYoutubeVideoId("https://youtube-nocookie.com.evil.example/embed/AbCdEfGhI12"))
    }

    @Test fun returnsNoMediaWhenArticleHasNoMedia() {
        val result = GameArticleMediaExtractor.extract("<p>No images or video here</p>")
        assertTrue(result.images.isEmpty())
        assertNull(result.youtubeVideoId)
    }
}
