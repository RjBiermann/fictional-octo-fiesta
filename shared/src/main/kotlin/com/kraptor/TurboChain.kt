// hlsfast.com → turboembed.com chain (Cat3Movie sv1..sv3, issue #1448 audit).
//
// The old bespoke Cat3Movie branch read an AES-CBC hex blob from
// `hlsfast.com/api/v1/video` (issue #180). That endpoint now answers 404 on every hash;
// the live player page (https://hlsfast.com/#<hash>) resolves through a public redirect
// API instead, and the destination host changes without notice (2026-11-23: turboembed.com,
// served from `host2: turbovio` — the JSON still carries the retired `upn` slot):
//
//   GET https://hlsfast.com/api/public/resolve?file1=<hash>
//     → {"ok":true,"url":"https://turboembed.com/<code>", ...}
//   GET https://turboembed.com/api/download/tiers?s=<code>
//     → {"data":{"tiers":[{"file":"540p","label":"540p","height":480,...}]}}
//   GET https://turboembed.com/api/download/get?s=<code>&t=<tier>
//     → 302 → signed mp4 CDN URL (206 video/mp4, Range-capable)
//
// Both hops live in the registry (ADR-0002) so the provider carries no host-specific
// branch: HlsFast resolves and hands the destination to loadExtractor, TurboEmbed turns
// the download tiers into playable mp4 links. Fixtures: src/test/resources/turbo-chain/
// (live capture 2026-11-23).
package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Pure hlsfast chain helpers — framework-free so the unit-test classpath can load them
 *  (TDD-first, ADR-0005). */
object HlsFastParse {
    /** Player hash from `https://hlsfast.com/#<hash>` (the player page's `location.hash`). */
    fun hash(url: String?): String? =
        url?.substringAfterLast('#', "")?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }

    /** Destination embed from the resolve JSON; only http(s) targets are dispatchable. */
    fun resolveTarget(body: String): String? = try {
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
        root.path("url").asText("").trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    } catch (e: Exception) {
        null
    }

    /** Some resolve answers point straight at a media file — those need no adapter. */
    fun isDirectMedia(url: String): Boolean =
        Regex("\\.(mp4|m3u8)([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
}

/** hlsfast.com player shell (issue #1448): `/#<hash>` → public resolve API → destination
 *  embed. Dispatch goes through the registry, so a future destination host only needs a
 *  registry row — never another provider-side branch. */
open class HlsFast : ExtractorApi() {
    override val name = "HlsFast"
    override val mainUrl = "https://hlsfast.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = HlsFastParse.hash(url) ?: return
        val body = try {
            app.get("$mainUrl/api/public/resolve?file1=$hash", referer = "$mainUrl/").text
        } catch (e: Exception) {
            Log.d(name, "resolve: ${e.message}")
            return
        }
        val target = HlsFastParse.resolveTarget(body) ?: return
        if (HlsFastParse.isDirectMedia(target)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = target,
                    type = if (target.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }
        loadExtractor(target, "$mainUrl/", subtitleCallback, callback)
    }
}

