// Deep parse module for the KVS (Kernel Video Sharing) flashvars grammar
// (glossary: Parse function). One home for the unquoted-key / single-quoted-value
// field match that used to be hand-rolled in Neporn, Javbangers, AllClassicPorn
// and Porntrex. Keys are JS object-literal entries (`video_url: '…'`) or the
// bracket form AllClassicPorn serves (`flashvars['video_url_text'] = '…'`,
// issue #322 D2). Live-fixture-tested in KvsFlashvarsTest.
package com.kraptor

object KvsFlashvars {

    /** The standard KVS source keys KVS video pages expose, best-first. */
    val URL_KEYS = listOf("video_url", "video_alt_url", "video_alt_url2")

    /**
     * Value of [key] from the flashvars JS: colon form `key: 'v'` or bracket form
     * `key'] = 'v'`. Keys are prefix-safe — `video_url` never matches
     * `video_url_text` or `video_alt_url2` (the grammar requires colon/bracket right
     * after the key). Escaped apostrophes (`\'`) unescape; blank/absent → null.
     */
    fun field(html: String, key: String): String? {
        val value = Regex("(?:$key\\s*:|$key'\\]\\s*=)\\s*'((?:[^'\\\\]|\\\\.)*)'")
            .find(html)?.groupValues?.get(1) ?: return null
        return value.replace("\\'", "'").trim().takeIf { it.isNotEmpty() }
    }

    /** Quality label of [key], i.e. the `<key>_text` flashvar ("480p", "1080p FHD"). */
    fun label(html: String, key: String): String? = field(html, "${key}_text")

    /** First-match value per [keys] entry (defaults [URL_KEYS]), deduped, in key order. */
    fun videoSources(html: String, keys: List<String> = URL_KEYS): List<String> =
        keys.mapNotNull { field(html, it) }.distinct()
}
