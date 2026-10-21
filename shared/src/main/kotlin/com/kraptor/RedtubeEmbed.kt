// Redtube embed resolver (issue #779): the second hub of the ixiporn shape-B tier.
// The clean-tube-player b64 payload (`player-x.php?q=`) wraps an iframe from a hub
// that varies per video — xvideos (#778) and embed.redtube.com (this) are the two
// observables; both resolve through shared rows so any provider can chain them.
package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * Pure redtube embed-chain helpers (issue #779, TDD seam per ADR-0005) — kept free of
 * framework types so they load on the unit-test classpath.
 *
 * Chain (live-verified 2026-10-17, embed.redtube.com/?id=103165491):
 *  1. the embed page HTML carries a `var flashvars_NNN = ...;` assignment — a JSON object
 *     whose `mediaDefinitions` array lists the stream legs
 *  2. each mediaDefinition is a leg (format "hls" or "mp4") whose `videoUrl` is a RELATIVE
 *     media path (`/media/hls?s=<token>`, `/media/mp4?s=<token>`) resolved against the
 *     embed origin
 *  3. that path answers a JSON array of per-quality entries with absolute rdtcdn URLs
 *     (master.m3u8 for hls, direct mp4 for the mp4 leg) — the signed tokens there are
 *     expiring one-shot media URLs, not credentials (Sonar S6418)
 *
 * Deleted-shell contract: a removed video answers HTTP 404 with a "This video has been
 * removed" page and NO flashvars blob — [flashvarsJson] returns null and the caller
 * declines (no links, no throw, no dead-link best effort).
 */
object RedtubeParse {
    /** One mediaDefinition leg from the flashvars blob. */
    data class Media(val format: String, val videoUrl: String, val height: Int)

    /** One playable variant parsed from a media-response JSON. */
    data class Variant(val url: String, val quality: Int, val isDefault: Boolean)

    /**
     * The `var flashvars_NNN = <json>;` blob from an embed page; null when absent —
     * a removed-video shell (HTTP 404 "This video has been removed") carries no blob,
     * so null IS the decline path (no crash, no dead link — issue #779).
     */
    fun flashvarsJson(embedHtml: String): String? =
        Regex("""var\s+flashvars(?:_\w+)?\s*=\s*(\{.*?\});""") // signed-media config blob, not a secret (Sonar S6418)
            .find(embedHtml)?.groupValues?.get(1)

    /** Stream legs (hls/mp4) worth resolving — blank/non-stream videoUrls are dropped. */
    fun mediaFromFlashvars(flashvarsJson: String): List<Media> = try {
        val md = com.fasterxml.jackson.databind.ObjectMapper().readTree(flashvarsJson)
            .path("mediaDefinitions")
        (0 until md.size()).mapNotNull { i ->
            val e = md.get(i) ?: return@mapNotNull null
            val format = e.path("format").asText("")
            if (format != "hls" && format != "mp4") return@mapNotNull null
            val videoUrl = e.path("videoUrl").asText("")
            if (videoUrl.isBlank()) return@mapNotNull null
            Media(format, videoUrl, e.path("height").asInt(0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * A `/media/hls?s=` or `/media/mp4?s=` response → playable variants. Only ABSOLUTE
     * entries are emitted (relative ones are server-side media paths, not links) —
     * garbage/non-JSON answers yield an empty list (graceful leg failure, #779).
     */
    fun variantsFromMediaJson(mediaJson: String): List<Variant> = try {
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(mediaJson)
        if (!root.isArray) emptyList()
        else (0 until root.size()).mapNotNull { i ->
            val e = root.get(i) ?: return@mapNotNull null
            val url = e.path("videoUrl").asText("")
            if (!url.startsWith("http")) return@mapNotNull null
            Variant(
                url,
                e.path("quality").asInt(0),
                e.path("defaultQuality").asBoolean(false)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Redtube embed player (issue #779) — resolves the full flashvars → /media chain. */
open class RedtubeEmbed : ExtractorApi() {
    override val name = "Redtube"
    override val mainUrl = "https://embed.redtube.com"
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
        // deleted-shell contract: the removed-video shell carries no flashvars blob —
        // decline with zero links rather than a best-effort dead entry
        val flash = RedtubeParse.flashvarsJson(html) ?: return
        for (media in RedtubeParse.mediaFromFlashvars(flash)) {
            // every fixture leg is remote (relative path under the embed origin);
            // an absolute videoUrl (if the site ever serves one) is playable as-is
            val variants = if (media.videoUrl.startsWith("http")) {
                listOf(RedtubeParse.Variant(media.videoUrl, media.height, false))
            } else {
                val res = try {
                    app.get("$mainUrl${media.videoUrl}", referer = referer ?: "$mainUrl/")
                } catch (e: Exception) {
                    Log.d(name, "leg ${media.format}: ${e.message}")
                    null
                } ?: continue
                RedtubeParse.variantsFromMediaJson(res.text)
            }
            for (v in variants) {
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
