package com.rjbiermann

import com.kraptor.SearchCard
import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// 7mmtv.sx — selectors, endpoints and the encrypted server-row scheme from FINDINGS.md
// (issue #452 probe 2026-09-22). The site builds its player embeds client-side from
// `mvarr` rows: XOR-23 base-2 chunks → base64 → AES-CBC with per-page key/IV vars.

object SevenMm {

    enum class Kind { EMBED, TURBOVID, PLAY }

    data class Row(val kind: Kind, val src: String)
    data class Meta(
        val title: String, val year: Int?, val duration: Int?,
        val actors: List<String>, val tags: List<String>,
        val plot: String?, val poster: String?,
    )

    /** Pure Parse function: video-page `mvarr` server rows → decrypted iframe targets.
     *  Page shape (content_mv_protect_ofb.js): each row is
     *  mvarr['<key>']=[['<frameId>','<enc0c1...>','<iframe-prefix>','<urlPrefix>','</iframe>',...]]
     *  with enc = '0'/'1'/'c' chars; split('c') chunks are base-2 ints XOR 23 → bytes,
     *  then base64 → AES-CBC decrypt with the page-local 16-char key (argdeqweqweqwe) /
     *  IV (hdddedg252), PKCS7. Targets:
     *   - urlPrefix https://emturbovid.com/t/ : decrypted value IS the full src
     *     (a 7mmtv /iframeencrypteda/ chain URL) → Kind.TURBOVID
     *   - urlPrefix //7mmtv.sx/.../play.php?id= : Kind.PLAY (server-direct m3u8s)
     *   - everything else (mmsi02.com, mmvh02.com, playmogo.com): Kind.EMBED → loadExtractor */
    fun serverRows(doc: Document): List<Row> {
        val html = doc.html()
        val key = Regex("(?:argdeqweqweqwe|hdddedd252|argdeqweqweqwz)\\s*=\\s*'([0-9a-f]{16})'").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val iv = Regex("(?:hdddedg252|hdddedf252|argdeqweqweqww)\\s*=\\s*'([0-9a-f]{16})'").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        val rows = mutableListOf<Row>()
        for (row in Regex("mvarr\\[[^]]+]=\\[\\[.*?\\],\\]", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)) {
            try {
                val blob = row.value
                val enc = Regex("'([01c]{20,})'").find(blob)?.groupValues?.get(1) ?: continue
                val prefix = Regex("'((?:https?:)?//[^']*?/(?:e|v|t)/|//[^']*?play\\.php\\?id=)'")
                    .findAll(blob).map { it.groupValues[1] }.firstOrNull()
                    ?.let { if (it.startsWith("//")) "https:$it" else it } ?: continue
                val b64 = enc.split("c").joinToString("") {
                    ((it.toInt(2)) xor 23).toChar().toString()
                }
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"),
                    IvParameterSpec(iv.toByteArray()))
                val pt = String(cipher.doFinal(Base64.getDecoder().decode(b64.trim()))).trim()
                if (pt.isEmpty()) continue

                val src = if (prefix == "https://emturbovid.com/t/") pt else prefix + pt
                val kind = when {
                    prefix.contains("play.php?id=") -> Kind.PLAY
                    prefix == "https://emturbovid.com/t/" -> Kind.TURBOVID
                    else -> Kind.EMBED
                }
                rows.add(Row(kind, src))
            } catch (e: Exception) { /* one broken row must not kill the list */ }
        }
        return rows
    }

    /** Pure Parse function: play.php player page → DISTINCT direct m3u8 urls. */
    fun playSources(doc: Document): List<String> =
        Regex("src:\\s*'([^'\\s]+\\.m3u8)\\s*'").findAll(doc.html())
            .map { it.groupValues[1] }.distinct().toList()

    /** Pure Parse function: the video page's meta block (title/date/duration/idols/genre/plot/cover). */
    fun meta(doc: Document): Meta {
        val title = doc.selectFirst("h1.fullvideo-title")?.text()?.trim() ?: ""
        val spans = doc.select("div.fullvideo-details div.d-flex > span").map { it.text() }
        val year = Regex("(\\d{4})-\\d{2}-\\d{2}").find(spans.joinToString(" "))
            ?.groupValues?.get(1)?.toInt()
        val duration = Regex("(\\d+)\\s*\\u5206").find(spans.joinToString(" "))
            ?.groupValues?.get(1)?.toInt()
        return Meta(
            title = title,
            year = year,
            duration = duration,
            actors = doc.select("div.fullvideo-idol > span > a").map { it.text().trim() }
                .filter { it.isNotBlank() },
            tags = doc.select("div.categories a").map { it.text().trim() }
                .filter { it.isNotBlank() },
            plot = doc.selectFirst("div.fullvideo-text article p")
                ?.text()?.trim()?.takeIf { it.isNotBlank() },
            poster = doc.selectFirst("div.content_main_cover img")?.absUrl("src")
                ?.takeIf { it.isNotBlank() },
        )
    }

    /** Related cards on a video page: div.video.video-related strip (SearchCard shape). */
    fun recs(doc: Document): List<com.kraptor.CardFields> =
        SearchCard.homeCards(doc, "div.video.video-related", "h3.video-title a")

    /** Listing / search result cards. The site legitimately repeats the same movie across
     *  its content pipelines in search results (censored + amateur pipelines) — dedupe. */
    fun cards(doc: Document): List<com.kraptor.CardFields> =
        SearchCard.homeCards(doc, "div.video", "h3.video-title a")
            .distinctBy { it.poster ?: it.href }
}

