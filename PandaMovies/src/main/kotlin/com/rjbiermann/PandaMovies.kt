package com.rjbiermann

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.kraptor.registerHostExtractors

/**
 * PandaMovies (pandamovies.pw) — issue #421. WordPress + PsyPlay movie theme; movies embed
 * filehost mirrors (LuluStream/Dood/MixDrop/VOE/Playmate), all already covered by the shared
 * host registry. See FINDINGS.md in this directory for every selector below.
 */
class PandaMovies : MainAPI() {
    override var mainUrl = "https://pandamovies.pw"
    override var name = "PandaMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest",
        "$mainUrl/genre/18-teens" to "18+ Teens",
        "$mainUrl/genre/lesbian" to "Lesbian",
        "$mainUrl/genre/anal" to "Anal",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val home = Parse.cards(document).mapNotNull { it.toSearchResult(this@PandaMovies) }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            // WP `posts_per_page=40`: a full 40-card page may continue, a shorter one is
            // the real last page (continuing returns one extra empty card-less home/genre
            // 200; search URLs 404 past the end). Exact-multiples (40/80/…) still fire
            // one trailing request — the tail is shortened, not eliminated.
            hasNext = Parse.hasNextPage(document)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = { q: String ->
            val slug = java.net.URLEncoder.encode(Parse.normalizeQuery(q.trim()), "UTF-8")
            if (page <= 1) "$mainUrl/search/$slug" else "$mainUrl/search/$slug/page/$page"
        }
        var document = app.get(url(query)).document
        var list = Parse.cards(document)
        // #444 hardening: WP search intermittently serves garbage fuzzy pages (zero
        // query/title overlap, reproduced live in this issue). Retry once via the
        // equivalent `?s=` endpoint.
        if (page == 1 && Parse.queryMismatch(list, query)) {
            document = app.get("$mainUrl/?s=" + java.net.URLEncoder.encode(Parse.normalizeQuery(query.trim()), "UTF-8")).document
            val retry = Parse.cards(document)
            if (!Parse.queryMismatch(retry, query)) list = retry
        }
        return newSearchResponseList(
            list.mapNotNull { it.toSearchResult(this@PandaMovies) },
            hasNext = Parse.hasNextPage(document)
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val page = Parse.videoPage(document)

        return newMovieLoadResponse(page.title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(page.poster)
            this.year = page.year
            this.plot = page.plot
            this.tags = page.tags
            this.duration = page.durationMin
            this.recommendations = Parse.cards(document, fromRelated = true).mapNotNull { it.toSearchResult(this@PandaMovies) }
            addActors(page.actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.d(name, "watch page: ${e.message}")
            null
        } ?: return false

        Parse.embeds(document).forEach { embed ->
            try {
                loadExtractor(embed, mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d(name, "embed $embed: ${e.message}")
            }
        }
        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class PandaMoviesPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(PandaMovies())
        registerHostExtractors()
    }
}

private fun Parse.Card.toSearchResult(main: MainAPI): SearchResponse? = try {
    main.newMovieSearchResponse(title, href, TvType.NSFW) {
        this.posterUrl = poster
    }
} catch (e: Exception) {
    Log.d("PandaMovies", "card emit: ${e.message}")
    null
}

/**
 * Pure card/video/embed parsing (TDD-first, ADR-0005). Selector evidence: FINDINGS.md.
 */
object Parse {
    data class Card(val title: String, val href: String, val poster: String?)
    data class VideoPage(
        val title: String,
        val poster: String?,
        val plot: String?,
        val durationMin: Int?,
        val year: Int?,
        val tags: List<String>,
        val actors: List<String>,
    )

    /** Listing cards (search / home / genre / related share one shape): `div.ml-item`.
     *  PsyPlay renders lists twice on some pages (related strip + grid, preamble issue Cat3Movie
     *  homeCards dedup); dedupe by href. */
    fun cards(document: org.jsoup.nodes.Document, fromRelated: Boolean = false): List<Card> =
        document.select(if (fromRelated) "div.mlw-related div.ml-item" else "div.ml-item")
            .filter { if (fromRelated) true else it.closest("div.mlw-related") == null }
            .mapNotNull { el ->
                try {
                    val anchor = el.selectFirst("a.ml-mask") ?: return@mapNotNull null
                    val title = anchor.attr("oldtitle").ifBlank {
                        anchor.selectFirst("h2")?.text()
                    }?.trim().takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href") } ?: return@mapNotNull null
                    if (href.isBlank()) return@mapNotNull null
                    val poster = el.selectFirst("img")?.attr("src")
                        ?.takeIf { it.startsWith("http") && !it.startsWith("data:") }
                    Card(title, href, poster)
                } catch (e: Exception) {
                    null
                }
            }.distinctBy { it.href }

    /**
     * #444: normalize keyboard curly apostrophes to the straight form the site's
     * healthy search path uses. WP's fuzzy engine serves garbage pages for curly
     * variants; incumbent straight-apostrophe paths are proven by fixtures.
     */
    fun normalizeQuery(text: String): String =
        text.replace('\u2018', '\'').replace('\u2019', '\'')

    /**
     * #444 structural check: garbage result pages (transient WP/CDN responses) share
     * zero alphanumeric tokens (len >= 3, apostrophe-stripped, case-insensitive) with
     * the query — every healthy result page overlaps, fixtures prove it. Empty results
     * are a legitimate no-hit, never a mismatch.
     */
    fun queryMismatch(cards: List<Card>, query: String): Boolean {
        if (cards.isEmpty()) return false
        val tokens = normalizeQuery(query).lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()
        if (tokens.isEmpty()) return false
        return cards.none { c ->
            val title = normalizeQuery(c.title).lowercase()
            tokens.any { title.contains(it) }
        }
    }

    /** Is there a next page? (live, #425): WP `posts_per_page=40` — search and archive
     *  listings serve up to 40 cards per page. A page with fewer cards is the last
     *  real page; continuing yields an empty card-less 200 (or a search 404). No
     *  pagination block is rendered on these pages, so card count is the signal.
     *  Counts via [cards] — the same selector/related-exclusion/dedup as the actual
     *  result list, so pager and list can't desynchronize. */
    fun hasNextPage(document: org.jsoup.nodes.Document): Boolean =
        cards(document).size >= 40

    /** One video page's LoadResponse fields (`.mvic-thumb` / `h3[itemprop=name]` / `.mvic-info`). */
    fun videoPage(document: org.jsoup.nodes.Document): VideoPage {
        val doc = document
        val poster = doc.selectFirst("div.mvic-thumb img[src]")?.attr("src")
            ?.takeIf { it.startsWith("http") && !it.startsWith("data:") }
        val plot = doc.select("[itemprop=description].desc").text()
        val durationText = doc.select("div.mvic-info p").firstOrNull {
            it.text().startsWith("Duration")
        }?.text() ?: ""  // "Duration: 3 hrs. 42 mins."
        return VideoPage(
            title = doc.selectFirst("h3[itemprop=name]")?.text()?.trim() ?: "",
            poster = poster,
            plot = plot.ifBlank { null },
            durationMin = minutes(durationText.substringAfter(':')),
            year = doc.select("div.mvic-info a[href*=/release-year/]").first()?.text()
                ?.trim()?.toIntOrNull(),
            tags = doc.select("div.mvic-info p:contains(Genres) a[href*=/genre/]").map { it.text() },
            actors = doc.select("div.mvic-info a[href*=/actors/]").map { it.text() },
        )
    }

    /** "3 hrs. 42 mins." / "9 mins." → minutes. */
    fun minutes(text: String): Int? {
        val hours = Regex("(\\d+)\\s*hrs?").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)\\s*mins?").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (hours == 0 && mins == 0) null else hours * 60 + mins
    }

    /**
     * Watch embeds from the #pettabs "Watch Online" table (anchors `id="#iframe"`; the
     * Download table uses id="newtabforced"). Anchor hrefs point at mirror domains; the
     * LuluStream and Voe registry rows are keyed to the canonical hosts (data-fl-url), so
     * those two get normalized. Evidence: FINDINGS Stream sources.
     */
    fun embeds(document: org.jsoup.nodes.Document): List<String> =
        document.select("a[id=#iframe]").map { it.absUrl("href").ifBlank { it.attr("href") } }
            .map { url ->
                url.replace("://luluvid.com/e/", "://lulustream.com/")
                    .replace("://voe.sx/e/", "://voe.sx/")
            }
            .distinct()
}
