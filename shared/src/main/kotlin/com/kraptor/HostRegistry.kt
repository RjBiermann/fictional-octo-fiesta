// Host registry (ADR-0002): the single registration point for every extractor
// adapter the repo ships. Every provider's plugin load() calls
// registerHostExtractors(); stream dispatch flows exclusively through the
// framework's loadExtractor. `first` accepts provider-specific adapters that
// must register ahead of the shared list (ordering-sensitive matches, e.g.
// StreamTape variants). Iterate order: loadExtractor matches the LAST
// registered adapter first, so keep the sequence stable.
package com.kraptor

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VinovoTo
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.BigwarpIO
import com.lagradost.cloudstream3.extractors.DoodToExtractor
import com.lagradost.cloudstream3.extractors.MixDropAg
import com.lagradost.cloudstream3.utils.ExtractorApi

/**
 * Browser UA used by hosts whose edge blocks framework defaults; one const so the
 * fingerprint can't drift between copies (audit finding 7 — the same string was
 * pasted 11× across Extractorlar.kt and HQPorner).
 */
const val FIREFOX_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

/**
 * The shared host table itself (ADR-0005 pure-data seam, issue #353): one row per
 * embed-host mirror. Kept separate from registration so unit tests can pin the
 * ordering/dedup/naming invariants on the data without touching the framework's
 * global extractor registry.
 *
 * Ordering contract (verified in the framework jar, ExtractorApiKt.loadExtractor):
 * iteration is last → first over the registration list and the first adapter whose
 * mainUrl is a prefix of the target URL wins — so the LAST registered row wins, and
 * an earlier row with an identical mainUrl is dead code. HostRegistryTest pins:
 * unique mainUrls, no prefix shadowing, the StreamTape supersession invariant,
 * and the intentional name-collision allowlist (issue #347 P0-10/P0-11).
 */
