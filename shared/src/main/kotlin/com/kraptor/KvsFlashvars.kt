// Deep parse module for the KVS (Kernel Video Sharing) flashvars grammar
// (glossary: Parse function). One home for the unquoted-key / single-quoted-value
// field match that used to be hand-rolled in Neporn, Javbangers, AllClassicPorn
// and Porntrex. Keys are JS object-literal entries (`video_url: '…'`) or the
// bracket form AllClassicPorn serves (`flashvars['video_url_text'] = '…'`,
// issue #322 D2). Live-fixture-tested in KvsFlashvarsTest.
//
// Multi-grammar resolution (issue #1316, epic #876): KVS players also ship the
// stream in a video.js `cfg` object as a camelCase entry (`videoUrl: '…'`) with no
// legacy flashvars at all. `source()` chains legacy → cfg so a page-grammar drift
// stays a shared fix; `field()` keeps its single legacy grammar for callers.
package com.kraptor

object KvsFlashvars {

    /** The standard KVS source keys KVS video pages expose, best-first. */
    val URL_KEYS = listOf("video_url", "video_alt_url", "video_alt_url2")

    /** Resolution token in a media filename: `…_480p.mp4` → `480`. */
    private val FILE_QUALITY = Regex("""_(\d{3,4})p""")

    /**
     * Value of [key] from the flashvars JS: colon form `key: 'v'` or bracket form
     * `key'] = 'v'`. Keys are prefix-safe — `video_url` never matches
     * `video_url_text` or `video_alt_url2` (the grammar requires colon/bracket right
     * after the key). Escaped apostrophes (`\'`) unescape; blank/absent → null.
     */
    fun field(html: String, key: String): String? = match(html, key)

    /** Quality label of [key], i.e. the `<key>_text` flashvar ("480p", "1080p FHD"). */
    fun label(html: String, key: String): String? = field(html, "${key}_text")

    /**
     * Multi-grammar value of [key] (default `video_url`): legacy colon/bracket form
     * first, then the video.js `cfg` camelCase form (`video_url` → `videoUrl`,
     * `video_alt_url2` → `videoAltUrl2`, issue #1316). Legacy wins when both grammars
     * are present, so legacy pages keep resolving to the same URL. Blank/absent → null.
     */
    fun source(html: String, key: String = "video_url"): String? =
        field(html, key) ?: match(html, camelCase(key), leadingBoundary = true)

    /**
     * Quality label for [key]: derived from the resolved source's filename token
     * (`…_480p.mp4` → "480p") when present, else the caption flashvar — legacy
     * `<key>_text`, then its cfg camelCase spelling. Null when neither is available —
     * never an invented quality.
     *
     * KVS appends a query token (`…/6161_480p.mp4/?v-acctoken=…`), so the token is read
     * from the last path segment *after* `?`/`#` and any trailing slash are stripped
     * (review on #1320) — otherwise the segment is the query string and the token misses.
     */
    fun quality(html: String, key: String = "video_url"): String? {
        val fromName = source(html, key)
            ?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.let { FILE_QUALITY.find(it)?.groupValues?.get(1) }
        return fromName?.plus("p") ?: label(html, key)
            ?: match(html, camelCase("${key}_text"), leadingBoundary = true)
    }

    /** First-match value per [keys] entry (defaults [URL_KEYS]), deduped, in key order. */
    fun videoSources(html: String, keys: List<String> = URL_KEYS): List<String> =
        keys.mapNotNull { source(html, it) }.distinct()

    /** `video_alt_url2` → `videoAltUrl2` — the video.js cfg spelling of a KVS key. */
    private fun camelCase(key: String): String =
        key.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")

    /**
     * [field]'s single grammar, with an optional leading word boundary for camelCase
     * cfg keys so `videoUrl` never matches a longer identifier (`myVideoUrl`).
     */
    private fun match(html: String, key: String, leadingBoundary: Boolean = false): String? {
        val lead = if (leadingBoundary) "\\b" else ""
        val value = Regex("$lead(?:$key\\s*:|$key'\\]\\s*=)\\s*'((?:[^'\\\\]|\\\\.)*)'")
            .find(html)?.groupValues?.get(1) ?: return null
        return value.replace("\\'", "'").trim().takeIf { it.isNotEmpty() }
    }
}
