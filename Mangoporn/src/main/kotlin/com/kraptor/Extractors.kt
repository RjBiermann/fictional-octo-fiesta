// Mangoporn provider-only extractors (issue #443).
//
// The upstream Mangoporn source (Kraptor123/Cs-GizliKeyif) ships a large
// Extractors.kt whose host families (Filemoon/Byse, Streamwish, VidHidePro,
// DoodStream, StreamTAPE, Player4Me, LULUBASE, VidNest, Playmate, Vidguardto,
// Turtleviplay) are already byte-equivalent ports in shared/Extractorlar.kt —
// per ADR-0002 they are NOT duplicated here; they reach this provider through
// registerHostExtractors() plus the mirror rows below. Only hosts the shared
// table lacks live in this file.
package com.kraptor

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable

// Mirror rows: host families shared/ already serves, pinned to the exact hosts
// Mangoporn embeds (same pattern as sharedHostRegistry, ADR-0002).
class Streamhihi : Streamwish() { override var name = "Streamhihi"; override var mainUrl = "https://streamhihi.com" }
class Javsw : Streamwish() { override var mainUrl = "https://javsw.me"; override var name = "Javsw" }

class VidhideVIP : VidHidePro() { override var mainUrl = "https://vidhidevip.com"; override var name = "VidhideVIP" }
class Javlion : VidHidePro() { override var mainUrl = "https://javlion.xyz"; override var name = "Javlion" }
class VidHidePro1 : VidHidePro() { override var mainUrl = "https://filelions.live" }
class VidHidePro2 : VidHidePro() { override var mainUrl = "https://filelions.online" }
class VidHidePro3 : VidHidePro() { override var mainUrl = "https://filelions.to" }
class VidHidePro4 : VidHidePro() { override var mainUrl = "https://kinoger.be" }
class VidHidePro6 : VidHidePro() { override var mainUrl = "https://vidhidepre.com" }
class VidHidePro7 : VidHidePro() { override var mainUrl = "https://vidhidehub.com" }
class Dhcplay : VidHidePro() { override var name = "DHC Play"; override var mainUrl = "https://dhcplay.com" }
class Smoothpre : VidHidePro() { override var name = "EarnVids"; override var mainUrl = "https://smoothpre.com" }
class Dhtpre : VidHidePro() { override var name = "EarnVids"; override var mainUrl = "https://dhtpre.com" }
class Peytonepre : VidHidePro() { override var name = "EarnVids"; override var mainUrl = "https://peytonepre.com" }
class Movearnpre : VidHidePro() { override var name = "EarnVids"; override var mainUrl = "https://movearnpre.com" }
class Dintezuvio : VidHidePro() { override var name = "EarnVids"; override var mainUrl = "https://dintezuvio.com" }
class HgLink : VidHidePro() { override var name = "HGLink"; override var mainUrl = "https://hglink.to" }
class RyderJet : VidHidePro() { override var name = "RyderJet"; override var mainUrl = "https://ryderjet.com" }

class MyCloudZ : VidHidePro() { override var mainUrl = "https://mycloudz.cc"; override var name = "MyCloudZ" }
class Turboplayers : StreamTape() { override var mainUrl = "https://turboplayers.xyz"; override var name = "Streamtape" }

class swhoi : Filesim() { override var mainUrl = "https://swhoi.com"; override var name = "Streamwish" }
class MixDropis : MixDrop() { override var mainUrl = "https://mixdrop.is" }
class Javmoon : Filesim() { override var mainUrl = "https://javmoon.me"; override var name = "FileMoon" }

class StbP2P : VidStack() { override var mainUrl = "https://stb.strp2p.com"; override var name = "STBP2P" }

class MixDropAG : MixDrop() {
    override var mainUrl = "https://mixdrop.ag"
}

class DoodDoply : DoodStream() {
    override var mainUrl = "https://doply.net"
    override var name = "DoodStream"
}

class DoodVideo : DoodStream() {
    override var mainUrl = "https://vide0.net"
}
class Ds2Play : DoodStream() {
    override var mainUrl = "https://ds2play.com"
}
class d000d : DoodStream() {
    override var mainUrl = "https://d000d.com"
}

class Dooood : DoodStream() {
    override var mainUrl = "https://dooood.com"
}

class Watchadsontape : StreamTAPE() {
    override var mainUrl = "https://watchadsontape.com"
}
class Stape : StreamTAPE() {
    override var mainUrl = "https://stape.fun"
}

class StreamTapeNet : StreamTAPE() {
    override var mainUrl = "https://streamtape.net/"
}

class StreamTapeXyz : StreamTAPE() {
    override var mainUrl = "https://streamtape.xyz"
}

class ShaveTape : StreamTAPE() {
    override var mainUrl = "https://shavetape.cash"
}

class Lancewhoisdifficult : Voe() {
    override var mainUrl = "https://lancewhosedifficult.com"
}

class Javlesbians : Voe() {
    override var mainUrl = "https://javlesbians.com"
}

class Stevenfamilyedge : Voe() {
    override var mainUrl = "https://stevenfamilyedge.com"
}

class Vip4me : Player4Me() {
    override var mainUrl = "https://vip.player4me.vip"
    override var name = "Player4Me"
}

class RPMShare : Player4Me() {
    override var mainUrl = "https://my.rpmplay.online"
    override var name = "Player4Me"
}

class UpnsOnline : Player4Me() {
    override var mainUrl = "https://my.upns.online"
    override var name = "Player4Me"
}

class EmbedSeek : Player4Me() {
    override var mainUrl = "https://my.embedseek.online"
    override var name = "Player4Me"
}

class VipSeekPlayer : Player4Me() {
    override var mainUrl = "https://vip.seekplayer.vip"
    override var name = "Player4Me"
}

