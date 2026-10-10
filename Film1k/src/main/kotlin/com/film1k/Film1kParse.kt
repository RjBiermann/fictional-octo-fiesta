package com.film1k

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Pure parsing helpers for issue #233 (related videos, year) — test-safe, no framework types. */
object Film1kParse {
    /** issue #558 fixture-backed parse helper: one jsoup parse of the raw page html,
     *  handed to the scoped (player-strip) overloads of byseCode/abyssUrl. */
    fun doc(html: String): Document = org.jsoup.Jsoup.parse(html)

    /** Related-videos block: scoped to <main>, the section after the `Related Videos` header.
     *  Card markup is identical to listings (`article.loop-post`), so the caller reuses the
     *  same card parser as parseList. Sidebar sections live outside <main> and are skipped. */
    fun relatedOf(doc: Document): List<Element> {
        val main = doc.selectFirst("main") ?: return emptyList()
        val h3 = main.select("h3").firstOrNull {
            it.text().equals("Related Videos", ignoreCase = true)
        } ?: return emptyList()
        val section = h3.closest("section") ?: return emptyList()
        return section.select("article.loop-post").toList()
    }

    /** Year from og:title — every title carries "(<year>)" (fixture: "Tick Tock (2000) - ..."). */
    fun yearOf(ogTitle: String?): Int? =
        ogTitle?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Promo scope: the page's real player lives in `<div class="aa-cn" id="aa-videos-op">`;
     *  on-page ads (sidebar video-card promos) can embed their own `abyssplayer.com/??v=` markup
     *  deeper in the aside — must never be handed to a player branch. Issue #558.
     *  <main> fallback keeps pages without the wrapper working (byseNoSlashDoc); the doc
     *  itself is the last resort (the callers match on html today). */
    private fun playerScope(doc: Document): Element =
        doc.selectFirst("#aa-videos-op")
            ?: doc.selectFirst("main")
            ?: doc

    /** Turbovid (turbovidhls.com) — issue #496: that whole family (turbovidhls.com with
     *  its turboviplay.com / turbosplayer.com CDN) lost DNS entirely (A NODATA across
     *  resolvers, MX still answers), so nothing it served was reachable and the
     *  hardcoded-turbovidhls loadLinks branch was removed. A resurrected family
     *  rebuilds the embed-code parser from git history (fixture stays). */

    /** Byse (film1k.xyz) embed code — issue #408: the page markup is now `/e/{code}`
     *  (lazy iframe, no trailing slash) and `/e/{code}/{slug}.mp4` (fake-extension video
     *  source); the old loadingLinks regex demanded a trailing `/` and matched neither.
     *  Issue #558: scoped to the player strip — the promo aside also embeds related hosts;
     *  see abyssUrl. The two LoadPaths which need html (jsoup) call this overload; the
     *  loadLinks caller passes a parsed doc (one document tree per loadLinks — single
     *  jsoup parse, both parsers scope the same strip). Fixture:
     *  film1k_video_byse_noslash.html + film1k_video_558.html. */
    fun byseCode(html: String): String? =
        Regex("""film1k\.xyz/e/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)

    fun byseCode(doc: Document): String? =
        byseCode(playerScope(doc).toString())

    /** Abyssplayer embed URL — issue #233 gap 2 (SoTrym/enc-dec chain, shared adapter).
     *  End of the loadLinks ladder: last parseable embed on a page with no byse embed.
     *  Issue #558: scoped to the player strip (see byseCode) — on-page promos are not
     *  player embeds. Idea rezzy: the promo `?v=` token is an iframe-src sight
     *  (`player.abyssplayer.com/?v=`), which does not carry any `/e/`; fixture
     *  film1k_video_558.html (this match is the entire #558 "No links found" cause). */
    fun abyssUrl(html: String): String? =
        Regex("""abyssplayer\.com/\?v=[A-Za-z0-9]+""").find(html)?.value

    fun abyssUrl(doc: Document): String? =
        abyssUrl(playerScope(doc).toString())

    /** HLS master URL from the turbovid embed page (plain string, not packed).
     *  Kept Parse-tested (unreachable while turbovidhls.com stays DNS-dead — the
     *  loadLinks branch was removed with the family): the resurrection recipe. */
    fun turbovidStreamUrl(embedHtml: String): String? =
        // subdomain anchored — a loose [A-Za-z0-9.]* prefix would also match a
        // page-controlled look-alike host like evilturboviplay.com
        Regex("""https://(?:[A-Za-z0-9-]+\.)*turboviplay\.com/data3/[a-zA-Z0-9]+/[A-Za-z0-9]+\.m3u8""")
            .find(embedHtml)?.value
}
