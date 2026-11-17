package com.marcorx6.library.ui

import android.content.SharedPreferences
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Download-browser content blocker.
 *
 * Supports the practical core of Adblock Plus filter syntax:
 * network rules, exception rules, resource types, third-party rules,
 * domain restrictions and cosmetic selectors. Lists are cached locally
 * and refreshed in the background; no browsing request waits for a list update.
 */
internal object LocalBrowserAdBlocker {
    private const val PREF_RULES = "download_adblock_filter_rules_v2"
    private const val PREF_UPDATED = "download_adblock_filter_updated_v2"
    private const val MAX_RULES = 100_000
    private const val MAX_LIST_BYTES = 5 * 1024 * 1024
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Marco-AdBlock-Update").apply { isDaemon = true }
    }
    private val refreshing = AtomicBoolean(false)

    private val listUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt"
    )

    private val builtInDomains = setOf(
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
        "betweendigital.com", "bluekai.com", "rlcdn.com", "adnxs-simple.com",
        "scorecardresearch.com", "hotjar.com", "mouseflow.com", "fullstory.com",
        "clarity.ms", "analytics.yahoo.com"
    )

    private data class NetworkRule(
        val rawPattern: String,
        val regex: Regex?,
        val hostSuffix: String?,
        val options: RuleOptions,
        val exception: Boolean
    )

    private data class RuleOptions(
        val types: Set<String> = emptySet(),
        val excludedTypes: Set<String> = emptySet(),
        val thirdParty: Boolean? = null,
        val includeDomains: Set<String> = emptySet(),
        val excludeDomains: Set<String> = emptySet()
    )

    private data class CosmeticRule(
        val domains: Set<String>,
        val excludedDomains: Set<String>,
        val selector: String,
        val exception: Boolean
    )

    private data class RuleSnapshot(
        val networkRules: List<NetworkRule>,
        val hostRules: Map<String, List<NetworkRule>>,
        val genericRules: List<NetworkRule>,
        val cosmeticRules: List<CosmeticRule>,
        val loadedAt: Long
    )

    @Volatile private var snapshot = RuleSnapshot(emptyList(), emptyMap(), emptyList(), emptyList(), 0L)

    fun loadCached(preferences: SharedPreferences, onLoaded: (() -> Unit)? = null) {
        // Parsing a large filter list must never run on the UI thread.
        executor.execute {
            val raw = preferences.getString(PREF_RULES, null)
            if (!raw.isNullOrBlank()) snapshot = parseLists(raw)
            onLoaded?.invoke()
        }
    }

    fun refreshInBackground(
        preferences: SharedPreferences,
        force: Boolean = false,
        onUpdated: (() -> Unit)? = null
    ) {
        val lastUpdate = preferences.getLong(PREF_UPDATED, 0L)
        if (!force && System.currentTimeMillis() - lastUpdate < REFRESH_INTERVAL_MS) return
        if (!refreshing.compareAndSet(false, true)) return

        executor.execute {
            try {
                val downloaded = StringBuilder()
                for (listUrl in listUrls) {
                    val body = downloadList(listUrl) ?: continue
                    if (downloaded.length + body.length > MAX_LIST_BYTES * 2) break
                    downloaded.append("\n").append(body)
                }
                if (downloaded.isNotEmpty()) {
                    val content = downloaded.toString()
                    val parsed = parseLists(content)
                    if (parsed.networkRules.isNotEmpty()) {
                        snapshot = parsed
                        preferences.edit()
                            .putString(PREF_RULES, content)
                            .putLong(PREF_UPDATED, System.currentTimeMillis())
                            .apply()
                        onUpdated?.invoke()
                    }
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

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
        val target = parseUrl(url) ?: return false
        if (target.scheme != "http" && target.scheme != "https") return false
        val host = normalizeHost(target.host ?: return false) ?: return false
        if (allowedHosts.any { domainMatches(host, normalizeHost(it) ?: it) }) return false

        val page = parseUrl(pageUrl)
        val pageHost = page?.host?.let(::normalizeHost).orEmpty()
        val thirdParty = pageHost.isNotBlank() && !sameSite(host, pageHost)
        val type = if (isMainFrame) "document" else resourceType(requestHeaders, target.path)

        val current = snapshot
        val candidates = ArrayList<NetworkRule>()
        var candidateHost = host
        while (candidateHost.contains('.')) {
            current.hostRules[candidateHost]?.let(candidates::addAll)
            candidateHost = candidateHost.substringAfter('.')
        }
        candidates.addAll(current.genericRules)
        val applicable = candidates.asSequence()
            .filter { rule -> ruleApplies(rule.options, host, pageHost, type, thirdParty) }
        if (applicable.any { it.exception && matches(it, url, host) }) return false

        // Known ad-network hosts are a safe fallback before remote lists load.
        if (builtInDomains.any { domainMatches(host, it) }) return true
        if (applicable.any { !it.exception && matches(it, url, host) }) return true

        // Top-level navigations are blocked only by explicit filter rules or known ad domains.
        if (isMainFrame) return false
        return false
    }

    fun buildCosmeticScript(pageUrl: String): String {
        val host = parseUrl(pageUrl)?.host?.let(::normalizeHost).orEmpty()
        if (host.isBlank()) return ""
        val rules = snapshot.cosmeticRules
        val exceptions = rules.asSequence()
            .filter { it.exception && domainApplies(it.domains, it.excludedDomains, host) }
            .map { it.selector }.toSet()
        val fallback = listOf(
            "[id^=google_ads]", "[id*=ad-container]", "[id*=ad_wrapper]",
            "[class*=ad-container]", "[class*=ad-wrapper]", "[class*=advertisement]",
            "[class*=sponsored]", "[data-ad]", "[data-ad-slot]", "ins.adsbygoogle",
            "amp-ad", "iframe[src*=doubleclick]", "iframe[src*=googlesyndication]",
            "iframe[src*=taboola]", "iframe[src*=outbrain]"
        )
        val selectors = (rules.asSequence()
            .filter { !it.exception && domainApplies(it.domains, it.excludedDomains, host) }
            .map { it.selector }
            .filter { it !in exceptions }
            .toList() + fallback)
            .distinct()
            .take(500)
        if (selectors.isEmpty()) return ""
        val json = JSONArray(selectors).toString()
        return """(function(){try{
          var selectors=$json;
          var styleId='marco-rx6-adblock-css';
          var style=document.getElementById(styleId);
          if(!style){style=document.createElement('style');style.id=styleId;(document.head||document.documentElement).appendChild(style);}
          style.textContent=selectors.map(function(s){return s+'{display:none!important;visibility:hidden!important;}' }).join('\\n');
          var hidden=0;
          function mark(root){
            selectors.forEach(function(s){try{
              if(root&&root.nodeType===1&&root.matches&&root.matches(s)&&root.getAttribute('data-marco-ad-hidden')!=='1'){
                root.setAttribute('data-marco-ad-hidden','1');hidden++;
              }
              if(root&&root.querySelectorAll)root.querySelectorAll(s).forEach(function(e){
                if(e.getAttribute('data-marco-ad-hidden')!=='1'){e.setAttribute('data-marco-ad-hidden','1');hidden++;}
              });
            }catch(ignore){}});
          }
          mark(document);
          if(!window.__marcoAdBlockObserver){
            window.__marcoAdBlockObserver=new MutationObserver(function(records){
              records.forEach(function(record){record.addedNodes.forEach(function(node){
                if(node&&node.nodeType===1)mark(node);
              });});
            });
            window.__marcoAdBlockObserver.observe(document.documentElement,{childList:true,subtree:true});
          }
          return hidden;
        }catch(e){}})();"""
    }

    fun parsedNetworkRuleCount(): Int = snapshot.networkRules.size
    fun parsedCosmeticRuleCount(): Int = snapshot.cosmeticRules.size

    internal fun loadRulesForTest(content: String) {
        snapshot = parseLists(content)
    }

    private data class ParsedUrl(val scheme: String, val host: String?, val path: String, val query: String)

    private fun parseUrl(value: String): ParsedUrl? = runCatching {
        val uri = URI(value)
        ParsedUrl(
            uri.scheme?.lowercase(Locale.ROOT).orEmpty(),
            uri.host,
            uri.rawPath.orEmpty(),
            uri.rawQuery.orEmpty()
        )
    }.getOrNull()

    private fun normalizeHost(host: String): String? = runCatching {
        java.net.IDN.toASCII(host.trim().trimEnd('.')).lowercase(Locale.ROOT)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun domainMatches(host: String, rule: String): Boolean =
        host == rule || host.endsWith(".$rule")

    private fun sameSite(host: String, pageHost: String): Boolean =
        host == pageHost || host.endsWith(".$pageHost") || pageHost.endsWith(".$host")

    private fun domainApplies(domains: Set<String>, excluded: Set<String>, host: String): Boolean =
        (domains.isEmpty() || domains.any { domainMatches(host, it) }) &&
            excluded.none { domainMatches(host, it) }

    private fun resourceType(headers: Map<String, String>, path: String): String {
        val destination = headers.entries.firstOrNull {
            it.key.equals("Sec-Fetch-Dest", true)
        }?.value?.lowercase(Locale.ROOT).orEmpty()
        if (destination.isNotBlank()) return when (destination) {
            "frame", "iframe" -> "subdocument"
            "empty" -> "xmlhttprequest"
            else -> destination
        }
        val accept = headers.entries.firstOrNull {
            it.key.equals("Accept", true)
        }?.value?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "javascript" in accept || path.endsWith(".js", true) || path.endsWith(".mjs", true) -> "script"
            "text/css" in accept || path.endsWith(".css", true) -> "stylesheet"
            "image/" in accept -> "image"
            "font/" in accept || path.endsWith(".woff", true) || path.endsWith(".woff2", true) -> "font"
            "video/" in accept || "audio/" in accept -> "media"
            "text/html" in accept -> "document"
            else -> "other"
        }
    }

    private fun ruleApplies(
        options: RuleOptions,
        host: String,
        pageHost: String,
        type: String,
        thirdParty: Boolean
    ): Boolean {
        if (options.types.isNotEmpty() && type !in options.types) return false
        if (type in options.excludedTypes) return false
        if (options.thirdParty != null && options.thirdParty != thirdParty) return false
        if (options.includeDomains.isNotEmpty() &&
            options.includeDomains.none { domainMatches(pageHost, it) }) return false
        if (options.excludeDomains.any { domainMatches(pageHost, it) }) return false
        return true
    }

    private fun matches(rule: NetworkRule, url: String, host: String): Boolean {
        rule.hostSuffix?.let { return domainMatches(host, it) && (rule.regex?.containsMatchIn(url) ?: true) }
        return rule.regex?.containsMatchIn(url) == true
    }

    private fun parseLists(content: String): RuleSnapshot {
        val network = ArrayList<NetworkRule>()
        val cosmetic = ArrayList<CosmeticRule>()
        content.lineSequence().take(MAX_RULES).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") ||
                line.startsWith("@@") && line.contains("#@#")) return@forEach

            val cosmeticExceptionAt = line.indexOf("#@#")
            val cosmeticAt = line.indexOf("##")
            val cosmeticException = cosmeticExceptionAt >= 0
            val cosmeticIndex = if (cosmeticException) cosmeticExceptionAt else cosmeticAt
            if (cosmeticIndex >= 0) {
                val domainPart = line.substring(0, cosmeticIndex)
                val selector = line.substring(cosmeticIndex + if (cosmeticException) 3 else 2).trim()
                if (selector.isNotBlank() && selector.length <= 500 &&
                    !selector.startsWith("+js(") && !selector.startsWith(":-abp-")) {
                    val domainTokens = domainPart.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    val domains = domainTokens.filterNot { it.startsWith("~") }
                        .mapNotNull(::normalizeHost).toSet()
                    val excluded = domainTokens.filter { it.startsWith("~") }
                        .mapNotNull { normalizeHost(it.drop(1)) }.toSet()
                    cosmetic += CosmeticRule(domains, excluded, selector, cosmeticException)
                }
                return@forEach
            }

            val exception = line.startsWith("@@")
            val body = if (exception) line.removePrefix("@@") else line
            val parts = body.split('$', limit = 2)
            val pattern = parts[0].trim()
            if (pattern.isBlank() || pattern.startsWith("/") && pattern.endsWith("/") && pattern.length > 260) return@forEach
            val options = parseOptions(parts.getOrNull(1).orEmpty())
            val hostSuffix = pattern.removePrefix("||").substringBeforeAny('/', '^', '*', '|')
                .takeIf { pattern.startsWith("||") && it.contains('.') }
                ?.let(::normalizeHost)
            val regex = compilePattern(pattern)
            if (regex != null || hostSuffix != null) {
                network += NetworkRule(pattern, regex, hostSuffix, options, exception)
            }
        }
        val hostIndex = network.filter { it.hostSuffix != null }.groupBy { it.hostSuffix!! }
        val generic = network.filter { it.hostSuffix == null }.take(12_000)
        return RuleSnapshot(network, hostIndex, generic, cosmetic, System.currentTimeMillis())
    }

    private fun String.substringBeforeAny(vararg chars: Char): String {
        val index = indexOfAny(chars)
        return if (index < 0) this else substring(0, index)
    }

    private fun parseOptions(raw: String): RuleOptions {
        val types = linkedSetOf<String>()
        val excluded = linkedSetOf<String>()
        val includeDomains = linkedSetOf<String>()
        val excludeDomains = linkedSetOf<String>()
        var thirdParty: Boolean? = null
        raw.split(',').forEach { optionRaw ->
            val option = optionRaw.trim().lowercase(Locale.ROOT)
            when (option) {
                "script", "image", "stylesheet", "object", "xmlhttprequest", "subdocument",
                "document", "font", "media", "websocket", "ping", "other" -> types += option
                "~script", "~image", "~stylesheet", "~object", "~xmlhttprequest", "~subdocument",
                "~document", "~font", "~media", "~websocket", "~ping", "~other" -> excluded += option.drop(1)
                "third-party" -> thirdParty = true
                "~third-party" -> thirdParty = false
                else -> if (option.startsWith("domain=")) {
                    option.removePrefix("domain=").split('|').forEach { domain ->
                        val clean = domain.trim()
                        if (clean.startsWith("~")) normalizeHost(clean.drop(1))?.let(excludeDomains::add)
                        else normalizeHost(clean)?.let(includeDomains::add)
                    }
                }
            }
        }
        return RuleOptions(types, excluded, thirdParty, includeDomains, excludeDomains)
    }

    private fun compilePattern(pattern: String): Regex? = runCatching {
        if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length > 2) {
            Regex(pattern.substring(1, pattern.length - 1), RegexOption.IGNORE_CASE)
        } else {
            val source = buildString {
                var i = 0
                while (i < pattern.length) {
                    when {
                        pattern.startsWith("||", i) -> { append("^(?:https?:)?//(?:[^/]+\\.)?"); i++ }
                        pattern[i] == '*' -> append(".*")
                        pattern[i] == '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                        pattern[i] == '|' && i == 0 -> append("^")
                        pattern[i] == '|' && i == pattern.lastIndex -> append("$")
                        else -> append(Regex.escape(pattern[i].toString()))
                    }
                    i++
                }
            }
            Regex(source, RegexOption.IGNORE_CASE)
        }
    }.getOrNull()

    private fun downloadList(address: String): String? = runCatching {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MARCO-RX6-AdBlock/1.0")
        connection.setRequestProperty("Accept", "text/plain")
        connection.connect()
        try {
            if (connection.responseCode !in 200..299) return null
            val output = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                var bytes = 0
                while (reader.readLine().also { line = it } != null && bytes < MAX_LIST_BYTES) {
                    val value = line.orEmpty()
                    bytes += value.length + 1
                    output.append(value).append('\n')
                }
            }
            output.toString()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
