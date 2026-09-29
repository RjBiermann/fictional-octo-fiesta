package com.film1k

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Pure parsing helpers for issue #233 (related videos, year) — test-safe, no framework types. */
object Film1kParse {
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

    /** Byse (film1k.xyz) embed code — issue #408: the page markup is now
     *  `/e/{code}` (lazy iframe, no trailing slash) and `/e/{code}/{slug}.mp4`
     *  (fake-extension video source); the old loadingLinks regex demanded a trailing `/`
     *  and matched neither. First match wins — same first-match semantics as the
     *  pre-#408 regex (a page embedding more than one source picks the first code,
     *  exactly as before). Fixture: film1k_video_byse_noslash.html. */
    fun byseCode(html: String): String? {
        return Regex("""film1k\.xyz/e/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
    }

    /** Turbovid (turbovidhls.com) — issue #496: that whole family (turbovidhls.com with
     *  its turboviplay.com / turbosplayer.com CDN) lost DNS entirely (A NODATA across
     *  resolvers, MX still answers), so nothing it served was reachable and the
     *  hardcoded-turbovidhls loadLinks branch was removed. A resurrected family
     *  rebuilds the embed-code parser from git history (fixture stays). */

    /** Abyssplayer embed URL — issue #233 gap 2 (SoTrym/enc-dec chain, shared adapter).
     *  End of the loadLinks ladder: last parseable embed on a page with no byse embed. */
    fun abyssUrl(html: String): String? =
        Regex("""abyssplayer\.com/\?v=[A-Za-z0-9]+""").find(html)?.value

    /** HLS master URL from the turbovid embed page (plain string, not packed).
     *  Kept Parse-tested (unreachable while turbovidhls.com stays DNS-dead — the
     *  loadLinks branch was removed with the family): the resurrection recipe. */
    fun turbovidStreamUrl(embedHtml: String): String? =
        // subdomain anchored — a loose [A-Za-z0-9.]* prefix would also match a
        // page-controlled look-alike host like evilturboviplay.com
        Regex("""https://(?:[A-Za-z0-9-]+\.)*turboviplay\.com/data3/[a-zA-Z0-9]+/[A-Za-z0-9]+\.m3u8""")
            .find(embedHtml)?.value
}
