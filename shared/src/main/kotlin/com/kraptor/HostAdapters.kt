// Extractor adapters that used to live inside provider directories, moved to
// shared code for the Host registry (ADR-0002) — every provider can now match
// these embed hosts. Includes the HlsFree family (Cat3Movie token dance).
package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * Shared Cloudflare-challenge interceptor (audit finding 2 — was copied as an inner
 * class in Cat3Film, Cat3Movie and FullPorner). Wraps every request: when the response
 * body looks like a challenge page, CloudflareKiller solves it and the request replays
 * with the cf_clearance cookie. [isChallenge] lets a provider pin its own marker set
 * (Cat3Movie serves interstitial markers beyond "Just a moment"); the default checks
 * the literal "Just a moment" phrase, same as the old copies did.
 */
fun cfChallenge(
    killer: CloudflareKiller,
    isChallenge: (String) -> Boolean = { Jsoup.parse(it).html().contains("Just a moment") }
): Interceptor = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (isChallenge(response.peekBody(1024 * 1024).string())) {
        killer.intercept(chain)
    } else {
        response
    }
}

open class MyDaddyExtractor : ExtractorApi() {
    override val name = "MyDaddy"
    override val mainUrl = "https://mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text

        val regex = Regex(pattern = "a href='([^']*)'", options = setOf(RegexOption.IGNORE_CASE))

        val videolar = regex.findAll(response)

        videolar.forEach { video ->
            val video = video.groupValues[1]
            callback.invoke(newExtractorLink(
                this.name,
                this.name,
                fixUrl(video),
                type = INFER_TYPE
            ) {
                this.referer = "$referer"
                this.quality = getQualityFromName(video.substringAfterLast("/").substringBefore("."))
            })
        }

    }
}

// vidara embed (jav.guru searcho mirror "cd"): POST /api/stream with the
// filecode from /e/<filecode> returns {"streaming_url": "<m3u8>", ...}
// open: the .so mirror row (#657) subclasses it for loadExtractor's mainUrl dispatch
open class Vidara : ExtractorApi() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val filecode = url.substringAfterLast("/e/").substringBefore(".")
        if (filecode.isBlank()) return null
        val res = app.post(
            "$mainUrl/api/stream",
            json = mapOf("filecode" to filecode, "device" to "web"),
            referer = referer
        ).text
        val streamUrl = Regex("\"streaming_url\"\\s*:\\s*\"([^\"]+)\"").find(res)?.groupValues?.get(1)
            ?: return null
        return listOf(
            newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer ?: "$mainUrl/"
            }
        )
    }
}

// vidara.so — Mangoporn's default data-servers host since 2026 (#657 FINDINGS:
// video-1 data-default="0" → vidara.so/e/<filecode>; the API contract is the
// same POST /api/stream). A distinct TLD needs its own row or loadExtractor's
// startsWith(mainUrl) dispatch drops the link (multi-host fallback rule).
class VidaraSo : Vidara() {
    override var mainUrl = "https://vidara.so"
}

// javhdz.today embed host — primary data-embed target on javseen.tv.
// Same playlist player family as SavedVids (see FINDINGS #118/#145); Cloudflare
// blocks datacenter IPs so this can only be confirmed in-app.
open class Javhdz : ExtractorApi() {
    override val name = "Javhdz"
    override val mainUrl = "https://stream3.javhdz.today"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer ?: "https://javseen.tv/").text
        val m3u8 = Regex("\"playlist\":\\s*\"([^\"]+\\.m3u8[^\"]*)\"").find(res)?.groupValues?.get(1) ?: return
        callback.invoke(
            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = referer ?: "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class Javhdz2 : Javhdz() {
    override val name = "Javhdz2"
    override val mainUrl = "https://stream2.javhdz.today"
}

open class PerverZijaExtractor : ExtractorApi() {
    private companion object {
        const val TAG = "kraptor_PerverZijaExtract"
    }

    override var name = "PerverZija"
    // player iframe subdomain varies per video (pervlN/pervmN/j2/perv...); referer must be the player domain
    override var mainUrl = "https://pervl2.xtremestream.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "url = $url")

        val ref = referer ?: url.substringBefore("/player/") + "/"
        val playerHtml = try {
            app.get(url, referer = ref).text
        } catch (e: Exception) {
            // An unreachable player page cannot be classified; emitting the tokenless
            // master here would be the decoy on a gated video (FINDINGS-906).
            Log.d(TAG, "stage=player failed: ${e.message}")
            return
        }