class EasyVidPlayer : Player4Me() {
    override var mainUrl = "https://p.easyvidplayer.com"
    override var name = "Player4Me"
}

class VipEasyVidPlayer : Player4Me() {
    override var mainUrl = "https://vip.easyvidplayer.com"
    override var name = "Player4Me"
}

class Playmogo : DoodStream() {
    override var mainUrl = "https://playmogo.com"
    override var name = "DoodStream"
}

class LULUSTREAM : LULUBASE() {
    override val name = "LuluStream"
    override val mainUrl = "https://lulustream.com"
}

class LULUVDO : LULUBASE() {
    override val name = "Lulustream"
    override val mainUrl = "https://luluvdo.com"
}

class LULUVDOO : LULUBASE() {
    override val name = "Lulustream"
    override val mainUrl = "https://luluvdoo.com"
}

class LULUPVP : LULUBASE() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulupvp.com"
}

class LULUDLC : LULUBASE() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulu.dlc.ovh/"
}

class LULU0 : LULUBASE() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulu0.ovh/"
}

class LULUX08 : LULUBASE() {
    override val name = "Lulustream"
    override val mainUrl = "https://x08.ovh/"
}

// CloudWish: Dean-Edwards packed player script. The mangoporn upstream carries
// its own unpack() whose grammar differs from shared PackedJs (no `.split('|')`
// tail required, keys may be absent); ported as-is to not disturb the shared
// fixture-tested grammar.
open class CloudWish : ExtractorApi() {
    override val name = "CloudWish"
    override val mainUrl = "https://cloudwish.xyz"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Sec-GPC" to "1",
    )

    fun unpack(packedJs: String): String? {
        try {
            val pattern = Regex(
                """\}\('((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = pattern.find(packedJs) ?: return null

            val p = match.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\\", "\\")
            val a = match.groupValues[2].toInt()
            val c = match.groupValues[3].toInt()
            val k = match.groupValues[4].split("|").toMutableList()

            while (k.size < c) {
                k.add("")
            }

            var result = p
            for (i in (c - 1) downTo 0) {
                if (k[i].isNotEmpty()) {
                    val token = Integer.toString(i, a)
                    result = result.replace("\\b$token\\b".toRegex(RegexOption.IGNORE_CASE), k[i])
                }
            }

            return result
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = baseHeaders)
            val html = response.text

            val packedScript = Regex("eval\\(function\\(p,a,c,k,e,d\\)").let { marker ->
                Regex("<script[^>]*>([\\s\\S]*?)</script>").findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull { it.contains(marker.pattern) }
            } ?: return

            val unpacked = unpack(packedScript) ?: return

            val parsedUrl = java.net.URL(url)
            val host = parsedUrl.host

            val m3u8Pattern =
                Regex("""(https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*|/stream/[^\s"'<>]+master\.m3u8)""")
            val m3u8Urls = m3u8Pattern.findAll(unpacked)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            val masterUrl = m3u8Urls.firstOrNull { it.startsWith("/stream/") }

            if (masterUrl != null) {
                val fullUrl = "https://$host$masterUrl"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fullUrl,
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0",
                            "Sec-GPC" to "1",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-origin",
                        )
                    }
                )
            } else {
                for (m3u8Url in m3u8Urls) {
                    val fullUrl =
                        if (m3u8Url.startsWith("/")) "https://$host$m3u8Url" else m3u8Url
                    val quality = when {
                        fullUrl.contains("/hls4/") -> Qualities.P1080.value
                        fullUrl.contains("/hls3/") -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(
                        newExtractorLink(source = name, name = name, url = fullUrl) {
                            this.referer = url
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
}

// Vidguard: rhino-evaluated obfuscated player + sig decode. Upstream uses
// android.util.Base64 directly — shared decodeBase64 differs (URL-safe); the
// DEFAULT flag here matters for the sig payload shape.
class MangopornVidguard : ExtractorApi() {
    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url)
        val resc = res.document.select("script:containsData(eval)").firstOrNull()?.data()
        resc?.let {
            val jsonStr2 = try { SvgObjectMapper.read(it) } catch (_: Exception) { null } ?: return
            val watchlink = sigDecode(jsonStr2.stream)

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = name,
                    url = watchlink,
                    INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        var t = ""
        for (v in sig.chunked(2)) {
            val byteValue = Integer.parseInt(v, 16) xor 2
            t += byteValue.toChar()
        }
        val padding = when (t.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        val decoded = Base64.decode(t + padding, Base64.DEFAULT)
        t = String(decoded).dropLast(5).reversed()
        val charArray = t.toCharArray()
        for (i in 0 until charArray.size - 1 step 2) {
            val temp = charArray[i]
            charArray[i] = charArray[i + 1]
            charArray[i + 1] = temp
        }
        val modifiedSig = String(charArray).dropLast(5)
        return url.replace(sig, modifiedSig)
    }

    private fun runJS2(hideMyHtmlContent: String): String {
        val rhino = Context.enter()
        rhino.optimizationLevel = -1
        val scope: Scriptable = rhino.initSafeStandardObjects()
        scope.put("window", scope, scope)
        var result = ""
        try {
            rhino.evaluateString(scope, hideMyHtmlContent, "JavaScript", 1, null)
            val svgObject = scope.get("svg", scope)
            result = if (svgObject is NativeObject) {
                NativeJSON.stringify(Context.getCurrentContext(), scope, svgObject, null, null).toString()
            } else {
                Context.toString(svgObject)
            }
        } catch (_: Exception) {
        } finally {
            Context.exit()
        }
        return result
    }
}

// Jackson indirection kept from upstream (mapper.readValue on SvgObject).
object SvgObjectMapper {
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    fun read(json: String): SvgObject = mapper.readValue(json, SvgObject::class.java)
}
