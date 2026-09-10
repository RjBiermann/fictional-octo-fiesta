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
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.MixDropAg
import com.lagradost.cloudstream3.utils.ExtractorApi

fun BasePlugin.registerHostExtractors(first: List<ExtractorApi> = emptyList()) {
    first.forEach { registerExtractorAPI(it) }
    // Data table: one row per embed-host mirror (factory functions per family
    // live in Extractorlar.kt). Keep sequence stable — loadExtractor matches
    // the LAST registered adapter first.
    listOf(
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
        CloudWish(),
        dood("https://dooood.com"),
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
        Javclan(),
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
        Vidguardto(),
        MixDropAg(),
        MixDropMy(),
        Player4Me(),
        player4me("https://vip.player4me.vip"),
        player4me("https://my.rpmplay.online"),
        dood("https://playmogo.com"),
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
        MyDaddyExtractor(),
        Vidara(),
        Javhdz(),
        Javhdz2(),
        PerverZijaExtractor(),
        HlsFree(),
        HlsFreeWww(),
    ).forEach { registerExtractorAPI(it) }
}

// Shared Base64 decode helper (pad-if-needed, NO_WRAP, null on failure).
fun decodeBase64(token: String): String? = try {
    val padded = token.trim() + "=".repeat((4 - token.trim().length % 4) % 4)
    String(android.util.Base64.decode(padded, android.util.Base64.NO_WRAP), Charsets.UTF_8)
} catch (e: IllegalArgumentException) {
    null
}