        // #908: one structural fallback chain (XsftkParse.playerPlan) instead of the
        // old "boot == null → xs1.php" branch. The legacy master is only for pages
        // without the FTK gate (the 16/336 live plain-TS family); on a gated page it
        // is a DECOY — playlist 200, every segment 404s, the owner's 2004 — so a
        // gated page whose boot call is missing fails closed (FINDINGS-906).
        when (val plan = XsftkParse.playerPlan(url, playerHtml)) {
            is XsftkParse.PlayerPlan.Legacy -> callback.invoke(newExtractorLink(
                source = name,
                name = name,
                url = plan.masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url.substringBefore("/player/") + "/"
                this.quality = Qualities.Unknown.value
            })

            XsftkParse.PlayerPlan.FailClosed ->
                Log.d(TAG, "stage=player failed: gated player without boot call (decoy suppressed, no source)")

            is XsftkParse.PlayerPlan.Xsftk -> emitXsftk(url, ref, plan.boot, callback)
        }
    }

    /** Booted gated player: decode the XOR'd manifest, then emit one synthesized VOD
     *  playlist per buildable quality. No sign/seg round-trip in-app — the AES-128-CBC
     *  `_enc` segment files sit OPEN on the CDN, encrypted with the op=key bytes and
     *  IV = BE(segment index) = the HLS media-sequence default, so
     *  #EXT-X-KEY URI=op=key + in-order segments replay natively (#845/#846).
     *  The key fetch must carry X-XS-P: 1 (403 without) — it travels on the link
     *  headers map, same contract as HlsFree (#247).
     *
     *  #1328/S2: the manifest's `cdn` list is a redundancy pool (segment bytes verified
     *  identical on all 45 reported hosts, two families — FINDINGS-1328 §1), so the CDN
     *  host is now *selected*, not assumed to be `cdn[0]`: one blocked/unresolvable host
     *  used to take down every quality of the video at once (the connect-level 2001 shape
     *  of the report). XsftkParse.cdnSources is the whole pure decision — probe a
     *  deterministic round-robin order, emit every buildable quality of the first live
     *  host, then offer the other family once as a manual fallback source. */
    private suspend fun emitXsftk(
        url: String,
        ref: String,
        boot: XsftkParse.Boot,
        callback: (ExtractorLink) -> Unit
    ) {
        val json = try {
            XsftkParse.decodeXsManifest(
                app.get(XsftkParse.protoApiUrl(url, boot, "manifest"), referer = ref).body.bytes(),
                boot
            )
        } catch (e: Exception) {
            Log.d(TAG, "stage=manifest failed: ${e.message}")
            return
        }
        if (json == null) {
            Log.d(TAG, "stage=manifest failed: decode failed for ${boot.id}")
            return
        }

        // #1328/S1 keeps its diagnostics: onSkip names every quality the manifest cannot
        // build, and the emission log names the selected host per source. An all-dead
        // roster (or a manifest with no buildable quality) emits NOTHING — never a decoy,
        // never a tokenless xs1.php on a gated page (#906's rule stands).
        val keyHost = url.substringAfter("://", url).substringBefore('/')
        val roster = XsftkParse.cdnHosts(json)
        var skipped = 0
        var probed = false
        val sources = XsftkParse.cdnSources(json, boot, url, onSkip = { plan ->
            skipped++
            Log.d(TAG, "skip q=${plan.label} reason=${plan.reason ?: "unbuildable"}")
        }) { host, quality ->
            probed = true
            cdnSegmentAlive(host, boot.id, quality)
        }
        if (sources.isEmpty()) {
            Log.d(
                TAG,
                when {
                    roster.isEmpty() -> "stage=cdn failed: manifest has no cdn roster"
                    !probed -> "stage=playlist failed: no buildable quality " +
                        "(qualities_skipped=$skipped)"
                    else -> "stage=cdn failed: no live cdn host (roster=${roster.size}, " +
                        "maxProbes=${XsftkParse.MAX_PROBES})"
                }
            )
            return
        }
        // Loopback host diagnostics (#1353 review): a failed accept/serve would otherwise
        // leave no plugin-side trace for the next 2001 report.
        XsftkPlaylistServer.logger = { message -> Log.d(TAG, "stage=playlist host: $message") }
        for (source in sources) {
            val playlist = source.playlist
            // #1353: the playlist is hosted on loopback and the player gets an http URL.
            // A `data:` URI never plays — the app's Cronet/OkHttp datasource answers
            // net::ERR_UNKNOWN_URL_SCHEME, surfaced as 2001 (FINDINGS-1353).
            val uri = try {
                XsftkPlaylistServer.publish(playlist)
            } catch (e: Exception) {
                Log.d(TAG, "stage=playlist failed: loopback host (${e.javaClass.simpleName})")
                continue
            }
            callback.invoke(newExtractorLink(
                source = name,
                // the fallback family is the same video: source label unchanged, link
                // name marked so the app's source sheet shows the manual fallback
                name = if (source.alt) "$name (alt)" else name,
                url = uri,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = ref
                this.headers = mapOf("X-XS-P" to "1")
                this.quality = source.quality.toIntOrNull() ?: Qualities.Unknown.value
            })
            Log.d(
                TAG,
                "source q=${source.quality}${if (source.alt) " alt=1" else ""} " +
                    "cdn=${source.cdnHost} key=$keyHost " +
                    "segs=${playlist.lineSequence().count { it.startsWith("#EXTINF:") }} " +
                    "bytes=${playlist.toByteArray(Charsets.UTF_8).size}"
            )
        }
    }

    /** Segment-0 preflight for one candidate CDN host (#1328/S2). The `_enc` segment
     *  files sit open on the CDN (no token, no referer — FINDINGS-840/1328), so a 200 or
     *  206 on the first 1 KiB of the highest buildable quality proves the host is
     *  reachable *and* serving this video. A DNS/connect failure is exactly the 2001
     *  shape being hardened against, so any exception is a rejection, never a crash. */
    private suspend fun cdnSegmentAlive(host: String, id: String, quality: String): Boolean = try {
        val code = app.get(
            XsftkParse.xsSegmentUrl(host, id, quality, 0),
            headers = mapOf("Range" to "bytes=0-1023")
        ).code
        code == 200 || code == 206
    } catch (e: Exception) {
        Log.d(TAG, "cdn probe failed: $host (${e.javaClass.simpleName})")
        false
    }
}

