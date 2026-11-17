package com.marcorx6.library.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalBrowserAdBlockerTest {
    @Before fun installRepresentativeFilterRules() {
        LocalBrowserAdBlocker.loadRulesForTest(
            """
            ||adserver.example^${'
            ||cdn.example.net/pagead/ads
            @@||safe.adserver.example/allowed.js${'
            example.org##.advertisement
            example.org##.sponsor-box
            example.org#@#.sponsor-box
            """.trimIndent()
        )
    }

    private fun blocks(
        url: String,
        page: String = "https://example.org/download/game",
        enabled: Boolean = true,
        allow: Set<String> = emptySet(),
        direct: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        mainFrame: Boolean = false
    ) = LocalBrowserAdBlocker.shouldBlockRequest(
        url = url,
        pageUrl = page,
        enabled = enabled,
        allowedHosts = allow,
        isDirectFile = { direct },
        requestHeaders = headers,
        isMainFrame = mainFrame
    )

    @Test fun blocksKnownAdNetworksAndSubdomains() {
        assertTrue(blocks("https://securepubads.g.doubleclick.net/pagead/ads"))
        assertTrue(blocks("https://x.popads.net/script.js"))
    }

    @Test fun parsesThirdPartyScriptRulesAndAllowsNormalCdn() {
        assertTrue(blocks(
            "https://cdn.adserver.example/script.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertFalse(blocks(
            "https://cdn.jsdelivr.net/npm/example/app.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun respectsFilterExceptionsAndDomainOptions() {
        assertFalse(blocks(
            "https://safe.adserver.example/allowed.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertTrue(blocks(
            "https://safe.adserver.example/allowed.js",
            page = "https://other-site.example/",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun blocksExplicitAdEndpointsWithoutBreakingDownloadRoutes() {
        assertTrue(blocks("https://cdn.example.net/pagead/ads"))
        assertFalse(blocks("https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1."))
        assertFalse(blocks("https://files.example.net/download/game.zip"))
    }

    @Test fun allowlistAndDirectFilesTakePrecedence() {
        assertFalse(blocks("https://ads.example.com/banner.js", allow = setOf("example.com")))
        assertFalse(blocks("https://doubleclick.net/ads.js", direct = true))
    }

    @Test fun disabledBlockerAndNormalTopLevelPagesAreAllowed() {
        assertFalse(blocks("https://doubleclick.net/ads.js", enabled = false))
        assertFalse(blocks("https://news.example.net/article", mainFrame = true))
    }

    @Test fun blocksKnownAdNetworkTopLevelRedirects() {
        assertTrue(blocks("https://popads.net/redirect", mainFrame = true))
    }

    @Test fun compilesCosmeticSelectorsAndHonorsCosmeticExceptions() {
        val script = LocalBrowserAdBlocker.buildCosmeticScript("https://example.org/page")
        assertTrue(script.contains(".advertisement"))
        assertFalse(script.contains(".sponsor-box"))
    }
}
}script,third-party
            ||cdn.example.net/pagead/ads
            @@||safe.adserver.example/allowed.js$script,domain=example.org
            example.org##.advertisement
            example.org##.sponsor-box
            example.org#@#.sponsor-box
            """.trimIndent()
        )
    }

    private fun blocks(
        url: String,
        page: String = "https://example.org/download/game",
        enabled: Boolean = true,
        allow: Set<String> = emptySet(),
        direct: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        mainFrame: Boolean = false
    ) = LocalBrowserAdBlocker.shouldBlockRequest(
        url = url,
        pageUrl = page,
        enabled = enabled,
        allowedHosts = allow,
        isDirectFile = { direct },
        requestHeaders = headers,
        isMainFrame = mainFrame
    )

    @Test fun blocksKnownAdNetworksAndSubdomains() {
        assertTrue(blocks("https://securepubads.g.doubleclick.net/pagead/ads"))
        assertTrue(blocks("https://x.popads.net/script.js"))
    }

    @Test fun parsesThirdPartyScriptRulesAndAllowsNormalCdn() {
        assertTrue(blocks(
            "https://cdn.adserver.example/script.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertFalse(blocks(
            "https://cdn.jsdelivr.net/npm/example/app.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun respectsFilterExceptionsAndDomainOptions() {
        assertFalse(blocks(
            "https://safe.adserver.example/allowed.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertTrue(blocks(
            "https://safe.adserver.example/allowed.js",
            page = "https://other-site.example/",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun blocksExplicitAdEndpointsWithoutBreakingDownloadRoutes() {
        assertTrue(blocks("https://cdn.example.net/pagead/ads"))
        assertFalse(blocks("https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1."))
        assertFalse(blocks("https://files.example.net/download/game.zip"))
    }

    @Test fun allowlistAndDirectFilesTakePrecedence() {
        assertFalse(blocks("https://ads.example.com/banner.js", allow = setOf("example.com")))
        assertFalse(blocks("https://doubleclick.net/ads.js", direct = true))
    }

    @Test fun disabledBlockerAndNormalTopLevelPagesAreAllowed() {
        assertFalse(blocks("https://doubleclick.net/ads.js", enabled = false))
        assertFalse(blocks("https://news.example.net/article", mainFrame = true))
    }

    @Test fun blocksKnownAdNetworkTopLevelRedirects() {
        assertTrue(blocks("https://popads.net/redirect", mainFrame = true))
    }

    @Test fun compilesCosmeticSelectorsAndHonorsCosmeticExceptions() {
        val script = LocalBrowserAdBlocker.buildCosmeticScript("https://example.org/page")
        assertTrue(script.contains(".advertisement"))
        assertFalse(script.contains(".sponsor-box"))
    }
}
}script,domain=example.org
            example.org##.advertisement
            example.org##.sponsor-box
            example.org#@#.sponsor-box
            """.trimIndent()
        )
    }

    private fun blocks(
        url: String,
        page: String = "https://example.org/download/game",
        enabled: Boolean = true,
        allow: Set<String> = emptySet(),
        direct: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        mainFrame: Boolean = false
    ) = LocalBrowserAdBlocker.shouldBlockRequest(
        url = url,
        pageUrl = page,
        enabled = enabled,
        allowedHosts = allow,
        isDirectFile = { direct },
        requestHeaders = headers,
        isMainFrame = mainFrame
    )

    @Test fun blocksKnownAdNetworksAndSubdomains() {
        assertTrue(blocks("https://securepubads.g.doubleclick.net/pagead/ads"))
        assertTrue(blocks("https://x.popads.net/script.js"))
    }

    @Test fun parsesThirdPartyScriptRulesAndAllowsNormalCdn() {
        assertTrue(blocks(
            "https://cdn.adserver.example/script.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertFalse(blocks(
            "https://cdn.jsdelivr.net/npm/example/app.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun respectsFilterExceptionsAndDomainOptions() {
        assertFalse(blocks(
            "https://safe.adserver.example/allowed.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertTrue(blocks(
            "https://safe.adserver.example/allowed.js",
            page = "https://other-site.example/",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun blocksExplicitAdEndpointsWithoutBreakingDownloadRoutes() {
        assertTrue(blocks("https://cdn.example.net/pagead/ads"))
        assertFalse(blocks("https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1."))
        assertFalse(blocks("https://files.example.net/download/game.zip"))
    }

    @Test fun allowlistAndDirectFilesTakePrecedence() {
        assertFalse(blocks("https://ads.example.com/banner.js", allow = setOf("example.com")))
        assertFalse(blocks("https://doubleclick.net/ads.js", direct = true))
    }

    @Test fun disabledBlockerAndNormalTopLevelPagesAreAllowed() {
        assertFalse(blocks("https://doubleclick.net/ads.js", enabled = false))
        assertFalse(blocks("https://news.example.net/article", mainFrame = true))
    }

    @Test fun blocksKnownAdNetworkTopLevelRedirects() {
        assertTrue(blocks("https://popads.net/redirect", mainFrame = true))
    }

    @Test fun compilesCosmeticSelectorsAndHonorsCosmeticExceptions() {
        val script = LocalBrowserAdBlocker.buildCosmeticScript("https://example.org/page")
        assertTrue(script.contains(".advertisement"))
        assertFalse(script.contains(".sponsor-box"))
    }
}
}script,third-party
            ||cdn.example.net/pagead/ads
            @@||safe.adserver.example/allowed.js$script,domain=example.org
            example.org##.advertisement
            example.org##.sponsor-box
            example.org#@#.sponsor-box
            """.trimIndent()
        )
    }

    private fun blocks(
        url: String,
        page: String = "https://example.org/download/game",
        enabled: Boolean = true,
        allow: Set<String> = emptySet(),
        direct: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        mainFrame: Boolean = false
    ) = LocalBrowserAdBlocker.shouldBlockRequest(
        url = url,
        pageUrl = page,
        enabled = enabled,
        allowedHosts = allow,
        isDirectFile = { direct },
        requestHeaders = headers,
        isMainFrame = mainFrame
    )

    @Test fun blocksKnownAdNetworksAndSubdomains() {
        assertTrue(blocks("https://securepubads.g.doubleclick.net/pagead/ads"))
        assertTrue(blocks("https://x.popads.net/script.js"))
    }

    @Test fun parsesThirdPartyScriptRulesAndAllowsNormalCdn() {
        assertTrue(blocks(
            "https://cdn.adserver.example/script.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertFalse(blocks(
            "https://cdn.jsdelivr.net/npm/example/app.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun respectsFilterExceptionsAndDomainOptions() {
        assertFalse(blocks(
            "https://safe.adserver.example/allowed.js",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
        assertTrue(blocks(
            "https://safe.adserver.example/allowed.js",
            page = "https://other-site.example/",
            headers = mapOf("Sec-Fetch-Dest" to "script")
        ))
    }

    @Test fun blocksExplicitAdEndpointsWithoutBreakingDownloadRoutes() {
        assertTrue(blocks("https://cdn.example.net/pagead/ads"))
        assertFalse(blocks("https://nopaystation.com/get/PS3/NPEB00882/GTAIVDIGITAL0001/1."))
        assertFalse(blocks("https://files.example.net/download/game.zip"))
    }

    @Test fun allowlistAndDirectFilesTakePrecedence() {
        assertFalse(blocks("https://ads.example.com/banner.js", allow = setOf("example.com")))
        assertFalse(blocks("https://doubleclick.net/ads.js", direct = true))
    }

    @Test fun disabledBlockerAndNormalTopLevelPagesAreAllowed() {
        assertFalse(blocks("https://doubleclick.net/ads.js", enabled = false))
        assertFalse(blocks("https://news.example.net/article", mainFrame = true))
    }

    @Test fun blocksKnownAdNetworkTopLevelRedirects() {
        assertTrue(blocks("https://popads.net/redirect", mainFrame = true))
    }

    @Test fun compilesCosmeticSelectorsAndHonorsCosmeticExceptions() {
        val script = LocalBrowserAdBlocker.buildCosmeticScript("https://example.org/page")
        assertTrue(script.contains(".advertisement"))
        assertFalse(script.contains(".sponsor-box"))
    }
}
