package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.extractors.Maxstream

@CloudstreamPlugin
class MangopornProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Mangoporn())
        // Shared host families (Extractorlar.kt, registered via registerHostExtractors)
        // already serve every host Mangoporn embeds — Dood, VidHidePro, Streamtape,
        // LULU, Voe, Player4Me, Vidguard etc. (ADR-0002: no mirror rows). Only the
        // two hosts the shared table lacks are registered here.
        registerHostExtractors(
            listOf(
                CloudWish(),
                Maxstream(),
            )
        )

        this.openSettings = { ctx: Context ->
            MangoAyarlar.showSettingsDialog(ctx as AppCompatActivity) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