// hlsfree.com embed (Cat3Movie): the embed page exposes
// defaultHlsUrl = "...token=<hex>"; /api/hls/serve?token=<hex> serves the m3u8.
open class HlsFree : ExtractorApi() {
    override val name = "HlsFree"
    override val mainUrl = "https://hlsfree.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // issue #247: every leg (embed page, token playlist, segments) is referer-gated to
        // hlsfree.com and can 403/5xx per token. Preflight the manifest here and retry once
        // with a fresh embed-token; drop the source if the chain never yields a real playlist
        // — handing a bare token URL to the player surfaced as ExoPlayer 2004
        // ERROR_CODE_IO_BAD_HTTP_STATUS. Link referer + header map stay on hlsfree.com so
        // segment fetches keep the header regardless of datasource path.
        for (attempt in 1..2) {
            val token = try {
                HlsFreeParse.hlsfreeToken(app.get(url, referer = referer ?: "$mainUrl/").text)
            } catch (e: Exception) {
                Log.d(name, "embed: ${e.message}")
                null
            } ?: return
            val serve = "$mainUrl/api/hls/serve?token=$token"
            val res = try {
                app.get(serve, referer = "$mainUrl/")
            } catch (e: Exception) {
                Log.d(name, "attempt $attempt: ${e.message}")
                continue
            }
            if (HlsFreeParse.isPlayableManifest(res.code, res.text)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = serve,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Referer" to "$mainUrl/")
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
            Log.d(name, "attempt $attempt: token $token -> ${res.code}")
        }
    }
}

/** Pure hlsfree chain helpers (issue #247) kept free of CloudStream framework types so
 *  they load on the unit-test classpath (TDD-first, ADR-0005). */
object HlsFreeParse {
    /** hlsfree embed page embeds the token dance in `defaultHlsUrl = "...token=<hex>"`. */
    fun hlsfreeToken(embedHtml: String): String? =
        // token here is the hlsfree API query param, not a secret (Sonar S6418).
        Regex("defaultHlsUrl\\s*=\\s*\"[^\"]*token=([a-f0-9]+)\"") // NOSONAR
            .find(embedHtml)?.groupValues?.get(1)

    /** A healthy hlsfree serve response is HTTP 2xx with an #EXTM3U body — anything else
     *  (CF 403 HTML, 429 view-limit, 500 Proxy error) is a dead source for issue #247. */
    fun isPlayableManifest(code: Int, body: String): Boolean =
        code in 200..299 && body.contains("#EXTM3U")
}

// www. variant of the hlsfree embed URL (loadExtractor matches by mainUrl prefix).
class HlsFreeWww : HlsFree() {
    override val name = "HlsFree"
    override val mainUrl = "https://www.hlsfree.com"
}