/** Pure turboembed.com helpers (the hlsfast destination host). */
object TurboEmbedParse {
    /** Player code from `https://turboembed.com/<code>` (a leading/trailing space is a real
     *  cat3movie markup quirk — `src=" https://…"`). */
    fun code(url: String?): String? =
        url?.trim()?.substringAfter("://turboembed.com/", "")?.substringBefore('?')?.substringBefore('/')
            ?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }

    data class Tier(val file: String, val label: String)

    /** Download tiers from `GET /api/download/tiers?s=<code>`; the label is the quality
     *  token the site exposes ("540p"), the height field is not reliable (384–480 for the
     *  same 540p label across samples). */
    fun tiers(body: String): List<Tier> = try {
        val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data")
        val list = data.path("tiers")
        (0 until list.size()).mapNotNull { i ->
            val t = list.get(i) ?: return@mapNotNull null
            val file = t.path("file").asText("").trim()
            if (file.isEmpty()) null else Tier(file, t.path("label").asText("").trim())
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** turboembed.com (issue #1448): the download API exposes a signed mp4 per tier; the
 *  `/api/download/get` hop is a 302, so the emitted link is the resolved CDN URL. */
open class TurboEmbed : ExtractorApi() {
    override val name = "TurboEmbed"
    override val mainUrl = "https://turboembed.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = TurboEmbedParse.code(url) ?: return
        val tiers = try {
            TurboEmbedParse.tiers(app.get("$mainUrl/api/download/tiers?s=$code", referer = url).text)
        } catch (e: Exception) {
            Log.d(name, "tiers: ${e.message}")
            return
        }
        for (tier in tiers) {
            // the 302 target is a signed CDN URL — resolve it here so the player never has
            // to replay the redirect (and the signature is bound to this request time)
            val file = try {
                app.get("$mainUrl/api/download/get?s=$code&t=${tier.file}", referer = url).url
            } catch (e: Exception) {
                Log.d(name, "tier ${tier.file}: ${e.message}")
                continue
            }
            if (!file.startsWith("http")) continue
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = file,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = if (tier.label.isNotEmpty()) {
                        getQualityFromName(tier.label)
                    } else {
                        Qualities.Unknown.value
                    }
                }
            )
        }
    }
}

/** Pure loadvid helpers (Cat3Movie sv2 on recent uploads). */
object LoadVidParse {
    /** Embed hash from `https://cdn.loadvid.com/videos/play/<hash>`. */
    fun hash(url: String?): String? =
        url?.trim()?.substringAfter("/videos/play/", "")?.substringBefore('/')?.substringBefore('?')
            ?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }

    data class Config(val csrf: String, val token: String, val hash: String)

    /** `window.LoadVidConfig` (videoHash/videoToken) + the page's CSRF meta — the pair the
     *  player posts to `/videos/resolve-token`. */
    fun config(html: String): Config? {
        val csrf = Regex("""name="csrf-token"\s+content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return null
        val hash = Regex("""videoHash\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: return null
        val token = Regex("""videoToken\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: return null
        return Config(csrf, token, hash)
    }

    /** `/videos/resolve-token` answers the manifest *content* (never a URL — the storage
     *  path is hidden behind a blob: in the browser). CloudStream can only play an http(s)
     *  source, so the one playable shape is a single-segment VOD playlist: its segment is
     *  the whole video (MPEG-TS bytes served under a .png name, live 2026-11-23). Anything
     *  multi-segment has no addressable source and is dropped. */
    fun singleSegment(manifest: String): String? {
        if (!manifest.contains("#EXTM3U")) return null
        val segments = manifest.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        return segments.singleOrNull()?.takeIf { it.startsWith("http") }
    }
}

/** cdn.loadvid.com (issue #1448): JS-config player, resolved through its own JSON API. */
open class LoadVid : ExtractorApi() {
    override val name = "LoadVid"
    override val mainUrl = "https://cdn.loadvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        LoadVidParse.hash(url) ?: return
        val html = try {
            app.get(url, referer = referer ?: "$mainUrl/").text
        } catch (e: Exception) {
            Log.d(name, "embed: ${e.message}")
            return
        }
        val cfg = LoadVidParse.config(html) ?: return
        val manifest = try {
            app.post(
                "$mainUrl/videos/resolve-token",
                json = mapOf("token" to cfg.token, "hash" to cfg.hash),
                referer = url,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-CSRF-TOKEN" to cfg.csrf,
                    "Accept" to "application/vnd.apple.mpegurl,*/*"
                )
            ).text
        } catch (e: Exception) {
            Log.d(name, "resolve-token: ${e.message}")
            return
        }
        val file = LoadVidParse.singleSegment(manifest) ?: return
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = file,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
