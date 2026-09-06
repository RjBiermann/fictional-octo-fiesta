package com.rjbiermann

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PornXPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornXP())
    }
}