class SevenMmTv : MainAPI() {
    override var mainUrl = "https://7mmtv.sx"
    override var name = "7mmtv"
    override val hasMainPage = true
    override var lang = "ja"
    override val hasQuickSearch = false   // FINDINGS: no live-typing suggest endpoint on the site
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "censored" to "Censored",
        "uncensored" to "Uncensored",
        "amateurjav" to "Amateur",
        "reducing-mosaic" to "Reducing Mosaic",
        "amateur" to "Chinese AV",
    )

    // FINDINGS: every listing page is ${group}_latest/all/<n>.html with plain <n>.html pagination
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = SevenMm.cards(app.get("$mainUrl/en/${request.data}_latest/all/$page.html").document)
        return newHomePageResponse(
            HomePageList(name = request.name, list = home.map { f ->
                newMovieSearchResponse(f.title, fixUrl(f.href), TvType.NSFW) { posterUrl = fixUrlNull(f.poster) }
            }, isHorizontalImages = false),
            hasNext = home.isNotEmpty(),
        )
    }

    // FINDINGS: search results are GET-rendered at /en/searchall_search/all/<kw>/<n>.html
    // (the search form POSTs to searchform_search and lands on the same URL)
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = SevenMm.cards(
            app.get("$mainUrl/en/searchall_search/all/${android.net.Uri.encode(query)}/$page.html").document
        )
        return newSearchResponseList(results.map { f ->
            newMovieSearchResponse(f.title, fixUrl(f.href), TvType.NSFW) { posterUrl = fixUrlNull(f.poster) }
        }, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val m = SevenMm.meta(doc)
        val recommendations = SevenMm.recs(doc).filter { fixUrl(it.href) != url }.map { f ->
            newMovieSearchResponse(f.title, fixUrl(f.href), TvType.NSFW) { posterUrl = fixUrlNull(f.poster) }
        }
        return newMovieLoadResponse(m.title.ifBlank { url }, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(m.poster)
            this.tags = m.tags
            this.year = m.year
            this.duration = m.duration
            this.actors = m.actors.map { ActorData(Actor(it)) }
            this.plot = m.plot
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        for (row in SevenMm.serverRows(document)) {
            try {
                when (row.kind) {
                    // SP rows: play.php serves direct m3u8 sources in plain HTML
                    SevenMm.Kind.PLAY -> {
                        val player = app.get(fixUrl(row.src)).document
                        for (m3u8 in SevenMm.playSources(player)) callback.invoke(
                            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            })
                    }
                    // TV rows: decrypted src is 7mmtv's own /iframeencrypteda/ chain; it hops
                    // (iframe a → iframe b) to turbovidhls.com — resolved like Javmost's
                    // emturbovid stream (page-derived, same documented exception)
                    SevenMm.Kind.TURBOVID -> {
                        val wrapped = Regex("src='(//[^']*iframeencryptedb[^']*)'")
                            .find(app.get(row.src).text)?.groupValues?.get(1) ?: continue
                        val vhPage = app.get(fixUrl(wrapped), referer = data).text
                        val m3u8 = Regex("urlPlay\\s*=\\s*'([^']+m3u8[^']*)'").find(vhPage)?.groupValues?.get(1)
                            ?: Regex("data-hash=\"([^\"]+m3u8[^\"]*)\"").find(vhPage)?.groupValues?.get(1)
                            ?: continue
                        callback.invoke(
                            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                                this.referer = "https://turbovidhls.com/"
                                this.quality = Qualities.Unknown.value
                            })
                    }
                    // SW/VH rows: known registered host families (StreamHG/mmsi02, VidHide/mmvh02,
                    // Dood/playmogo) — framework dispatch only, never inline (ADR-0002)
                    SevenMm.Kind.EMBED -> loadExtractor(row.src, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                e.message?.let { android.util.Log.d(name, "loadLinks ${row.kind}: $it") }
            }
        }
        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class SevenMmTvPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(SevenMmTv())
        registerHostExtractors()
    }
}
