package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.Maxstream
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.Voe

@CloudstreamPlugin
class MangopornProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Mangoporn())
        // Shared host families first (ADR-0002), then the provider-only rows.
        registerHostExtractors(
            listOf(
                StreamTapeNet(),
                StreamTapeXyz(),
                Turboplayers(),
                DoodStream(),
                DoodDoply(),
                DoodVideo(),
                Ds2Play(),
                d000d(),
                Dooood(),
                Playmogo(),
                Streamhihi(),
                Javsw(),
                VidhideVIP(),
                Javlion(),
                VidHidePro1(),
                VidHidePro2(),
                VidHidePro3(),
                VidHidePro4(),
                VidHidePro6(),
                VidHidePro7(),
                Dhcplay(),
                Smoothpre(),
                Dhtpre(),
                Peytonepre(),
                Movearnpre(),
                Dintezuvio(),
                HgLink(),
                RyderJet(),
                MyCloudZ(),
                CloudWish(),
                Javlion(),
                Maxstream(),
                Javggvideo(),
                EmturbovidExtractor(),
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
                MangopornVidguard(),
                MixDropAG(),
                MixDropMy(),
                Player4Me(),
                Vip4me(),
                RPMShare(),
                Voe(),
                Stape(),
                StreamTAPE(),
                ShaveTape(),
                Watchadsontape(),
                Lancewhoisdifficult(),
                Javlesbians(),
                Stevenfamilyedge(),
                LULUSTREAM(),
                LULUVDO(),
                LULUVDOO(),
                LULUPVP(),
                LULUDLC(),
                LULU0(),
                LULUX08(),
                swhoi(),
                MixDropis(),
                Javmoon(),
            )
        )

        this.openSettings = { ctx: Context ->
            MangoAyarlar.showSettingsDialog(ctx as AppCompatActivity) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
