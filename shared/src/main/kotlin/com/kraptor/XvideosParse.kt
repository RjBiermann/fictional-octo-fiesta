package com.kraptor

/**
 * Pure xvideos embed resolver (issue #778, spec round of #708): ixiporn's
 * shape-B pages (2021–2023 import era) base64-wrap an external hub iframe via
 * `clean-tube-player/player-x.php` — for the xvideos hub that is
 * `https://www.xvideos.com/embedframe/<id>`. The embed page is server-rendered
 * and carries the whole player config as `html5player.setVideo*('…')` calls:
 * `setVideoUrlHigh`/`setVideoUrlLow` (progressive mp4 qualities) and
 * `setVideoHLS` (adaptive m3u8). Live capture: video 61306335 → 2 × mp4 +
 * 1 × m3u8 (probe 2026-10-21, evidence base #708/FINDINGS-655).
 *
 * Deleted-upstream contract: the target answers a 1049-byte
 * "This video has been deleted" shell (no setVideo* calls) — [streams]
 * returns an empty list: no throw, no best-effort dead URLs.
 *
 * Kept free of CloudStream framework types so it loads on the unit-test
 * classpath (TDD-first, ADR-0005). HTTP transport lives in the shared
 * `Xvideos` ExtractorApi adapter (HostAdapters.kt).
 */
object XvideosParse {

    /** Literal marker of the deleted-upstream shell page. */
    private const val DELETED_MARKER = "This video has been deleted"

    /** One resolved stream: [url] plus whether it is HLS (adaptive) and its mp4 quality (0 = n/a). */
    data class XvideosStream(val url: String, val isHls: Boolean, val quality: Int)

    /** True when the target answers the deleted-upstream shell instead of a player page. */
    fun isDeletedShell(html: String): Boolean = html.contains(DELETED_MARKER)

    /** Video title from `setVideoTitle('…')` (null on deleted/garbage pages). */
    fun title(embedHtml: String): String? =
        setVideoArg(embedHtml, "setVideoTitle")

    /**
     * All stream URLs an embed page resolves to, order pinned: high mp4 →
     * low mp4 → HLS (first entry = best progressive quality). Deleted shell
     * or absent calls → empty list, never a throw.
     */
    fun streams(embedHtml: String): List<XvideosStream> {
        if (isDeletedShell(embedHtml)) return emptyList()

        val out = mutableListOf<XvideosStream>()
        for (call in listOf("setVideoUrlHigh", "setVideoUrlLow")) {
            val url = setVideoArg(embedHtml, call) ?: continue
            out.add(XvideosStream(url, isHls = false, quality = mp4Quality(url)))
        }
        setVideoArg(embedHtml, "setVideoHLS")?.let {
            out.add(XvideosStream(it, isHls = true, quality = 0))
        }
        return out
    }

    /** First `'…'` argument of `html5player.<call>('…')`, JSON-escape-unescaped; null if absent. */
    private fun setVideoArg(embedHtml: String, call: String): String? =
        Regex("""$call\('([^']*)'\)""").find(embedHtml)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?.takeUnless { it.isEmpty() }

    /** Progressive quality from the mp4 filename (`video_360p.mp4` → 360), 0 when unparseable. */
    private fun mp4Quality(url: String): Int =
        Regex("""(\d{3,4})p\.mp4""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