internal fun sharedHostRegistry(): List<ExtractorApi> = listOf(
        // ponytail: StreamTAPE (custom adapter below) deliberately supersedes the
        // framework StreamTape class — its headers/redirect handling is what serves
        // the whole streamtape mirror family here. Do not re-register the framework
        // StreamTape alongside it: last-registered wins and the row would be dead.
        streamtapeMirror("https://streamtape.net/"),
        streamtapeMirror("https://streamtape.xyz"),
        streamtapeMirror("https://turboplayers.xyz"),
        TurbovidVip(),
        SavedVids(),
        StreamBeastUpn(),
        lulu("https://lulustream.fit", "LuluStream"),
        DoodStream(),
        dood("https://doply.net"),
        dood("https://vide0.net"),
        dood("https://ds2play.com"),
        dood("https://d000d.com"),
        VidHidePro(),
        vidHidePro("https://filelions.live"),
        vidHidePro("https://filelions.online"),
        vidHidePro("https://filelions.to"),
        vidHidePro("https://kinoger.be"),
        vidHidePro("https://vidhidepre.com"),
        vidHidePro("https://vidhidehub.com"),
        vidHidePro("https://vidhidevip.com", "VidhideVIP"),
        dood("https://dooood.com"),
        // #653: xmoviesforyou.com serves dood.to anchors on its video pages — the
        // registered DoodStream rows all target pass_md5-style pages, and dood.to's
        // modern /d/ page from this IP is a captcha shell anyway, so row selection is
        // not live-verifiable from here: the framework's own DoodToExtractor (Dood
        // family, upstream-maintained) is the row — same choice a default app gets.
        DoodToExtractor(),
        dood("https://dood.ws"),   // javmost.ws g41 server row returns dood.ws embeds (issue #376)
        vidHidePro("https://javlion.xyz", "Javlion"),
        vidHidePro("https://dhcplay.com", "DHC Play"),
        vidHidePro("https://smoothpre.com", "EarnVids"),
        vidHidePro("https://dhtpre.com", "EarnVids"),
        vidHidePro("https://peytonepre.com", "EarnVids"),
        vidHidePro("https://movearnpre.com", "EarnVids"),
        vidHidePro("https://dintezuvio.com", "EarnVids"),
        Streamwish(),
        streamwishMirror("https://streamhihi.com", "Streamhihi"),
        streamwishMirror("https://javsw.me", "Javsw"),
        filesimMirror("https://swhoi.com", "Streamwish"),
        filesimMirror("https://javmoon.me", "FileMoon"),
        mixdropMirror("https://mixdrop.is"),
        // #1446: xmoviesforyou.com's MIXDROP chip host rotates through the mixdrop alias
        // family (mxdrop.click, m1xdrop.bz, mixdrp.click, miiixdrop.net/top observed in one
        // 2026-11-22 sweep) and every one of them 30x-redirects to mxdrop.click/f/<id>.
        // The provider resolves that hop before loadExtractor (prefix dispatch works on the
        // ORIGINAL url), so this single canonical row serves the whole rotation. Verified
        // live: mxdrop.click/f/<id> → /e/<id> packed MDCore.wurl → 206 video/mp4.
        mixdropMirror("https://mxdrop.click"),
        BigwarpIO(),   // #653: xmoviesforyou.com serves bigwarp.io anchors; framework extractor (bigwarp.io → bigwarp.pro redirect is host-side)
        Javclan(),
        // #1442: jav.guru's searcho mirror rotates four player hosts across its buttons.
        // javplaya.com serves the javclan grammar (`links={hls2,hls4}`, packed);
        // maxstream.org is the same packer around a Playerjs `file:` config. Both carried
        // live 200 m3u8 while this run's samples were resolved, and loadExtractor's
        // prefix dispatch dropped them (14/16 and 4/16 of the sampled pages).
        javclanMirror("https://javplaya.com", "Javplaya"),
        Maxstream(),
        Javggvideo(),
        EmturbovidExtractor(),
        dood("https://dood.pm"),
        VidNest(),
        vidHidePro("https://hglink.to", "HGLink"),
        vidHidePro("https://ryderjet.com", "RyderJet"),
        player4me("https://my.upns.online"),
        player4me("https://my.embedseek.online"),
        player4me("https://vip.seekplayer.vip"),
        player4me("https://p.easyvidplayer.com"),
        player4me("https://vip.easyvidplayer.com"),
        vidHidePro("https://mycloudz.cc", "MyCloudZ"),
        VidStack(),
        vidstackMirror("https://stb.strp2p.com", "STBP2P"),
        Playerupnone(),
        Turtleviplay(),
        Turboviplay(),
        AbyssPlayer(),
        AbyssPlayerEmbed(),
        Vidguardto(),
        MixDropAg(),
        MixDropMy(),
        Player4Me(),
        player4me("https://vip.player4me.vip"),
        player4me("https://my.rpmplay.online"),
        dood("https://playmogo.com"),
        // #670: dood.so is a dood-family mirror — /e/<id> answers 301 → playmogo.com
        // (row above), but loadExtractor dispatches by mainUrl prefix on the ORIGINAL
        // URL, so dood.so anchors need their own row or they are dropped fleet-wide.
        dood("https://dood.so"),
        Voe(),
        streamtapeMirror("https://stape.fun"),
        StreamTAPE(),
        streamtapeMirror("https://shavetape.cash"),
        streamtapeMirror("https://watchadsontape.com"),
        voeMirror("https://lancewhosedifficult.com"),
        voeMirror("https://javlesbians.com"),
        lulu("https://lulustream.com", "LuluStream"),
        lulu("https://luluvdo.com"),
        lulu("https://luluvdoo.com"),
        lulu("https://lulupvp.com"),
        lulu("https://luluvido.com", "LuluStream"),   // mangoporn.net data-servers serves luluvido.com/e/<id> after the #564 BEM migration (FINDINGS-564)
        lulu("https://lulu.dlc.ovh/"),
        lulu("https://lulu0.ovh/"),
        lulu("https://x08.ovh/"),
        voeMirror("https://stevenfamilyedge.com"),
        filemoon("https://filemoon.sx", "Filemoon"),
        filemoon("https://filemoon.in", "Filemoon"),
        filemoon("https://filemoon.link", "Filemoon"),
        filemoon("https://filemoon.wf", "Filemoon"),
        filemoon("https://filemoon.eu", "Filemoon"),
        filemoon("https://filemoon.art", "Filemoon"),
        filemoon("https://filemoon.nl", "Filemoon"),
        filemoon("https://cinegrab.com", "Cinegrab"),
        filemoon("https://moonmov.pro", "Moonmov"),
        filemoon("https://96ar.com", "96ar"),
        filemoon("https://kerapoxy.cc", "Kerapoxy"),
        filemoon("https://furher.in", "Furher"),
        filemoon("https://1azayf9w.xyz", "1azayf9w"),
        filemoon("https://81u6xl9d.xyz", "81u6xl9d"),
        filemoon("https://smdfs40r.skin", "Smdfs40r"),
        filemoon("https://c1z39.com", "C1z39"),
        filemoon("https://bf0skv.org", "Bf0skv"),
        filemoon("https://z1ekv717.fun", "Z1ekv717"),
        filemoon("https://l1afav.net", "L1afav"),
        filemoon("https://222i8x.lol", "222i8x"),
        filemoon("https://8mhlloqo.fun", "8mhlloqo"),
        filemoon("https://f51rm.com", "F51rm"),
        filemoon("https://xcoic.com", "Xcoic"),
        filemoon("https://boosteradx.online", "Boosteradx"),
        filemoon("https://streamlyplayer.online", "Streamlyplayer"),
        filemoon("https://streamlyplayero.online", "Streamlyplayero"),
        filemoon("https://bysewihe.com", "Byse"),
        filemoon("https://byselapuix.com", "Byse"),
        filemoon("https://embedplaybyse.top", "Byse"),
        filemoon("https://sb1254w9megshle.org", "Sb1254w9megshle"),
        filemoon("https://moflix-stream.link", "MoflixStream"),
        filemoon("https://bysezoxexe.com", "Byse"),
        filemoon("https://f16px.com", "F16px"),
        filemoon("https://bysesayeveum.com", "Byse"),
        filemoon("https://bysetayico.com", "Byse"),
        filemoon("https://bysevepoin.com", "Byse"),
        filemoon("https://bysezejataos.com", "Byse"),
        filemoon("https://bysekoze.com", "Byse"),
        filemoon("https://bysesukior.com", "Byse"),
        filemoon("https://bysejikuar.com", "Byse"),
        filemoon("https://bysefujedu.com", "Byse"),
        filemoon("https://bysedikamoum.com", "Byse"),
        filemoon("https://bysebuho.com", "Byse"),
        filemoon("https://byse.sx", "Byse"),
        filemoon("https://byseqekaho.com", "Byse"),
        Playmate(),
        // Formerly per-provider adapters, now host families available to all:
        // CloudWish promoted from Mangoporn's provider-local Extractors.kt (#443)
        // — evidence (#650): Javseen's fresh pages carry cloudwish as their
        // primary fallback host and no row answered cloudwish.xyz.
        CloudWish(),
        // #1444: Javseen's popular far-end videos carry javtiful.com/embed as their only
        // source (e.g. /290353/ → embed/115265 → 206 video/mp4). No row answered it, so
        // loadExtractor dropped the video; the embed's watch-config playback endpoint is
        // parsed by JavtifulEmbedParse (live fixtures, 2026-11-22).
        JavtifulEmbed(),
        MyDaddyExtractor(),
        // #1439: HQPorner's catalogue is served by three player generations — hqwo.cc
        // (older uploads, same mydaddy anchor grammar) and www.flyflv.com (a third
        // player, source-tag grammar) had no row, so loadExtractor prefix dispatch
        // (fuzzy fallback scored 71/78, under the 80 threshold) dropped those videos
        // entirely. Explicit rows, never fuzzy matching.
        Hqwo(),
        FlyFlv(),
        Vidara(),
        VidaraSo(),
        Javhdz(),
        Javhdz2(),
        PerverZijaExtractor(),
        HlsFree(),
        HlsFreeWww(),
        // #779: second hub of the ixiporn shape-B tier (player-x.php b64 payload —
        // hub varies per video, xvideos is the other observable). flashvars →
        // /media/{hls,mp4}?s= chain; deleted videos answer a 404 shell with no
        // flashvars and are declined at the Parse seam.
        RedtubeEmbed(),
        // #778: xvideos.com/embedframe/<id> — SSR resolver for the ixiporn shape-B hub
        // tier (#708/#709): html5player.setVideoUrlHigh/Low + setVideoHLS; deleted-upstream
        // targets answer a "This video has been deleted" shell → no links, no throw.
        Xvideos(),
        // #1445: third shape-B hub — www.youporn.com/embed/<id>/<slug>/ on the 2020-12
        // uploads (/page/1400: 2/2 sampled). Same network as redtube but the legs sit in a
        // SINGULAR `mediaDefinition` array, so the flashvars grammar missed them.
        Youporn(),
        // #1445: ixiporn shape-D tier — an import-era subset embeds a RAW iframe
        // (vjav.com/embed/<id>, videotxxx.com/embed/<id> → canonical txxx.com) instead of
        // the player-x b64 wrapper, and the video page carries no contentUrl. Both hosts are
        // KVS shells whose player calls /api/videofile.php; dispatch is by the iframe's own
        // host, so the videotxxx 302 → txxx.com never helps — one row per host.
        KvsVideofile("https://vjav.com", "Vjav"),
        KvsVideofile("https://videotxxx.com", "Txxx"),
        KvsVideofile("https://txxx.com", "Txxx"),
        KvsVideofile("https://txxx.me", "Txxx"),
        // #1448 (Cat3Movie video-source audit): the hlsfast.com → turboembed.com chain.
        // hlsfast's `/api/v1/video` AES endpoint (issue #180) is 404 on every hash — the
        // player shell now resolves `/#<hash>` through a public redirect API whose
        // destination host rotates (turboembed.com today, `host1: upn` retired in the
        // response). HlsFast dispatches the destination back through loadExtractor, so a
        // future host needs one row here, never a provider-side branch; TurboEmbed turns
        // its download tiers into playable mp4 links. LoadVid (cat3movie sv2) is the third
        // unregistered host these pages embed — same page-side JSON API family.
        HlsFast(),
        TurboEmbed(),
        // #1459 (Sexfilm video-source audit): filmcdm.top/e/<id> 302s part of that
        // catalogue to vsembed.com — the same download-tier player platform as
        // turboembed.com (`/api/download/tiers?s=`, `/api/download/get?s=&t=` → 302
        // signed mp4). One row, no provider-side host branch.
        turboEmbed("https://vsembed.com", "VsEmbed"),
        LoadVid(),
        // #1512 (S9/#1447 PandaMovies, S23/#1461 Mangoporn video-source audits): both
        // providers enumerate section.hlm[data-servers] host-agnostically and dispatch each
        // `u` through loadExtractor, so an unregistered host is a silently dropped mirror.
        // vinovo.to appears on 5/15 PandaMovies and 4/12 Mangoporn sampled pages and had no
        // row. The framework's own VinovoTo answers it: mainUrl https://vinovo.to, parse
        // targets meta[name=token|file_code|recaptcha] + video[data-base], POST
        // /api/file/url/ with Origin + X-Requested-With, reCAPTCHA via
        // APIHolder.getCaptchaToken (jar-verified against cloudstream3:pre-release and
        // re-probed live 2026-11-27: /e/<id> 200 with exactly that grammar). Deliberately
        // NOT a dood(...) row — vinovo.to does not serve the dood pass_md5 grammar, and a
        // dood row would emit a bogus URL (FINDINGS-1447).
        VinovoTo(),
    )

fun BasePlugin.registerHostExtractors(first: List<ExtractorApi> = emptyList()) {
    first.forEach { registerExtractorAPI(it) }
    sharedHostRegistry().forEach { registerExtractorAPI(it) }
}

// Shared Base64 decode helper (pad-if-needed, NO_WRAP, null on failure).
// android.util.Base64 is the primary decoder: it exists on every supported API.
// java.util.Base64 (API 26+) is only a fallback for JVM unit tests, which can't use
// android.util.Base64 ("not mocked").
fun decodeBase64(token: String): String? {
    val padded = token.trim() + "=".repeat((4 - token.trim().length % 4) % 4)
    return try {
        String(android.util.Base64.decode(padded, android.util.Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: RuntimeException) {
        try {
            String(java.util.Base64.getMimeDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: LinkageError) {
            null  // API < 26: java.util.Base64 absent
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
