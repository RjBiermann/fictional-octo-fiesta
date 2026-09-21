// Mangoporn provider-only extractors (issue #443).
//
// The upstream Mangoporn source (Kraptor123/Cs-GizliKeyif) ships a large
// Extractors.kt whose host families (Filemoon/Byse, Streamwish, VidHidePro,
// DoodStream, StreamTAPE, Player4Me, LULUBASE, VidNest, Playmate, Vidguardto,
// Turtleviplay — including Vidguardto at https://vidguard.to) are already
// byte-equivalent ports in shared/Extractorlar.kt — per ADR-0002 they are NOT
// duplicated here and no provider mirror rows are registered; they reach this
// provider through registerHostExtractors(). Only hosts the shared table lacks
// (CloudWish) live in this file.
package com.kraptor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// CloudWish: Dean-Edwards packed player script. The mangoporn upstream carries
// its own unpack() whose grammar differs from shared PackedJs (no `.split('|')`
// tail required, keys may be absent); ported as-is to not disturb the shared
// fixture-tested grammar.
open class CloudWish : ExtractorApi() {
    override val name = "CloudWish"
    override val mainUrl = "https://cloudwish.xyz"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
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

    fun unpack(packedJs: String): String? {
        try {
            val pattern = Regex(
                """\}\('((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = pattern.find(packedJs) ?: return null

            val p = match.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\\", "\\")
            val a = match.groupValues[2].toInt()
            val c = match.groupValues[3].toInt()
            val k = match.groupValues[4].split("|").toMutableList()

            while (k.size < c) {
                k.add("")
            }

            var result = p
            for (i in (c - 1) downTo 0) {
                if (k[i].isNotEmpty()) {
                    val token = Integer.toString(i, a)
                    result = result.replace("\\b$token\\b".toRegex(RegexOption.IGNORE_CASE), k[i])
                }
            }

            return result
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = baseHeaders)
            val html = response.text

            val packedScript = Regex("eval\\(function\\(p,a,c,k,e,d\\)").let { marker ->
                Regex("<script[^>]*>([\\s\\S]*?)</script>").findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull { it.contains(marker.pattern) }
            } ?: return

            val unpacked = unpack(packedScript) ?: return

            val parsedUrl = java.net.URL(url)
            val host = parsedUrl.host

            val m3u8Pattern =
                Regex("""(https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*|/stream/[^\s"'<>]+master\.m3u8)""")
            val m3u8Urls = m3u8Pattern.findAll(unpacked)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            val masterUrl = m3u8Urls.firstOrNull { it.startsWith("/stream/") }

            if (masterUrl != null) {
                val fullUrl = "https://$host$masterUrl"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fullUrl,
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0",
                            "Sec-GPC" to "1",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-origin",
                        )
                    }
                )
            } else {
                for (m3u8Url in m3u8Urls) {
                    val fullUrl =
                        if (m3u8Url.startsWith("/")) "https://$host$m3u8Url" else m3u8Url
                    val quality = when {
                        fullUrl.contains("/hls4/") -> Qualities.P1080.value
                        fullUrl.contains("/hls3/") -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(
                        newExtractorLink(source = name, name = name, url = fullUrl) {
                            this.referer = url
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
}
