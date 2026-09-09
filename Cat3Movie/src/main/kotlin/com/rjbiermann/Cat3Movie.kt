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
        val home = document.select("article.thumb").mapNotNull { it.toSearchResult() }
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
        val list = document.select("article.thumb").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(list, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: url.substringAfterLast('/')
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("p.released a[href*=/release/]")?.text()?.trim()?.toIntOrNull()
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
        val document = app.get(data).document
        val postId = Regex("\"post_id\":(\\d+)").find(document.html())?.groupValues?.get(1)
            ?: document.selectFirst("[data-post_id]")?.attr("data-post_id") ?: return false
        val nonce = document.selectFirst("body[data-nonce]")?.attr("data-nonce") ?: return false
        val slug = data.substringAfterLast('/')

        // sv1..sv3 servers: hlsfree embeds yield a token stream URL, hlsfast embeds
        // yield an AES-CBC encrypted api response (see extractHlsFast); loadvid is blob-gated
        for (sv in 1..3) {
            try {
                val playerHtml = app.get(
                    "$mainUrl/wp-content/themes/halimmovies/player.php",
                    referer = "$mainUrl/watch-$slug/full-sv$sv.html",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    params = mapOf(
                        "episode_slug" to "full",
                        "server_id" to sv.toString(),
                        "subsv_id" to "",
                        "post_id" to postId,
                        "nonce" to nonce,
                        "custom_var" to "",
                    )
                ).text
                val embed = Regex("iframe[^>]*src=\"([^\"]+)\"").find(playerHtml)?.groupValues?.get(1) ?: continue

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
                    // hlsfree (token dance lives in the shared HlsFree adapter); any
                    // other host family the registry has an adapter for now matches too
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

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class Cat3MoviePlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Cat3Movie())
        registerHostExtractors()
    }
}
