package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
