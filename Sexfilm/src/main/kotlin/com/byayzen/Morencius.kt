package com.byayzen

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack

// Morencius embeds ship the stream in a packed jwplayer config (eval(function(p,a,c,k,e,d)…));
// unpack and pull the m3u8 — same pattern as Filmcdn.
open class Morencius : ExtractorApi() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to USER_AGENT
        )

        val response = app.get(url, referer = referer)
        val unpacked = getAndUnpack(response.text)
        Regex("\"(https?://[^\"]+?\\.m3u8[^\"]*)\"").findAll(unpacked).forEach { match ->
            generateM3u8(
                name,
                match.groupValues[1],
                referer ?: "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }
}