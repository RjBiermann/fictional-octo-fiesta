// CloudWish (cloudwish.xyz): Dean-Edwards packed jwplayer with a links object
// {hls2|hls3|hls4} (StreamHG family). Promoted from Mangoporn's provider-local
// Extractors.kt (issue #443) into the shared table — evidence (issue #650, deep
// pass 2026-10-16): Javseen's fresh watch pages carry cloudwish as the primary
// fallback host and no registered adapter answered cloudwish.xyz, so the
// source was dead in-app; the live chains resolved 200 vnd.apple.mpegurl on
// 3/4 probed embeds the same day.
package com.kraptor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Pure parse seam for the CloudWish player (ADR-0005). */
object CloudWishParse {
    // Mangoporn's unpack grammar differs from the shared PackedJs one (keys may
    // be absent, no `.split('|')` tail requirement) — ported as-is to keep the
    // promoted extractor behavior-identical for the provider it shipped on.
    // ARGUMENT EXTRACTION: strict greedy groups, no nested alternation — the
    // original `((?:[^'\\]|\\.)*)` loop recurses per character on real ~16 KB
    // pages and overflows the JVM regex stack (Android's engine is iterative,
    // but the ADR-0005 gates are JVM-run). Real pages carry one pack whose
    // keys argument is terminated by `'.split('|')`.
    private val packRegex = Regex(
        """\}\('(.*)',\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    // absolute hls2 masters or the relative /stream/ (hls4) master — master.txt
    // (hls3) alternates are deliberately not matched, unchanged from the port.
    private val masterRegex =
        Regex("""(https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*|/stream/[^\s"'<>]+master\.m3u8)""")

    /** Standard Dean-Edwards unpack (grammar ported as-is from Mangoporn #443). */
    fun unpack(packedJs: String?): String? {
        if (packedJs == null) return null
        try {
            val m = packRegex.find(packedJs) ?: return null
            val p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            val radix = m.groupValues[2].toInt()
            val c = m.groupValues[3].toInt()
            val k = m.groupValues[4].split("|").toMutableList()
            while (k.size < c) {
                k.add("")
            }
            var result = p
            for (i in (c - 1) downTo 0) {
                if (k[i].isNotEmpty()) {
                    val token = Integer.toString(i, radix)
                    result = result.replace("\\b$token\\b".toRegex(RegexOption.IGNORE_CASE), k[i])
                }
            }
            return result
        } catch (_: Exception) {
            return null
        }
    }

    /** master.m3u8 candidates (absolute or /stream/), first-occurrence order. */
    fun links(unpacked: String?): List<String> {
        if (unpacked.isNullOrEmpty()) return emptyList()
        return masterRegex.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
    }

    /** Mangoporn parity: the /stream/ (hls4) path is preferred when present. */
    fun preferred(links: List<String>, embedHost: String): String? =
        links.firstOrNull { it.startsWith("/stream/") } ?: links.firstOrNull { it.startsWith("https://") }
}

open class CloudWish : ExtractorApi() {
    override val name = "CloudWish"
    override val mainUrl = "https://cloudwish.xyz"
    override val requiresReferer = true

    private val chromeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Sec-GPC" to "1",
    )

    private val streamHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0",
        "Sec-GPC" to "1",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = chromeHeaders)
            val html = response.text

            val packedScript = Regex("eval\\(function\\(p,a,c,k,e,d\\)").let { marker ->
                Regex("<script[^>]*>([\\s\\S]*?)</script>").findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull { it.contains(marker.pattern) }
            } ?: return

            val unpacked = CloudWishParse.unpack(packedScript) ?: return
            val links = CloudWishParse.links(unpacked)

            val host = java.net.URL(url).host
            val masterUrl = links.firstOrNull { it.startsWith("/stream/") }

            if (masterUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = "https://$host$masterUrl",
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            } else {
                for (m3u8Url in links) {
                    val fullUrl = if (m3u8Url.startsWith("/")) "https://$host$m3u8Url" else m3u8Url
                    val quality = when {
                        fullUrl.contains("/hls4/") -> Qualities.P1080.value
                        fullUrl.contains("/hls3/") -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(newExtractorLink(source = name, name = name, url = fullUrl) {
                        this.referer = url
                        this.quality = quality
                    })
                }
            }
        } catch (_: Exception) {
        }
    }
}
