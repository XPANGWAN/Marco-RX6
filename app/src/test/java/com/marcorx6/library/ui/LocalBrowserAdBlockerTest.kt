package com.marcorx6.library.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalBrowserAdBlockerTest {
    @Before
    fun installRepresentativeFilterRules() {
        val rules = listOf(
            "||adserver.example^" + '$' + "script,third-party",
            "||cdn.example.net/pagead/ads",
            "@@||safe.adserver.example/allowed.js" + '$' + "script,domain=example.org",
            "example.org##.advertisement",
            "example.org##.sponsor-box",
            "example.org#@#.sponsor-box"
        ).joinToString("\n")
        LocalBrowserAdBlocker.loadRulesForTest(rules)
    }

    private fun blocks(
        url: String,
        page: String = "https://example.org/download/game",
        enabled: Boolean = true,
        allow: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        direct: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        mainFrame: Boolean = false
    ) = LocalBrowserAdBlocker.shouldBlockRequest(
        url = url,
        pageUrl = page,
        enabled = enabled,
        allowedHosts = allow,
        blockedHosts = blocked,
        isDirectFile = { direct },
        requestHeaders = headers,
        isMainFrame = mainFrame
    )

    @Test
    fun blocksKnownAdNetworksAndSubdomains() {
        assertTrue(blocks("https://securepubads.g.doubleclick.net/pagead/ads"))
        assertTrue(blocks("https://x.popads.net/script.js"))
    }

    @Test
    fun parsesThirdPartyScriptRulesAndAllowsNormalCdn() {
        assertTrue(
            blocks(
                "https://cdn.adserver.example/script.js",
                headers = mapOf("Sec-Fetch-Dest" to "script")
            )
        )
        assertFalse(
            blocks(
                "https://cdn.jsdelivr.net/npm/example/app.js",
                headers = mapOf("Sec-Fetch-Dest" to "script")
            )
        )
    }

    @Test
    fun respectsFilterExceptionsAndDomainOptions() {
        assertFalse(
            blocks(
                "https://safe.adserver.example/allowed.js",
                headers = mapOf("Sec-Fetch-Dest" to "script")
            )
        )
        assertTrue(
            blocks(
                "https://safe.adserver.example/allowed.js",
                page = "https://other-site.example/",
                headers = mapOf("Sec-Fetch-Dest" to "script")
            )
        )
    }

    @Test
    fun blocksExplicitAdEndpointsWithoutBreakingDownloadRoutes() {
        assertTrue(blocks("https://cdn.example.net/pagead/ads"))
        assertFalse(blocks("https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1."))
        assertFalse(blocks("https://files.example.net/download/game.zip"))
    }

    @Test
    fun allowlistAndDirectFilesTakePrecedence() {
        assertFalse(blocks("https://ads.example.com/banner.js", allow = setOf("example.com")))
        assertFalse(blocks("https://doubleclick.net/ads.js", direct = true))
    }

    @Test
    fun userBlockedSitesIncludeSubdomainsAndOverrideDirectFileExemption() {
        assertTrue(
            blocks(
                "https://downloads.example.net/game.zip",
                blocked = setOf("example.net"),
                direct = true
            )
        )
        assertTrue(
            blocks(
                "https://shop.example.net/page",
                blocked = setOf("example.net"),
                mainFrame = true
            )
        )
        assertFalse(
            blocks(
                "https://example.net/page",
                blocked = setOf("example.net"),
                allow = setOf("example.net")
            )
        )
    }

    @Test
    fun disabledBlockerAndNormalTopLevelPagesAreAllowed() {
        assertFalse(blocks("https://doubleclick.net/ads.js", enabled = false))
        assertFalse(blocks("https://news.example.net/article", mainFrame = true))
    }

    @Test
    fun blocksKnownAdNetworkTopLevelRedirects() {
        assertTrue(blocks("https://popads.net/redirect", mainFrame = true))
    }

    @Test
    fun blocksRefpaAffiliateRedirectAndSubdomains() {
        assertTrue(
            blocks(
                "https://refpa-78460.com/L?tag=d_6341606m_1599c_",
                mainFrame = true
            )
        )
        assertTrue(
            blocks(
                "https://track.refpa-78460.com/L?click_id=test",
                mainFrame = true
            )
        )
        assertFalse(
            blocks(
                "https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1.",
                mainFrame = true
            )
        )
    }

    @Test
    fun compilesCosmeticSelectorsAndHonorsCosmeticExceptions() {
        val script = LocalBrowserAdBlocker.buildCosmeticScript("https://example.org/page")
        assertTrue(script.contains(".advertisement"))
        assertFalse(script.contains(".sponsor-box"))
    }
}
