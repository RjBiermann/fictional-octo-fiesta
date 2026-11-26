// Pure Parse functions for PornSlash (pornslash.com) — jsoup in, values out; no HTTP, no
// framework types (ADR-0005: MainAPI/HTTP flows stay with pipeline Verification). Fixtures
// under src/test/resources/ are live captures from 2026-11-26.
//
// Why a provider-local seam and not the shared KVS modules: this site is not flashvars KVS.
// The served HTML carries no `video_url` and no `kt_player`, so KvsFlashvars/KvsVideofile
// have nothing to read. What it does serve is the mp4 path on every card
// (`div.thumbnail[data-p=".../p/<encid>.mp4"]`) and, on the watch page, a base64-encoded
// HLS tunnel base.
//
// Two structural rules the drift history demands (findings 2026-11-26):
//  - the CDN host is a per-video `*.pages.dev` project (wolfcipher56-1343.pages.dev/13426,
//    ba0ze4asqz-1338.pages.dev/13375, ...): read from the page on every load, never templated;
//  - the HLS base is a rotating `*.trycloudflare.com` tunnel: decoded from the page on every
//    load, never cached across loads.
package com.rjbiermann

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jsoup.nodes.Element

/** One listing card: the fields the surfaces (home, search, category, related) all carry. */
data class SlashCard(
    val id: String,
    val title: String,
    val poster: String?,
    val quality: String?,
    /** Runtime in seconds — `data-duration` is exact; the `.duration` clock is the fallback. */
    val duration: Int?,
)

object PornSlashParse {

    /** The listing card root — one grammar on every surface (home, search, /cat/, related). */
    const val CARD = "div.video-item"

    private val VIDEO_HREF = Regex("""/watch/([A-Za-z0-9_-]+)""")

    /** `/t/<encid>.<ext>` — the trailing thumbnail path segment the mp4 mirrors. */
    private val THUMB = Regex("""/t/([^/?#]+)\.[A-Za-z0-9]+$""")

    /** H:MM:SS / MM:SS card clock (`54:35` on the live home page). */
    private val CLOCK = Regex("""^(?:(\d+):)?(\d+):(\d+)$""")

    private val DIGITS = Regex("""(\d{3,4})""")

    /** A base64 run long enough to be a token, not a JS identifier. */
    private val B64_RUN = Regex("""[A-Za-z0-9+/]{24,}={0,2}""")

    private val HLS_TUNNEL = Regex("""https?://[a-z0-9-]+\.trycloudflare\.com""", RegexOption.IGNORE_CASE)

    /**
     * One `div.video-item` → [SlashCard]; null when the card lacks a real id or title so a
     * single broken card never kills a listing.
     *
     * Field sources, in order: id from `div.video-info[data-id]` then the `/watch/<id>`
     * anchor; title from `data-title` then the title anchor's text/title attr; poster from
     * the thumbnail `<img>` (placeholder `data:` URIs skipped, never fabricated); quality
     * from the `.quality` badge then `data-quality`; duration from `data-duration` (seconds)
     * then the `.duration` clock.
     */
    fun card(el: Element): SlashCard? {
        val meta = el.selectFirst("div.video-info")
        val id = meta?.attr("data-id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: VIDEO_HREF.find(el.selectFirst("a[href*=/watch/]")?.attr("href").orEmpty())
                ?.groupValues?.get(1)
            ?: return null
        val title = meta?.attr("data-title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: el.selectFirst("h2.video-title a[title]")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: el.selectFirst("h2.video-title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = el.selectFirst("div.thumbnail img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
                .takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val quality = quality(
            el.selectFirst(".quality")?.text()?.takeIf { it.isNotBlank() } ?: meta?.attr("data-quality")
        )
        val duration = meta?.attr("data-duration")?.trim()?.toIntOrNull()
            ?: clockSeconds(el.selectFirst(".duration")?.text())
        return SlashCard(id, title, poster, quality, duration)
    }

    /**
     * Page-N URL for a listing surface: `?p=N` and nothing else.
     *
     * Path paging is dead on this site (`/search/<q>/2/` and `/page/2/` answer 404), and the
     * pager is JS-built, so the query param is the entire contract. Page 1 is the bare
     * surface — a stale `p=` is dropped rather than forwarded.
     *
     * The query is rebuilt pair by pair rather than regex-replaced: a surface that carries
     * another param (`...?p=1&sort=new`, S5's filters) keeps both its `?` and that param.
     */
    fun pageUrl(surface: String, page: Int): String {
        val q = surface.indexOf('?')
        val path = if (q >= 0) surface.substring(0, q) else surface
        val params = (if (q >= 0) surface.substring(q + 1) else "")
            .split('&')
            .filter { it.isNotBlank() && !it.startsWith("p=") }
            .toMutableList()
        if (page > 1) params.add("p=$page")
        return if (params.isEmpty()) path else path + "?" + params.joinToString("&")
    }

    /**
     * Thumbnail URL → mp4 URL: `/t/<encid>.<ext>` becomes `/p/<encid>.mp4`, host (the
     * per-video `*.pages.dev` project) and `<ptid>` path segment preserved. Null when the
     * URL is not that shape — a stream is never derived from something that is not a
     * thumbnail.
     */
    fun mp4FromThumb(ogImage: String?): String? {
        val url = ogImage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mp4 = THUMB.replace(url) { m -> "/p/${m.groupValues[1]}.mp4" }
        return mp4.takeIf { it != url }
    }

    /**
     * The watch page's HLS base: the base64 token that decodes to the rotating
     * `*.trycloudflare.com` tunnel. Decoded per load from the page — never cached across
     * loads (the tunnel rotates) and never templated. The page also carries an unrelated
     * base64 redirect (`wideeyedchance.com`) and a `media-server-NN` token, hence the
     * decode-then-match instead of "the first base64 on the page".
     */
    fun hlsBase(detailHtml: String?): String? {
        if (detailHtml.isNullOrBlank()) return null
        for (run in B64_RUN.findAll(detailHtml)) {
            val decoded = decodeBase64(run.value) ?: continue
            if (HLS_TUNNEL.matches(decoded)) return decoded
        }
        return null
    }

    /**
     * `data-quality` is the truth: `"480"` → `"480p"`, `"1080p"` → `"1080p"`. No ladder is
     * invented when the source has no number (absent, blank or `"HD"` → null): a fabricated
     * quality label misleads the player.
     */
    fun quality(raw: String?): String? =
        raw?.let { DIGITS.find(it) }?.groupValues?.get(1)?.let { "${it}p" }

    /**
     * Pagination stop rule: a full page means another page may exist, a partial page is an
     * unambiguous stop. Never probe `last + 1` — `/videos/?p=9999` and `/cat/<slug>/?p=9999`
     * answer 500 and `/search/<q>/?p=9999` answers 404 on this site.
     */
    fun hasNext(count: Int, pageSize: Int): Boolean = pageSize > 0 && count == pageSize

    private fun clockSeconds(text: String?): Int? {
        val m = CLOCK.find(text?.trim().orEmpty()) ?: return null
        val (h, min, s) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + min.toInt() * 60 + s.toInt()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(token: String): String? = runCatching {
        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val decoded = String(Base64.decode(padded), Charsets.UTF_8)
        decoded.takeIf { text -> text.all { it.code in 0x20..0x7e } }
    }.getOrNull()
}
