// javhdz.today embed host — primary data-embed target on javseen.tv.
// Same playlist player family as SavedVids (see FINDINGS #118/#145); Cloudflare
// blocks datacenter IPs so this can only be confirmed in-app.
package com.byayzen

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
