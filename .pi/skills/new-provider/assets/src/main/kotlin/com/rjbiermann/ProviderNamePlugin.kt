package com.rjbiermann

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class ProviderNamePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ProviderName())
    }
}