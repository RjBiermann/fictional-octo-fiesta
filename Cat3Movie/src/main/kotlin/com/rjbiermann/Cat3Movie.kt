package com.rjbiermann

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.kraptor.CardFields
import com.kraptor.SearchCard
import com.kraptor.cfChallenge
import com.kraptor.registerHostExtractors
import org.jsoup.nodes.Element

class Cat3Movie : MainAPI() {
    override var mainUrl = "https://cat3movie.org"
    override var name = "Cat3Movie"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // issue #411: cat3movie.org enabled Cloudflare JS Detection (the JSD snippet now sits
    // on every healthy 200 page — see FINDINGS.md). On a challenged client every leg
    // fails silently: the watch page comes back as a "Just a moment…" interstitial →
    // streamConfig finds no post_id → loadLinks returns false with zero callbacks →
    // CS3 shows "No links found". Route every fetch through CloudflareKiller (repo
    // precedent: Cat3Film #410, FullPorner, Film1k). Healthy pages carry the JSD snippet
    // too, so the trigger is the interstitial markers only (Parse.isChallengePage).
    private val cloudflareKiller by lazy { CloudflareKiller() }
    // Shared CfChallengeInterceptor (audit finding 2): Cat3Movie keeps its broader
    // marker set (Parse.isChallengePage) as the challenge predicate — healthier pages
    // also carry the JSD snippet, markers alone are the trigger.
    private val cfInterceptor by lazy { cfChallenge(cloudflareKiller) { Parse.isChallengePage(it) } }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest",
        "$mainUrl/new-movies" to "New Movies",
        "$mainUrl/classic-porn" to "Classic Porn",
        "$mainUrl/classic-erotica" to "Classic Erotica",
        "$mainUrl/asian-erotica" to "Asian Erotica",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url, interceptor = cfInterceptor).document
        // issue #645 hardening (drift-recurrence rule): the halimmovies theme serves the same
        // listing on /page/N for the homepage row (live origin fetches, cf-cache-status
        // EXPIRED/MISS — WP `paged-2` body class present, not a cache artifact) — without a
        // cross-page seen-set the Latest row re-serves page-1 cards forever. Category rows
        // paginate cleanly (verified disjoint on this run) and are unaffected: their page-2
        // content is all-new. Page 1 re-seeds the set (fresh listing renders on each visit).
        val seen = seenHrefs.getOrPut(request.data) { HashSet() }
        if (page <= 1) seen.clear()
        val cards = Parse.filterSeen(
            SearchCard.homeCards(
                document, "article.thumb",
                titleSel = "a.halim-thumb", titleAttr = "title", posterSel = "img[data-src]",
            ), seen
        ).map { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = cards, isHorizontalImages = false),
            hasNext = true
        )
    }

    /** Cross-page seen-hrefs per main-page row ([SearchCard.homeCards] output dedupes within
     *  a page; this set is what re-seeds on page 1 and filters deeper pages). */
    private val seenHrefs = HashMap<String, HashSet<String>>()

    /** Emission adapter for the shared SearchCard module (halimmovies hrefs are absolute). */
    private fun CardFields.toSearchResult(): SearchResponse =
        newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // /search/<slug> — the site gained pagination on this surface (issue #645: page 2
        // serves fresh cards; /page/3+ is 404 past-the-last-page, which parses to an empty
        // list so the row terminates). Slug-ification moved behind Parse.searchUrl.
        val document = app.get(
            Parse.searchUrl(mainUrl, query, page), interceptor = cfInterceptor
        ).document
        // search keeps every element (dedupe is homepage-only); the shared Parse function
        // drops only cards without a real title/link, exactly like the old local copy
        val list = Parse.searchCards(document)
            .mapNotNull { SearchCard.parse(it, "a.halim-thumb", posterSel = "img[data-src]", titleAttr = "title") }
            .map { it.toSearchResult() }
        return newSearchResponseList(list, hasNext = list.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: url.substringAfterLast('/')
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        // year: markup payloads were replaced by CF-only escaped card payloads on watch pages
        // (issue #250 re-probe) — p.released still prevails when present, title `Movie (1985)`
        // is the fallback shape every halimmovies watch page carries
        val year = document.selectFirst("p.released a[href*=/release/]")?.text()?.trim()?.toIntOrNull()
            ?: Parse.yearFromTitle(title)
        val tags = document.select("p.category a").map { it.text() }.filter { it.isNotBlank() }
        val actors = document.select("p.actors").filter { it.selectFirst("a") != null && it.text().startsWith("Actors") }
            .flatMap { it.select("a").map { a -> a.text() } }
        val plot = document.selectFirst("div.entry-content article.item-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val recommendations = SearchCard.homeCards(
            document, "section.related-movies article.thumb",
            titleSel = "a.halim-thumb", titleAttr = "title", posterSel = "img[data-src]",
        ).map { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            this.plot = plot
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, interceptor = cfInterceptor).document
        } catch (e: Exception) {
            Log.d("Cat3Movie", "watch page: ${e.message}")
            null
        } ?: return false

        // issue #250: keep a fallback if body[data-nonce] is ever absent, but note the live
        // watch pages all carry it and halim_cfg has no "nonce" key — the regex fallback
        // therefore grabs the first page-level `"nonce"` JSON entry (ajax_player), which
        // player.php does not validate (verified: any nonce, even "deadbeef", returns 200)
        val cfg = Parse.streamConfig(document) ?: return false
        val postId = cfg.postId.toString()
        val nonce = cfg.nonce

        // sv1..sv3 servers: hlsfree (token m3u8), hlsfast (hash → public resolve → turboembed)
        // and cdn.loadvid (JSON config → single-segment manifest) all dispatch through the
        // host registry below
        val embeds = LinkedHashSet<String>()
        for (sv in 1..3) {
            try {
                // issue #250: player.php GETs are cached by Cloudflare and sit behind the
                // per-server watch page — cache-buster + correct referer dodge the recorded
                // CF-cached 404/{"status":true,"code":403} responses
                val playerHtml = app.get(
                    "$mainUrl/wp-content/themes/halimmovies/player.php",
                    referer = Parse.playerReferer(data, sv),
                    interceptor = cfInterceptor,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    params = mapOf(
                        "episode_slug" to "full",
                        "server_id" to sv.toString(),
                        "subsv_id" to "",
                        "post_id" to postId,
                        "nonce" to nonce,
                        "custom_var" to "",
                        "_" to System.currentTimeMillis().toString(),
                    )
                ).text
                Parse.embedIframe(playerHtml)?.let { embeds.add(it) }
            } catch (e: Exception) {
                Log.d("Cat3Movie", "sv$sv: ${e.message}")
            }
        }
        // Every leg dispatches through the host registry (ADR-0002). The old bespoke hlsfast
        // branch (AES-CBC hex from /api/v1/video, issue #180) went dead: that endpoint now
        // answers 404 for every hash and the player shell resolves `/#<hash>` through
        // /api/public/resolve → turboembed.com (issue #1448). Keeping the dispatch here —
        // rather than a host branch — is what lets a rotated destination host be a registry
        // row instead of another provider rewrite.
        // player.php serves the SAME iframe on sv1..sv3 for many videos, so the set collapses
        // the duplicate legs into one dispatch per distinct URL.
        for (embed in Parse.distinctEmbeds(embeds)) {
            loadExtractor(embed, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}

/**
 * Pure parse hooks (TDD-first, ADR-0005). Card selection/dedupe/emission moved to the shared
 * SearchCard module (SearchCard.homeCards + SearchCard.parse); site-specific Parse hooks stay.
 */
object Parse {
    fun searchCards(document: org.jsoup.nodes.Document): List<Element> =
        document.select("article.thumb").toList()

    /** post_id + player nonce for the watch page ([data-post_id]/body[data-nonce]). The
     *  fallbacks only fire on a page shape not observed live (body[data-nonce] present on
     *  10/10 sampled pages); player.php ignores nonce, so the fallback value is cosmetic. */
    fun streamConfig(document: org.jsoup.nodes.Document): StreamConfig? {
        val html = document.html()
        val postId = Regex("\"post_id\":(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: (document.selectFirst("[data-post_id]")?.attr("data-post_id")?.toIntOrNull())
            ?: return null
        val nonce = document.selectFirst("body[data-nonce]")?.attr("data-nonce")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("\"nonce\":\"([a-f0-9]{8,})\"").find(html)?.groupValues?.get(1)
            ?: return null
        return StreamConfig(postId, nonce)
    }

    /** Referer check on player.php built from the watch-page slug — works whether `data` is
     *  the base watch URL or an episode page (the old `watch-` + slug concat doubled it). */
    fun playerReferer(data: String, sv: Int): String =
        data.substringBefore("/full-sv").substringAfterLast('/')
            .removePrefix("watch-")
            .let { "https://cat3movie.org/watch-$it/full-sv$sv.html" }

    /** Year from the page title's `Movie (1985)` suffix (raw p.released markup is gone on
     *  current watch pages — issue #250 re-probe). */
    fun yearFromTitle(title: String): Int? =
        Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

    /** Issue #645: search page URL — the site gained pagination on /search (page 2 returns
     *  fresh cards, /page/3+ is 404 past the last page). Page 1 keeps the bare path; deeper
     *  pages append the /page/N suffix the WP theme serves. Slug-ification here:
     *  lowercase, whitespace → '-', everything but letters/digits dropped (the exact filter
     *  the search() override carried before this run). */
    fun searchUrl(mainUrl: String, query: String, page: Int): String {
        val slug = query.trim().lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), "-")
        return if (page > 1) "$mainUrl/search/$slug/page/$page" else "$mainUrl/search/$slug"
    }

    /** Issue #645 hardening: cross-page row filter — keeps only cards whose href has not
     *  been served for the row yet, recording the ones it keeps (the halimmovies homepage
     *  /page/N serves the same listing). MutableSet.add's return value is the membership
     *  test; page-1 re-seed is the caller clearing the set. */
    fun filterSeen(cards: List<CardFields>, seen: MutableSet<String>): List<CardFields> =
        cards.filter { seen.add(it.href) }

    /** Embed src from the player.php response, double or single quotes. Live markup sometimes
     *  opens the value with a space (`src=" https://…"`) — dispatch is a mainUrl prefix match,
     *  so the value is trimmed before it leaves the parser. */
    fun embedIframe(html: String): String? =
        Regex("iframe[^>]*src=[\"']([^\"']+)").find(html)?.groupValues?.get(1)?.trim()

    /** One dispatch per distinct embed URL, order preserved, blanks dropped: player.php
     *  serves the same iframe on sv1..sv3 for many videos (three identical links shipped
     *  before), and an empty iframe is not a source. */
    fun distinctEmbeds(embeds: Collection<String>): List<String> =
        embeds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Cloudflare interstitial detector (issue #411). Real challenge pages carry the
     *  "Just a moment…" title or the cf-chl/_cf_chl challenge markers; healthy pages
     *  must NOT match — since the #411 probe every healthy 200 page embeds the JSD
     *  loader (/cdn-cgi/challenge-platform/scripts/jsd/main.js), so the bare
     *  challenge-platform path is not a trigger. */
    fun isChallengePage(html: String): Boolean =
        html.contains("Just a moment") ||
            html.contains("_cf_chl_opt") ||
            html.contains("cf-chl") ||
            html.contains("challenge-platform/h/b")
}

/** Watch-page player config (issue #250). */
data class StreamConfig(val postId: Int, val nonce: String)

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class Cat3MoviePlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Cat3Movie())
        registerHostExtractors()
    }
}
