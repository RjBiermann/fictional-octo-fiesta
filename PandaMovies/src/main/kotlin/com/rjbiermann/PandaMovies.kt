package com.rjbiermann

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.kraptor.registerHostExtractors

/**
 * PandaMovies (pandamovies.pw) — issue #421. WordPress + PsyPlay movie theme; movies embed
 * host registry. See FINDINGS-555.md (2026-10-13 rewrite, issue #555) and the
 * run8 FINDINGS.md in this directory.
 */
class PandaMovies : MainAPI() {
    override var mainUrl = "https://pandamovies.pw"
    override var name = "PandaMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

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

    /** Listing cards (search / home / genre / related share one shape): #555 BEM
     *  `article.card` grammar (replaced PsyPlay `div.ml-item`). Related lives in the
     *  "Similar titles" `section.sec`. Dedupe by href. */
    fun cards(document: org.jsoup.nodes.Document, fromRelated: Boolean = false): List<Card> =
        document.select(if (fromRelated) "section.sec article.card" else "article.card")
            .filter { if (fromRelated) true else it.closest("section.sec") == null }
            .mapNotNull { el ->
                try {
                    // theme mixes h2/h3 for card titles across surfaces — class only
                    val titleEl = el.selectFirst(".card__t a") ?: return@mapNotNull null
                    val title = titleEl.text()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val href = (el.selectFirst("a.card__th") ?: titleEl)
                        ?.absUrl("href")?.ifBlank { null } ?: return@mapNotNull null
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

    /** Is there a next page? (#555): every listing surface (home / search / genre /
     *  category / `?s=` search) serves a `nav.pg` block with an explicit
     *  `a.next` anchor when more pages exist — anchor presence, not card count
     *  (per-page counts vary: 49 home, 35 elsewhere). A last page is a plain
     *  page with cards but no `a.next` (WP serves 404 only past the end). */
    fun hasNextPage(document: org.jsoup.nodes.Document): Boolean =
        document.selectFirst("a.next.page-numbers") != null

    /** One video page's LoadResponse fields (#555 `article.vid` grammar).
     *  Title from the breadcrumb (`.bc__c` — clean name; `h1.vid__t` is noisy), the rest
     *  from the info strip and details panel. */
    fun videoPage(document: org.jsoup.nodes.Document): VideoPage {
        val doc = document
        val details = doc.selectFirst("div.dp__panel[data-panel=details]")
        return VideoPage(
            title = doc.selectFirst("span.bc__c")?.text()?.trim()
                ?: doc.selectFirst("h1.vid__t")?.text()?.trim() ?: "",
            poster = doc.selectFirst("img.hlm-screen__poster[src]")?.attr("src")
                ?.takeIf { it.startsWith("http") && !it.startsWith("data:") },
            plot = doc.selectFirst("div.vid__txt p")?.text()?.ifBlank { null },
            durationMin = clockMinutes(doc.selectFirst("span.st--duration")?.text()),
            year = details?.selectFirst("a[href*=/release-year/]")?.text()?.trim()?.toIntOrNull()
                ?: doc.selectFirst("span.st--year")?.text()?.trim()?.toIntOrNull(),
            tags = details?.select("a[href*=/genre/]")?.map { it.text() } ?: emptyList(),
            actors = details?.select("a.chip--star")?.map { it.text() } ?: emptyList(),
        )
    }

    /** #555 clock durations: "4:00:00" → 240; "1:00" → 1; else null. Assumption:
     *  a two-part clock is H:MM (hours), not MM:SS — FINDINGS-555 only sampled
     *  three-part forms, so live evidence is thin either way; MM:SS misreads
     *  minutes as hours, H:MM misreads at most 59 minutes. Re-check if live
     *  two-part samples ever appear. */
    fun clockMinutes(text: String?): Int? {
        if (text == null) return null
        val parts = text.trim().split(':')
        val n = parts.map { it.toIntOrNull() }
        return when {
            n.size == 3 && n.all { it != null } -> n[0]!! * 60 + n[1]!!
            n.size == 2 && n.all { it != null } -> n[0]!!
            else -> null
        }
    }

    /**
     * Watch embeds: #555 player is `section.hlm[data-servers]` — a JSON array with the
     * stream hosts at key "u" (labels: DoodStream/MixDrop/…; download rows
     * (Rapidgator/NitroFlare) are separate anchors and not eligible).
     */
    fun embeds(document: org.jsoup.nodes.Document): List<String> =
        document.select("section.hlm[data-servers]")
            .map { it.attr("data-servers") }
            .flatMap { json ->
                // JSON via Jackson convention; parse failure → no embeds for this
                // section, never a regex silently missing a formatting variance
                try {
                    com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
                        .let { it as com.fasterxml.jackson.databind.node.ArrayNode }
                        .mapNotNull { n -> n.get("u")?.asText() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .map { url ->
                url.replace("://luluvid.com/e/", "://lulustream.com/")
                    .replace("://voe.sx/e/", "://voe.sx/")
            }
            .distinct()
}