/** Pure abyssplayer.com helpers (issue #233) — test-safe (no org.json / android stubs). */
object AbyssParse {
    /** The embed page carries the whole player config base64-encoded in `const datas = "..."`. */
    fun datasFromEmbed(embedHtml: String): String? =
        Regex("""const\s+datas\s*=\s*"([^"]+)"""") // datas is a config blob, not a secret (Sonar S6418)
            .find(embedHtml)?.groupValues?.get(1)

    /** Map the dec-abyss response to playable sources; status!=true entries are dead. */
    fun sourcesFromResult(resultJson: String): List<AbyssSource> = try {
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(resultJson)
        val sources = root.path("result").path("sources")
        (0 until sources.size()).mapNotNull { i ->
            val s = sources.get(i) ?: return@mapNotNull null
            val url = s.path("url").asText("")
            if (!s.path("status").asBoolean(false) || !url.startsWith("http")) return@mapNotNull null
            AbyssSource(url, Regex("\\d{3,4}").find(s.path("type").asText(""))?.value?.toIntOrNull() ?: 0)
        }
    } catch (e: Exception) {
        emptyList()
    }

    data class AbyssSource(val url: String, val quality: Int)
}

/** abyssplayer.com (issue #233): SoTrym/jwplayer embed whose `datas` blob carries the
 *  encrypted media config. The site's obfuscated core.bundle.js decrypts it locally
 *  (AES-256-CTR, key = md5hex-ASCII("user_id:slug:md5_id"), IV = key[0..16]) and builds
 *  sora CDN links with a second md5/CTR token — that token scheme is NOT reproduced here;
 *  the public enc-dec.app decrypt API does the full chain instead. */
open class AbyssPlayer : ExtractorApi() {
    override val name = "AbyssPlayer"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.d(name, "embed: ${e.message}")
            return
        }
        val datas = AbyssParse.datasFromEmbed(html) ?: return
        val res = try {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                json = mapOf("text" to datas),
                headers = mapOf("Origin" to "https://enc-dec.app")
            ).text
        } catch (e: Exception) {
            Log.d(name, "dec-abyss: ${e.message}")
            return
        }
        for (s in AbyssParse.sourcesFromResult(res)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = s.url,
                    type = if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(if (s.quality > 0) "${s.quality}p" else "")
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mainUrl
                    )
                }
            )
        }
    }
}

/** xvideos.com/embedframe/<id> — SSR hub-embed host (issue #778, spec round of #708):
 *  ixiporn's shape-B pages (2021–2023 import era) base64-wrap this iframe via
 *  clean-tube-player/player-x.php. The embed page is server-rendered and carries the
 *  player config as html5player.setVideo* calls (2 × mp4 qualities + HLS, live capture
 *  61306335, probe 2026-10-21). Targets deleted upstream answer a 1049-byte
 *  "This video has been deleted" shell — the adapter emits no links for those: no
 *  throw, no best-effort dead URLs. Parsing lives in the pure XvideosParse seam. */
open class Xvideos : ExtractorApi() {
    override val name = "Xvideos"
    override val mainUrl = "https://www.xvideos.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val html = try {
            app.get(url, referer = referer ?: "$mainUrl/").text
        } catch (e: Exception) {
            Log.d(name, "embed: ${e.message}")
            return emptyList()
        }
        // Deleted-shell contract (#778): the shell has no setVideo* calls, so streams
        // returns empty either way — the explicit gate documents intent.
        if (XvideosParse.isDeletedShell(html)) return emptyList()
        return XvideosParse.streams(html).map { s ->
            newExtractorLink(
                source = name,
                name = name,
                url = s.url,
                type = if (s.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf("Referer" to "$mainUrl/")
                this.quality = if (s.isHls) Qualities.Unknown.value else getQualityFromName("${s.quality}p")
            }
        }
    }
}

// player. subdomain of the abyssplayer embed host (Cat3Film #613 Story 2, FINDINGS
// 2026-10-15): the site's sources-API backup embed lives at player.abyssplayer.com/<id>,
// and loadExtractor's primary dispatch is startsWith(target, stripped mainUrl) — the
// subdomain never prefix-matches the apex row, so without an explicit row the backup
// route only resolves via the framework's fuzzy Levenshtein fallback. HlsFreeWww precedent.
class AbyssPlayerEmbed : AbyssPlayer() {
    override val name = "AbyssPlayer"
    override val mainUrl = "https://player.abyssplayer.com"
}
