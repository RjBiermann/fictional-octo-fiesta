// Extractor adapters that used to live inside provider directories, moved to
// shared code for the Host registry (ADR-0002) — every provider can now match
// these embed hosts. Includes the HlsFree family (Cat3Movie token dance).
package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class MyDaddyExtractor : ExtractorApi() {
    override val name = "MyDaddy"
    override val mainUrl = "https://mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text

        val regex = Regex(pattern = "a href='([^']*)'", options = setOf(RegexOption.IGNORE_CASE))

        val videolar = regex.findAll(response)

        videolar.forEach { video ->
            val video = video.groupValues[1]
            callback.invoke(newExtractorLink(
                this.name,
                this.name,
                fixUrl(video),
                type = INFER_TYPE
            ) {
                this.referer = "$referer"
                this.quality = getQualityFromName(video.substringAfterLast("/").substringBefore("."))
            })
        }

    }
}

// vidara.to embed (jav.guru searcho mirror "cd"): POST /api/stream with the
// filecode from /e/<filecode> returns {"streaming_url": "<m3u8>", ...}
class Vidara : ExtractorApi() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val filecode = url.substringAfterLast("/e/").substringBefore(".")
        if (filecode.isBlank()) return null
        val res = app.post(
            "$mainUrl/api/stream",
            json = mapOf("filecode" to filecode, "device" to "web"),
            referer = referer
        ).text
        val streamUrl = Regex("\"streaming_url\"\\s*:\\s*\"([^\"]+)\"").find(res)?.groupValues?.get(1)
            ?: return null
        return listOf(
            newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer ?: "$mainUrl/"
            }
        )
    }
}

// javhdz.today embed host — primary data-embed target on javseen.tv.
// Same playlist player family as SavedVids (see FINDINGS #118/#145); Cloudflare
// blocks datacenter IPs so this can only be confirmed in-app.
open class Javhdz : ExtractorApi() {
    override val name = "Javhdz"
    override val mainUrl = "https://stream3.javhdz.today"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer ?: "https://javseen.tv/").text
        val m3u8 = Regex("\"playlist\":\\s*\"([^\"]+\\.m3u8[^\"]*)\"").find(res)?.groupValues?.get(1) ?: return
        callback.invoke(
            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = referer ?: "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class Javhdz2 : Javhdz() {
    override val name = "Javhdz2"
    override val mainUrl = "https://stream2.javhdz.today"
}

open class PerverZijaExtractor : ExtractorApi() {
    override var name = "PerverZija"
    // player iframe subdomain varies per video (pervlN/pervmN/j2/perv...); referer must be the player domain
    override var mainUrl = "https://pervl2.xtremestream.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("kraptor_PerverZijaExtract", "url = $url")

        listOf("480", "720", "1080", "2160").forEach { kalite ->
            val videoQuality = kalite.toIntOrNull() ?: 720

            val changeUrl = url.replace("index.php", "xs1.php") + "&q=$videoQuality"

            callback.invoke(newExtractorLink(
                source = this.name,
                name = this.name,
                url = changeUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url.substringBefore("/player/") + "/"
                this.quality = videoQuality
            })
        }
    }
}

// hlsfree.com embed (Cat3Movie): the embed page exposes
// defaultHlsUrl = "...token=<hex>"; /api/hls/serve?token=<hex> serves the m3u8.
open class HlsFree : ExtractorApi() {
    override val name = "HlsFree"
    override val mainUrl = "https://hlsfree.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedPage = app.get(url, referer = referer ?: "https://hlsfree.com/").text
        val token = Regex("defaultHlsUrl\\s*=\\s*\"([^\"]*token=([a-f0-9]+))\"")
            .find(embedPage)?.groupValues?.get(2) ?: return
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = "$mainUrl/api/hls/serve?token=$token",
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// www. variant of the hlsfree embed URL (loadExtractor matches by mainUrl prefix).
class HlsFreeWww : HlsFree() {
    override val name = "HlsFree"
    override val mainUrl = "https://www.hlsfree.com"
}
