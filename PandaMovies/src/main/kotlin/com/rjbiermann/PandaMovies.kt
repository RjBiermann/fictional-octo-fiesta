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

    // #555: /movies 404s since the theme migration; the homepage itself is the live
    // Latest listing ("Latest Porn Movies" grid) and paginates via /page/N.
    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest",
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
        // #444 hardening: WP search intermittently serves garbage fuzzy pages (zero
        // query/title overlap, reproduced live in this issue). Retry once via the
        // equivalent `?s=` endpoint. The zero-overlap detector can't catch garbage
        // that happens to contain a query token — accepted residual.
        // ponytail: single retry per request; retry-loop if server junk persists.
        val slug = java.net.URLEncoder.encode(Parse.normalizeQuery(query.trim()), "UTF-8")
        var document = app.get(if (page <= 1) "$mainUrl/search/$slug" else "$mainUrl/search/$slug/page/$page").document
        val firstDoc = document
        var list = Parse.cards(document)
        if (Parse.queryMismatch(list, query)) {
            val retryDoc = app.get("$mainUrl/?s=$slug").document
            val retry = Parse.cards(retryDoc)
            if (!Parse.queryMismatch(retry, query)) {
                list = retry
                document = retryDoc
            } else {
                document = firstDoc
            }
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

    fun cards(document: org.jsoup.nodes.Document, fromRelated: Boolean = false): List<Card> =
        document.select(if (fromRelated) "div.grid--blk article.card" else "article.card")
            .mapNotNull { el ->
                try {
                    val anchor = el.selectFirst("a.card__th") ?: return@mapNotNull null
                    val title = (el.selectFirst(".card__t")?.text() ?: "").trim()
                    if (title.isBlank()) return@mapNotNull null
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    if (href.isBlank()) return@mapNotNull null
                    if (href.isBlank()) return@mapNotNull null
                    val poster = el.selectFirst("img.card__img")?.attr("src")
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
            tokens.any { wordBoundaryContains(title, it) }
        }
    }

    /** Strict token match: `token` in `title` at word boundaries (same normalization
     *  as [queryMismatch]). Word-boundary instead of substring containment: a
     *  false-negative keeps the reported bug alive, a false-positive retry is cheap
     *  (one request, falls back to the first response). */
    private fun wordBoundaryContains(title: String, token: String): Boolean =
        token in title.split(Regex("[^a-z0-9]+"))

    /** Is there a next page? (re-cut #555): listings serve 35 cards per page
     *  (was WP` posts_per_page=40`). A page with fewer cards is the last real page;
     *  continuing yields an empty card-less 200 (or a 404). No pagination block is
     *  rendered, so card count is the signal. Counts via [cards] — the same
     *  selector/dedup as the actual result list, so pager and list can't desynchronize. */
    fun hasNextPage(document: org.jsoup.nodes.Document): Boolean =
        cards(document).size >= 35

    /** One video page's LoadResponse fields (vid__ grammar, #555 — the `.mvic-thumb`/
     *  `.mvic-info` markup is gone). Title = breadcrumb `span.bc__c`, which matches
     *  the search-card title (the `h1.vid__t` is SEO-mangled). */
    fun videoPage(document: org.jsoup.nodes.Document): VideoPage {
        val doc = document
        val poster = doc.selectFirst("img.vid__poster[src]")?.attr("src")
            ?.takeIf { it.startsWith("http") && !it.startsWith("data:") }
        return VideoPage(
            title = doc.selectFirst("span.bc__c")?.text()?.trim() ?: "",
            poster = poster,
            plot = doc.selectFirst("div.entry.vid__txt")?.text()
                ?.takeIf { it.isNotBlank() },
            durationMin = minutes(doc.selectFirst("span.st--duration")?.text() ?: ""),
            year = doc.selectFirst("span.st--year")?.text()?.trim()?.toIntOrNull(),
            tags = doc.select("a.chip[href*=/genre/]").map { it.text() },
            actors = doc.select("a.chip--star[href*=/actor/]").map { it.text() },
        )
    }

    /** "5:05:00" / "25:00" → minutes; null when not a clock. */
    fun minutes(text: String): Int? {
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]                 // seconds dropped
            2 -> parts[0]
            else -> null
        }
    }

    /**
     * Watch embeds (#555): no more #pettabs anchors — the player's `section.hlm` holds a
     * JSON array in its `data-servers` attribute: [{"u":url,"t":"iframe","l":label,"h":hash}].
     * The LuluStream(/luluvid) and VOE registry rows are keyed to canonical hosts, so those
     * two get normalized like before. Evidence: FINDINGS-555.md.
     */
    fun embeds(document: org.jsoup.nodes.Document): List<String> {
        val raw = document.selectFirst("section.hlm[data-servers]")?.attr("data-servers") ?: ""
        val urls = try {
            val arr = com.fasterxml.jackson.databind.ObjectMapper().readTree(raw)
            (0 until arr.size()).mapNotNull { arr[it].findPath("u").textValue() }
        } catch (e: Exception) {
            emptyList()
        }
        return urls.map { url ->
            url.replace("://luluvid.com/e/", "://lulustream.com/")
                .replace("://voe.sx/e/", "://voe.sx/")
        }.distinct()
    }
}
