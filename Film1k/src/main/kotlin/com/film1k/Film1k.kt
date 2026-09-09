package com.film1k

import com.kraptor.registerHostExtractors
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Film1k : MainAPI() {
    override var mainUrl = "https://www.film1k.com"
    override var name = "Film1k"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Movies",
        "$mainUrl/category/action" to "Action",
        "$mainUrl/category/comedy" to "Comedy",
        "$mainUrl/category/horror" to "Horror",
        "$mainUrl/category/drama" to "Drama",
        "$mainUrl/category/thriller" to "Thriller",
    )

    private fun pageUrl(url: String, page: Int): String = when {
        page <= 1 -> url
        url == mainUrl -> "$mainUrl/page/$page"
        else -> "$url/page/$page"
    }

    private fun org.jsoup.nodes.Element.toResult(): SearchResponse? {
        val a = this.selectFirst("header.entry-header > a") ?: return null
        val href = a.attr("href") ?: return null
        val title = this.selectFirst("h2.entry-title")?.text()?.trim() ?: return null
        val img = this.selectFirst("figure img")
        val poster = img?.attr("data-src")?.takeIf { it.contains("img.film1k.com") }
            ?: img?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    private fun parseList(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.loop-post").mapNotNull {
            try { it.toResult() } catch (e: Exception) { null }
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page)).document
        val items = parseList(doc)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = app.get(url).document
        val items = parseList(doc)
        return newSearchResponseList(items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: throw ErrorLoadingException("No title at $url")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val runtime = doc.selectFirst("strong:containsOwn(Runtime)")?.nextSibling()?.toString()
            ?.removePrefix(": ")?.trim()
        val tags = doc.selectFirst("strong:containsOwn(Genres)")?.nextSibling()?.toString()
            ?.removePrefix(": ")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val actors = doc.selectFirst("strong:containsOwn(Actors)")?.nextSibling()?.toString()
            ?.removePrefix(": ")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actors.map { ActorData(Actor(it)) }
            addDuration(runtime?.replace("mins", "min")?.trim())
        }
    }

    // ---- Byse (film1k.xyz) playback chain ----
    // page embeds <video><source src="https://film1k.xyz/e/{code}/{title}.mp4"> and an
    // iframe data-src variant; only the code matters. Playback is PoW-protected.
    private fun b64url(s: String): ByteArray {
        val padded = s.replace("-", "+").replace("_", "/") + "=".repeat((4 - s.length % 4) % 4)
        return Base64.decode(padded, Base64.NO_WRAP)
    }

    private fun solvePow(nonce: String, difficulty: Int): String? {
        val base = nonce + ":"
        for (s in 0 until 5_000_000) {
            val bytes = (base + s).toByteArray(Charsets.ISO_8859_1)
            val inp = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
            var bits = 0
            for (v in ByseHash.hash(inp)) {
                if (v == 0) { bits += 32; continue }
                bits += Integer.numberOfLeadingZeros(v)
                break
            }
            if (bits >= difficulty) return s.toString()
        }
        return null
    }

    object ByseHash {
        private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))
        private fun imul(a: Int, b: Int) = (a.toLong() * b.toLong()).toInt()

        fun hash(t: IntArray): IntArray {
            var e0 = 0x6A09E667.toInt(); var e1 = 0xBB67AE85.toInt()
            var e2 = 0x3C6EF372.toInt(); var e3 = 0xA54FF53A.toInt()
            fun ye() {
                e0 += e1
                e3 = rotl(e3 xor e0, 16)
                e2 += e3
                e1 = rotl(e1 xor e2, 12)
                e0 += e1
                e3 = rotl(e3 xor e0, 8)
                e2 += e3
                e1 = rotl(e1 xor e2, 7)
            }
            for (v in t) {
                e0 += v
                e0 = rotl(e0, 7)
                ye()
            }
            for (i in 0 until 8) ye()
            val r = IntArray(512)
            for (i in 0 until 512) { ye(); r[i] = e0 xor e2 }
            for (k in 0 until 2) for (s in 0 until 512) {
                val a = r[s] and 511
                var c = r[s] + r[a]
                c = rotl(c, 13)
                c = c xor imul(r[(s + 1) and 511], 0x9E3779B1.toInt())
                r[s] = c
                e0 = e0 xor c
                ye()
            }
            val n = IntArray(8)
            val o = 512 / 8
            for (i in 0 until 8) {
                ye()
                var s = e0
                val a = i * o
                for (c in 0 until o) {
                    val d = r[a + c]
                    s += d
                    s = rotl(s, 5)
                    s = s xor imul(d, 0x85EBCA77.toInt())
                }
                n[i] = s xor e2
            }
            return n
        }
    }

    private suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        try {
            val res = app.post(
                url,
                requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()),
                headers = headers
            )
            if (res.code in 200..299) JSONObject(res.text) else null
        } catch (e: Exception) {
            null
        }

    private suspend fun bysePlayback(code: String): Pair<String, String>? {
        val api = "https://film1k.xyz/api/videos/$code/embed/"
        val captcha = postJson(api + "captcha/", "{}") ?: return null
        val solution = solvePow(captcha.getString("pow_nonce"), captcha.getInt("pow_difficulty"))
            ?: return null
        val verify = postJson(
            api + "captcha/verify/",
            """{"pow_token":"${captcha.getString("pow_token")}","solution":"$solution"}"""
        ) ?: return null
        val token = verify.optString("token").takeIf { it.isNotEmpty() } ?: return null
        val resp = postJson(
            api + "playback/",
            """{"fingerprint":{}}""",
            mapOf("X-Captcha-Token" to token)
        ) ?: return null
        val pb = resp.optJSONObject("playback") ?: return null

        // key = b64url(key_parts[version]) + b64url(key_parts[31 - version])
        val version = pb.getString("version").toInt()
        val parts = pb.getJSONArray("key_parts")
        val iv = b64url(pb.getString("iv"))
        val payload = b64url(pb.getString("payload"))
        val p1 = b64url(parts.getString(version - 1))
        val p2 = b64url(parts.getString(31 - version - 1))
        val key = p1.copyOf(p1.size + p2.size).also { System.arraycopy(p2, 0, it, p1.size, p2.size) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(payload)
        val json = JSONObject(String(plain, Charsets.UTF_8))
        val sources = json.optJSONArray("sources") ?: return null
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val streamUrl = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val label = s.optString("label", "").ifEmpty { "Auto" }
            return streamUrl to label
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val code = Regex("""film1k\.xyz/e/([a-zA-Z0-9]+)/""").find(html)?.groupValues?.get(1)
            ?: return false
        val (streamUrl, quality) = bysePlayback(code) ?: return false
        callback(
            newExtractorLink(
                name = name,
                source = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://film1k.xyz/"
                this.quality = getQualityFromName(quality)
            }
        )
        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class Film1kPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Film1k())
        registerHostExtractors()
    }
}
