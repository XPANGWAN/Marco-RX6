package com.marcorx6.library.ui

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Offline network filter for the download WebView.
 *
 * The engine combines high-confidence ad/tracker domains, URL-pattern rules,
 * request destination hints and explicit user exceptions. It intentionally
 * avoids broad substring matching so ordinary download routes remain usable.
 */
internal object LocalBrowserAdBlocker {
    private val blockedDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "googletagservices.com", "taboola.com", "outbrain.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
        "trafficjunky.com", "onclickads.net", "juicyads.com", "adsterra.com",
        "adform.net", "adnxs.com", "adsrvr.org", "criteo.com", "casalemedia.com",
        "rubiconproject.com", "openx.net", "pubmatic.com", "sharethrough.com",
        "lijit.com", "media.net", "revcontent.com", "mgid.com", "zemanta.com",
        "smartadserver.com", "yieldmo.com", "bidswitch.net", "advertising.com",
        "contextweb.com", "sovrn.com", "inmobi.com", "applovin.com", "vungle.com",
        "chartboost.com", "ironsrc.com", "adcolony.com", "adsafeprotected.com",
        "adroll.com", "demdex.net", "everesttech.net", "flashtalking.com",
        "mathtag.com", "moatads.com", "quantserve.com", "serving-sys.com",
        "teads.tv", "triplelift.com", "undertone.com", "amazon-adsystem.com",
        "popunder.net", "hilltopads.net", "hilltopads.com", "ad-maven.com",
        "monetag.com", "adcash.com", "yllix.com", "revenuehits.com", "adnow.com",
        "adskeeper.com", "ad-delivery.net", "adkernel.com", "bidvertiser.com",
        "clickadu.com", "clickaine.com", "evadav.com", "onclickmega.com",
        "pushground.com", "rollerads.com", "trafficstars.com", "zeropark.com",
        "plugrush.com", "popmyads.com", "rapidadexchange.com", "usrpubtrck.com",
        "mkfshtuxymriq.space", "popcash.net", "popunder.net",
        "betweendigital.com", "bluekai.com", " casalemedia.com".trim(),
        "rlcdn.com", "lijit.com", "sharethrough.com", "adnxs-simple.com",
        "analytics.yahoo.com", "scorecardresearch.com", "hotjar.com",
        "mouseflow.com", "fullstory.com", "clarity.ms", "quantserve.com"
    )

    private val adPathRules = listOf(
        Regex("""(?:^|/)(?:pagead|adsystem|adserver|adservice|adview|adclick|popunder|clickunder|bannerad|vast|vpaid|adrequest|adserve|adframe|adloader|adscript|adsby|adsense|prebid|gampad)(?:/|[._-]|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|/)(?:advertisement|advertising|sponsored-content|ad-placement)(?:/|[._-]|$)""", RegexOption.IGNORE_CASE),
        Regex("""[?&](?:adunit|adslot|adtag|adzone|adserver|sponsorid|advert|advertising|ad_type|ad_format|ad_id)=([^&]*)""", RegexOption.IGNORE_CASE)
    )

    private val adHostLabels = setOf(
        "ad", "ads", "advert", "advertising", "adserver", "adservice",
        "adnetwork", "adtech", "adtag", "popunder", "clickunder",
        "tracker", "tracking", "pixel", "analytics"
    )

    private val scriptOrFrameTypes = setOf("script", "subdocument", "document", "iframe")
    private val trackingTypes = setOf("image", "xmlhttprequest", "ping", "beacon")

    /**
     * Returns true only when there is a strong ad/tracker signal.
     * The allowlist and direct-file checks always win over generic URL rules.
     */
    fun shouldBlockRequest(
        url: String,
        pageUrl: String,
        enabled: Boolean,
        allowedHosts: Set<String>,
        isDirectFile: (String) -> Boolean,
        requestHeaders: Map<String, String> = emptyMap(),
        isMainFrame: Boolean = false
    ): Boolean {
        if (!enabled || isDirectFile(url)) return false

        val target = parse(url) ?: return false
        if (target.scheme != "http" && target.scheme != "https") return false
        val host = normalizeHost(target.host ?: return false) ?: return false
        if (isAllowed(host, allowedHosts)) return false

        val pageHost = parse(pageUrl)?.host?.let(::normalizeHost)
        val sameSite = pageHost != null && isSameSite(host, pageHost)
        val pathAndQuery = (target.rawPath.orEmpty() + "?" + target.rawQuery.orEmpty())

        // Exact ad-network domains are safe to block regardless of resource type.
        if (blockedDomains.any { matchesDomain(host, it) }) return true

        val explicitAdEndpoint = adPathRules.any { it.containsMatchIn(pathAndQuery) }
        if (explicitAdEndpoint) return true

        val requestType = requestType(requestHeaders, target)
        val adLikeHost = host.split('.').any { it in adHostLabels }

        // Do not block arbitrary top-level websites based on weak name heuristics.
        if (isMainFrame) return false

        // Third-party ad scripts/frames are the most reliable generic signal.
        if (!sameSite && adLikeHost && requestType in scriptOrFrameTypes) return true

        // Known tracking-style hosts are blocked only for tracking-capable requests.
        if (!sameSite && adLikeHost && requestType in trackingTypes) return true

        // Keep first-party site functionality intact unless a high-confidence URL rule matched.
        return false
    }

    private data class ParsedUrl(
        val scheme: String,
        val host: String?,
        val rawPath: String?,
        val rawQuery: String?
    )

    private fun parse(value: String): ParsedUrl? = runCatching {
        val uri = URI(value)
        ParsedUrl(
            scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty(),
            host = uri.host,
            rawPath = uri.rawPath,
            rawQuery = uri.rawQuery
        )
    }.getOrNull()

    private fun requestType(headers: Map<String, String>, target: ParsedUrl): String {
        val destination = headers.entries.firstOrNull {
            it.key.equals("Sec-Fetch-Dest", ignoreCase = true)
        }?.value?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (destination.isNotBlank() && destination != "empty") {
            return if (destination == "frame") "subdocument" else destination
        }

        val accept = headers.entries.firstOrNull {
            it.key.equals("Accept", ignoreCase = true)
        }?.value?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "javascript" in accept || target.rawPath.orEmpty().endsWith(".js", true) ||
                target.rawPath.orEmpty().endsWith(".mjs", true) -> "script"
            "text/css" in accept || target.rawPath.orEmpty().endsWith(".css", true) -> "stylesheet"
            "image/" in accept -> "image"
            "video/" in accept || "audio/" in accept -> "media"
            "text/html" in accept -> "document"
            else -> "other"
        }
    }

    private fun normalizeHost(host: String): String? = runCatching {
        IDN.toASCII(host.trim().trimEnd('.')).lowercase(Locale.ROOT)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun matchesDomain(host: String, rule: String): Boolean =
        host == rule || host.endsWith(".$rule")

    private fun isSameSite(host: String, pageHost: String): Boolean =
        host == pageHost || host.endsWith(".$pageHost") || pageHost.endsWith(".$host")

    private fun isAllowed(host: String, allowedHosts: Set<String>): Boolean =
        allowedHosts.asSequence()
            .mapNotNull(::normalizeHost)
            .any { matchesDomain(host, it) }
}
