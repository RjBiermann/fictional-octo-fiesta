// PornSlash (pornslash.com) — a KVS-engine tube serving full-length movies and a shorts
// feed. S1 (#1643) is the scaffold + parse seam only: the MainAPI skeleton, the plugin
// registration and the pure Parse functions in PornSlashParse.kt. Home rows land in
// S2 (#1644), detail/streams in S3 (#1645), shorts in S4 (#1646), browse filters in
// S5 (#1647); those stories fill search()/load()/loadLinks() below.
package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

class PornSlash : MainAPI() {
    override var mainUrl = "https://www.pornslash.com"
    override var name = "PornSlash"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // S2 (#1644) wires the `Latest` + category home rows; nothing to show until then.
    override val hasMainPage = false
}

@CloudstreamPlugin
class PornSlashPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornSlash())
        registerHostExtractors()
    }
}
