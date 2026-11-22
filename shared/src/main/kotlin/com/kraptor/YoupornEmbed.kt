// Youporn hub resolver (issue #1445, ixiporn shape-B tier; ADR-0005 Parse seam).
//
// ixiporn's import-era pages wrap a hub embed in the player-x b64 payload and the hub
// varies per video: xvideos (#778) and embed.redtube.com (#779) were the two observables
// until the 2026-11-22 S7 pass found a third — `www.youporn.com/embed/<id>/<slug>/` on the
// 2020-12 uploads (`/page/1400`: 2/2 sampled, 0 links emitted before this row).
//
// The youporn embed page is server-rendered by the same network as redtube (ypncdn media
// CDN, identical `/media/{hls,mp4}/?s=` signed hop, identical variant JSON) but carries its
// legs as `mediaDefinition` — SINGULAR — inside the player config object, so
// RedtubeParse.flashvarsJson's `var flashvars… = {…}` grammar never matches. That is the
// whole gap: the media hop below is RedtubeParse.variantsFromMediaJson, unchanged.
package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/** Pure parse half of the youporn embed (issue #1445). */
object YoupornParse {

    /** One media leg from the embed page's `mediaDefinition` array. */
    data class Media(val format: String, val videoUrl: String, val height: Int)

    /**
     * The `mediaDefinition: [...]` array (singular key, live fixture
     * `youporn_embed_live.html`) → its stream legs. m3u8/mp4 formats only; a removed video's
     * shell carries no array at all and yields no legs (decline, never a dead link).
     *
     * The array is sliced by bracket depth rather than a regex so the nested
     * `segmentFormats` objects stay intact.
     */
    fun mediaFromEmbed(html: String): List<Media> {
        val key = html.indexOf("mediaDefinition")
        if (key < 0) return emptyList()
        val start = html.indexOf('[', key)
        if (start < 0) return emptyList()
        val json = balancedArray(html, start) ?: return emptyList()
        return try {
            val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
            if (!root.isArray) emptyList()
            else (0 until root.size()).mapNotNull { i ->
                val e = root.get(i) ?: return@mapNotNull null
                val format = e.path("format").asText("")
                if (format != "hls" && format != "mp4") return@mapNotNull null
                val videoUrl = e.path("videoUrl").asText("")
                if (videoUrl.isBlank()) return@mapNotNull null
                Media(format, videoUrl, e.path("height").asInt(0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [text] from `[` at [start] to its matching `]` (inclusive); null when unbalanced. */
    private fun balancedArray(text: String, start: Int): String? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

/** Youporn embed player (issue #1445) — mediaDefinition → signed `/media` hop. */
open class Youporn : ExtractorApi() {
    override val name = "Youporn"
    override val mainUrl = "https://www.youporn.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: "$mainUrl/").text
        } catch (e: Exception) {
            Log.d(name, "embed: ${e.message}")
            return
        }
        for (media in YoupornParse.mediaFromEmbed(html)) {
            // the leg is the SIGNED `/media/{hls,mp4}/?s=…` hop (absolute on youporn), not a
            // media file: it answers the per-quality JSON the redtube family shares
            val hopUrl = if (media.videoUrl.startsWith("http")) media.videoUrl
                         else "$mainUrl${media.videoUrl}"
            val res = try {
                app.get(hopUrl, referer = referer ?: "$mainUrl/")
            } catch (e: Exception) {
                Log.d(name, "leg ${media.format}: ${e.message}")
                continue
            }
            for (v in RedtubeParse.variantsFromMediaJson(res.text)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = v.url,
                        type = if (media.format == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = if (v.quality > 0) getQualityFromName("${v.quality}p")
                                       else Qualities.Unknown.value
                    }
                )
            }
        }
    }
}
