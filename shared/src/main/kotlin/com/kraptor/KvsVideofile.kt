// KVS `videofile.php` resolver (issue #1445, ixiporn shape-D tier; ADR-0005 Parse seam).
//
// The repo already resolves KVS streams from the flashvars grammar (KvsFlashvars, sites
// that print `video_url: '…'` in the page). A second KVS generation — vjav.com,
// txxx.com/txxx.me (canonical host of the videotxxx.com mirror) — is a JS shell instead:
// the embed page prints no stream at all, the player asks
//   `/<origin>/api/videofile.php?video_id=<id>&lifetime=864000`
// and the answer's `video_url` is base64 with Cyrillic homoglyphs swapped in for the Latin
// letters (KVS's own obfuscation — reversible, NOT encryption/credentials).
//
// Payload grammar (live captures, `kvs_videofile_vjav.json` / `kvs_videofile_txxx.json`):
//   * `~` terminates the payload;
//   * the body is either one base64 part (`<path>?<query>`, txxx) or two comma-separated
//     parts (`<path>,<query>`, vjav) — decoded parts are joined back with `?`;
//   * the decoded path is RELATIVE to the API origin (`/get_file/…_hq.mp4/`).
//
// Dead videos answer `[]` → no sources, no throw (the repo's dead-target contract).
package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.io.IOException

/** Pure deobfuscation + endpoint grammar for the KVS `videofile.php` player (issue #1445). */
object KvsVideofileParse {

    /** One playable entry from the API answer. */
    data class Source(val url: String, val format: String)

    /**
     * Cyrillic look-alikes KVS substitutes for the base64 Latin alphabet. Only the letters
     * with a Latin twin are mapped; anything else failing base64 raises the decline path.
     */
    private val HOMOGLYPHS: Map<Char, Char> = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H', 'К' to 'K',
        'М' to 'M', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X', 'У' to 'Y',
        'І' to 'I', 'Ј' to 'J', 'Ѕ' to 'S',
        'а' to 'a', 'в' to 'b', 'с' to 'c', 'е' to 'e', 'н' to 'h', 'к' to 'k',
        'м' to 'm', 'о' to 'o', 'р' to 'p', 'т' to 't', 'х' to 'x', 'у' to 'y',
        'і' to 'i', 'ј' to 'j', 'ѕ' to 's',
    )

    private val EMBED_ID = Regex("""/embed/(\d+)""")
    private val ORIGIN = Regex("""^(https?://[^/]+)""")

    /** `<origin>/embed/<id>` → the numeric video id; null when the URL is not an embed. */
    fun videoId(embedUrl: String): String? =
        EMBED_ID.find(embedUrl)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /** `https://host/path` → `https://host` (scheme + authority only); null when not absolute. */
    fun origin(url: String): String? = ORIGIN.find(url)?.groupValues?.get(1)

    /**
     * Referer the KVS CDNs accept on the stream: the API answer's FINAL origin (a mirror's
     * 302 lands on the canonical host, e.g. videotxxx.com → txxx.com), else the embed's own
     * origin. The provider page that dispatched us is NOT accepted — vjav/txxx gate on their
     * own origin and answer 403 to a foreign referer (#1508 review).
     */
    fun streamReferer(embedUrl: String, apiResponseUrl: String?): String? =
        origin(apiResponseUrl.orEmpty()) ?: origin(embedUrl)

    /**
     * The `videofile.php` endpoint for an embed URL, built on the embed's OWN origin —
     * the txxx mirror's 302 to `txxx.me` happens after dispatch, so the request has to
     * start where the iframe pointed. Null when no id can be read.
     */
    fun apiUrl(embedUrl: String): String? {
        val id = videoId(embedUrl) ?: return null
        val origin = origin(embedUrl) ?: return null
        return "$origin/api/videofile.php?video_id=$id&lifetime=864000"
    }

    /**
     * One obfuscated `video_url` value → the decoded relative media path (query included),
     * or null when it cannot be decoded. Pure string work: the caller absolutizes.
     */
    fun decodePayload(payload: String): String? {
        val body = payload.substringBefore('~').split(',').filter { it.isNotBlank() }
        if (body.isEmpty()) return null
        val parts = body.map { part ->
            decodeBase64(part.map { HOMOGLYPHS[it] ?: it }.joinToString("")) ?: return null
        }
        val path = parts.first().trim().takeIf { it.isNotEmpty() } ?: return null
        val query = parts.getOrNull(1)?.trim().orEmpty()
        return if (query.isEmpty()) path else path + (if (path.contains('?')) "&" else "?") + query
    }

    /**
     * `videofile.php` JSON → playable sources, absolute against [origin] (the API response's
     * own origin, so a mirror's redirect target wins). Non-JSON, `[]`, undecodable payloads
     * and blank URLs all yield no sources — a dead video declines instead of crashing.
     */
    fun sources(json: String, origin: String): List<Source> = try {
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
        if (!root.isArray) emptyList()
        else (0 until root.size()).mapNotNull { i ->
            val e = root.get(i) ?: return@mapNotNull null
            val payload = e.path("video_url").asText("")
            if (payload.isBlank()) return@mapNotNull null
            val decoded = decodePayload(payload) ?: return@mapNotNull null
            val absolute = if (decoded.startsWith("http")) decoded else origin.trimEnd('/') + "/" + decoded.trimStart('/')
            Source(absolute, e.path("format").asText(""))
        }.distinctBy { it.url }
    } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
        emptyList()
    }
}

/**
 * KVS `videofile.php` player adapter (issue #1445). One row per embed host the ixiporn
 * shape-D tier serves — dispatch is by the ORIGINAL iframe URL, so the mirror's redirect
 * target never answers for it.
 *
 * Quality: the API exposes only a format label (`_hq.mp4` / `_sd.mp4`), no ladder, so the
 * emitted quality is `Qualities.Unknown` — never an invented resolution.
 */
open class KvsVideofile(override val mainUrl: String, override val name: String) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val api = KvsVideofileParse.apiUrl(url) ?: return
        // request on the embed's own origin — a foreign referer is what the CDNs reject
        val embedOrigin = KvsVideofileParse.origin(url) ?: mainUrl
        val res = try {
            app.get(api, referer = embedOrigin)
        } catch (e: IOException) {
            Log.d(name, "videofile: ${e.message}")
            return
        }
        // absolutize + referer against the API response's own origin (mirror → canonical redirect)
        val origin = KvsVideofileParse.streamReferer(url, res.url) ?: mainUrl
        for (source in KvsVideofileParse.sources(res.text, origin)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = source.url,
                    type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = origin
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
