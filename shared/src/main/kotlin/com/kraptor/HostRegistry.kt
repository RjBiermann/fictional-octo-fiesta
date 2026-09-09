// Host registry (ADR-0002): the single registration point for every extractor
// adapter the repo ships. Every provider's plugin load() calls
// registerHostExtractors(); stream dispatch flows exclusively through the
// framework's loadExtractor. `first` accepts provider-specific adapters that
// must register ahead of the shared list (ordering-sensitive matches, e.g.
// StreamTape variants). Iterate order: loadExtractor matches the LAST
// registered adapter first, so keep the sequence stable.
package com.kraptor

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.DoodPmExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.utils.ExtractorApi

fun BasePlugin.registerHostExtractors(first: List<ExtractorApi> = emptyList()) {
    first.forEach { registerExtractorAPI(it) }
    listOf(
        StreamTape(),
        StreamTapeNet(),
        StreamTapeXyz(),
        Turboplayers(),
        TurbovidVip(),
        SavedVids(),
        StreamBeastUpn(),
        LULUSTREAMFIT(),
        DoodStream(),
        DoodDoply(),
        DoodVideo(),
        Ds2Play(),
        d000d(),
        VidHidePro(),
        VidHidePro1(),
        VidHidePro2(),
        VidHidePro3(),
        VidHidePro4(),
        VidHidePro6(),
        VidHidePro7(),
        VidhideVIP(),
        CloudWish(),
        Dooood(),
        Javlion(),
        Dhcplay(),
        Smoothpre(),
        Dhtpre(),
        Peytonepre(),
        Movearnpre(),
        Dintezuvio(),
        Streamwish(),
        Streamhihi(),
        Javsw(),
        swhoi(),
        Javmoon(),
        MixDropis(),
        Javclan(),
        Javggvideo(),
        EmturbovidExtractor(),
        DoodPmExtractor(),
        VidNest(),
        HgLink(),
        RyderJet(),
        UpnsOnline(),
        EmbedSeek(),
        VipSeekPlayer(),
        EasyVidPlayer(),
        VipEasyVidPlayer(),
        MyCloudZ(),
        VidStack(),
        StbP2P(),
        Playerupnone(),
        Turtleviplay(),
        Turboviplay(),
        Vidguardto(),
        MixDropAG(),
        MixDropMy(),
        Player4Me(),
        Vip4me(),
        RPMShare(),
        Playmogo(),
        Voe(),
        Stape(),
        StreamTAPE(),
        ShaveTape(),
        Watchadsontape(),
        Lancewhoisdifficult(),
        Javlesbians(),
        LULUSTREAM(),
        LULUVDO(),
        LULUVDOO(),
        LULUPVP(),
        LULUDLC(),
        LULU0(),
        LULUX08(),
        Stevenfamilyedge(),
        KPFilemoonSx(),
        KPFilemoonIn(),
        KPFilemoonLink(),
        KPFilemoonWf(),
        KPFilemoonEu(),
        KPFilemoonArt(),
        KPFilemoonNl(),
        KPCinegrab(),
        KPMoonmov(),
        KPNineSixAr(),
        KPKerapoxy(),
        KPFurher(),
        KPOneAzayf9w(),
        KPEightOneU6xl9d(),
        KPSmdfs40r(),
        KPC1z39(),
        KPBf0skv(),
        KPZ1ekv717(),
        KPL1afav(),
        KPTwoTwoTwoi8x(),
        KPEightMhlloqo(),
        KPF51rm(),
        KPXcoic(),
        KPBoosteradx(),
        KPStreamlyplayer(),
        KPStreamlyplayero(),
        KPBysewihe(),
        KPByselapuix(),
        KPEmbedplaybyse(),
        KPSb1254w9megshle(),
        KPMoflixStream(),
        KPBysezoxexe(),
        KPF16px(),
        KPBysesayeveum(),
        KPBysetayico(),
        KPBysevepoin(),
        KPBysezejataos(),
        KPBysekoze(),
        KPBysesukior(),
        KPByseSx(),
        KPByseqekaho(),
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
