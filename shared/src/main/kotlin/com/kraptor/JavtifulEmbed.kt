// Javtiful embed (javtiful.com/embed/<id>): a watch-config JSON block carries a
// playback endpoint + one-shot ticket; POSTing the ticket returns signed mp4
// sources. Javseen's popular far-end videos carry this host as their ONLY source
// (issue #1444, FINDINGS-1444: /290353/ → embed/115265 → 206 video/mp4), and no
// shared row answered it, so loadExtractor dropped those videos entirely.
package com.kraptor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/** Pure parse seam for the Javtiful watch page / embed (ADR-0005). */
object JavtifulEmbedParse {
    data class Playback(val endpoint: String, val ticket: String)
    data class Source(val src: String, val type: String?, val size: Int?)

    private val configScript = Regex(
        """<script[^>]*\bid=["']watch-config["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )

    /** `playback` from the watch-config JSON, or null when the page has none. */
    fun playback(html: String?): Playback? {
        val raw = html?.let { configScript.find(it)?.groupValues?.get(1) } ?: return null
        return try {
            val playback = JSONObject(raw).optJSONObject("playback") ?: return null
            val endpoint = playback.optString("endpoint")
            val ticket = playback.optString("ticket")
            if (endpoint.isBlank() || ticket.isBlank()) null else Playback(endpoint, ticket)
        } catch (_: Exception) {
            null
        }
    }

    /** Sources from the POST /video/<id>/playback response; junk yields an empty list. */
    fun sources(json: String?): List<Source> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(json).optJSONArray("sources") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                val source = array.optJSONObject(index) ?: return@mapNotNull null
                val src = source.optString("src")
                if (src.isBlank()) return@mapNotNull null
                Source(
                    src = src,
                    type = source.optString("type").takeIf { it.isNotBlank() },
                    size = if (source.has("size")) source.optInt("size") else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

open class JavtifulEmbed : ExtractorApi() {
    override var name = "Javtiful"
    override var mainUrl = "https://javtiful.com/embed"
    override val requiresReferer = true

    private val origin = "https://javtiful.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = try {
            app.get(url, referer = referer).text
        } catch (_: Exception) {
            return
        }
        val playback = JavtifulEmbedParse.playback(page) ?: return
        val endpoint = if (playback.endpoint.startsWith("http")) playback.endpoint
        else origin + playback.endpoint

        val body = try {
            app.post(
                endpoint,
                data = mapOf("ticket" to playback.ticket),
                referer = url,
                headers = mapOf(
                    "Origin" to origin,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        } catch (_: Exception) {
            return
        }

        JavtifulEmbedParse.sources(body).forEach { source ->
            val stream = if (source.src.startsWith("http")) source.src else origin + source.src
            callback.invoke(
                newExtractorLink(name, name, stream) {
                    this.referer = url
                    this.quality = source.size ?: Qualities.Unknown.value
                    this.type = if (
                        source.type?.contains("mpegurl", ignoreCase = true) == true ||
                        stream.contains(".m3u8")
                    ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }
    }
}
