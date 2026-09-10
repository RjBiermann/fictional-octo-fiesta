package com.rjbiermann

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.fasterxml.jackson.databind.ObjectMapper
import com.kraptor.registerHostExtractors
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Cat3Movie : MainAPI() {
    override var mainUrl = "https://cat3movie.org"
    override var name = "Cat3Movie"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest",
        "$mainUrl/new-movies" to "New Movies",
        "$mainUrl/classic-porn" to "Classic Porn",
        "$mainUrl/classic-erotica" to "Classic Erotica",
        "$mainUrl/asian-erotica" to "Asian Erotica",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val home = Parse.homeCards(document).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? = try {
        val link = selectFirst("a.halim-thumb") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.attr("title").ifBlank { link.text() }.takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst("img[data-src]")?.attr("data-src")
        newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    } catch (e: Exception) {
        Log.d("Cat3Movie", "card parse: ${e.message}")
        null
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // /search/<slug> — no pagination on this site (page 2 is 404)
        val slug = query.trim().lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), "-")
        val document = app.get("$mainUrl/search/$slug").document
        val list = Parse.searchCards(document).mapNotNull { it.toSearchResult() }
        return newSearchResponseList(list, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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
        val recommendations = document.select("section.related-movies article.thumb").mapNotNull { it.toSearchResult() }

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
            app.get(data).document
        } catch (e: Exception) {
            Log.d("Cat3Movie", "watch page: ${e.message}")
            null
        } ?: return false

        // issue #250: body[data-nonce] is absent on CF-cached/older page shapes — the
        // halim_cfg "nonce":"<hex>" entry is the fallback; without a fallback loadLinks
        // used to return false and the player silently showed no sources
        val cfg = Parse.streamConfig(document) ?: return false
        val postId = cfg.postId.toString()
        val nonce = cfg.nonce

        // sv1..sv3 servers: hlsfree embeds yield a token stream URL, hlsfast embeds
        // yield an AES-CBC encrypted api response (see extractHlsFast); loadvid is blob-gated
        for (sv in 1..3) {
            try {
                // issue #250: player.php GETs are cached by Cloudflare and sit behind the
                // per-server watch page — cache-buster + correct referer dodge the recorded
                // CF-cached 404/{"status":true,"code":403} responses
                val playerHtml = app.get(
                    "$mainUrl/wp-content/themes/halimmovies/player.php",
                    referer = Parse.playerReferer(data, sv),
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
                val embed = Parse.embedIframe(playerHtml) ?: continue

                if (embed.contains("hlsfast.com/#")) {
                    val hash = embed.substringAfterLast('#')
                    val hlsFast = extractHlsFast(hash)
                    if (hlsFast != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = "HlsFast",
                                name = "HlsFast",
                                url = hlsFast,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://hlsfast.com/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } else {
                    // hlsfree token dance lives in the shared HlsFree adapter (issue #247:
                    // preflight + fresh-token retry + Referer header map); any other host
                    // family the registry has an adapter for now matches too
                    loadExtractor(embed, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.d("Cat3Movie", "sv$sv: ${e.message}")
            }
        }
        return true
    }

    companion object {
        private val mapper = ObjectMapper()

        // hlsfast.com SPA (issue #180): GET /api/v1/video returns an AES-CBC encrypted hex
        // blob; key/iv are constants generated in the obfuscated player JS
        // (assets/index-DqFBtoPY.js, Z()/J()). Response JSON has "cfNative" (proxied
        // through hlsfast.com) and "source" (direct IP) — prefer cfNative.
        private const val HLSFAST_KEY = "kiemtienmua911ca"
        private const val HLSFAST_IV = "1234567890oiuytr"

        private fun aesCbcDecryptHex(hex: String): String {
            val data = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(HLSFAST_KEY.toByteArray(), "AES"),
                IvParameterSpec(HLSFAST_IV.toByteArray())
            )
            return String(cipher.doFinal(data))
        }

        private suspend fun extractHlsFast(hash: String): String? = try {
            val body = app.get(
                "https://hlsfast.com/api/v1/video?id=$hash&w=1280&h=720&r=cat3movie.org",
                referer = "https://hlsfast.com/"
            ).text.trim()
            if (!body.matches(Regex("[0-9a-fA-F]+"))) return null
            val json = mapper.readTree(aesCbcDecryptHex(body))
            val url = json.get("cfNative")?.asText()?.takeIf { it.isNotBlank() }
                ?: json.get("source")?.asText()?.takeIf { it.isNotBlank() }
                ?: return null
            if (url.startsWith("http")) url else "https://hlsfast.com" + url
        } catch (e: Exception) {
            Log.d("Cat3Movie", "hlsfast: ${e.message}")
            null
        }
    }
}

/**
 * Pure card-parsing hooks (TDD-first, ADR-0005). The halimmovies theme renders the newest
 * posts twice on the homepage archive (top "Latest" strip + main grid); homeCards dedupes
 * by card href. Search pages have no duplicates.
 */
object Parse {
    fun homeCards(document: org.jsoup.nodes.Document): List<Element> =
        document.select("article.thumb")
            .filter { it.selectFirst("a.halim-thumb") != null }
            .distinctBy { it.selectFirst("a.halim-thumb")?.attr("href") }

    fun searchCards(document: org.jsoup.nodes.Document): List<Element> =
        document.select("article.thumb").toList()

    /** post_id + player nonce for the watch page ([data-post_id]/body[data-nonce], with the
     *  halim_cfg entries as fallbacks — issue #250's silent no-sources case). */
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

    /** Embed src from the player.php response, double or single quotes. */
    fun embedIframe(html: String): String? =
        Regex("iframe[^>]*src=[\"']([^\"']+)").find(html)?.groupValues?.get(1)
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
