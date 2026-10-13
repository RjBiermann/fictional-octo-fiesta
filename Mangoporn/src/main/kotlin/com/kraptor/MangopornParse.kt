// Deep parse module for the Mangoporn port (glossary: Parse function).
// Pure logic only — HTTP flows stay covered by pipeline Verification.
// Title filter: Mangoporn's dirty-word blocklist + the "Home menu boars"
// extra words, one regex built once.
// Embed extraction: the section.hlm[data-servers] JSON player payload
// (issue #564 BEM grammar — the old div#pettabs tabs are gone).
// Quick search: turbosearch wp-json suggest payload.
package com.kraptor

object MangopornParse {

    /** Extra blocked words the home menu contributes (Mangoporn.Anamenudekiboklar). */
    private val menuWords = listOf("TS", "Trans", "TGirl", "gay", "pegging", "bi", "femboy", "T-Boy", "Bisexual", "Transsexual", "Trans")

    /** The full blocked-word regex: [words] + menuWords, `\b(word…)\w*\b`, case-insensitive. */
    fun dirtyWordRegex(words: List<String>): Regex = Regex(
        "\\b(?:${(words + menuWords).joinToString("|") { Regex.escape(it) }})\\w*\\b",
        RegexOption.IGNORE_CASE
    )

    /** File-locker hosts (rapidgator/nitroflare-style download links) — kept as a guard:
     *  download rows ship outside data-servers today, the filter just keeps them out
     *  if the payload shape ever merges them in. */
    private val blockedHosts = listOf("rapidgator.net", "nitroflare.com", "uploaded.net", "filefactory.com")

    private val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()

    /**
     * Embeds from the watch player: `section.hlm[data-servers]` — a JSON array with
     * the stream hosts at key "u" (labels: PlayMate/DoodStream/LuluStream/VOE/MixDrop).
     * Download rows (Rapidgator/NitroFlare) are separate anchors and never included;
     * a malformed payload yields no embeds for that section, never a throw.
     */
    fun embedLinks(document: org.jsoup.nodes.Document): List<String> {
        val attrs = document.select("section.hlm[data-servers]")
        val out = ArrayList<String>(attrs.size)
        for (el in attrs) {
            for (link in embedLinksJson(el.attr("data-servers"))) {
                // data-servers URLs are absolute; the MainAPI adapter applies any fixUrl
                if (blockedHosts.none { link.contains(it) }) out.add(link)
            }
        }
        return out.distinct()
    }

    /** One data-servers payload → embed URLs (key "u"); malformed → empty, never a throw. */
    fun embedLinksJson(json: String): List<String> = try {
        val node = jsonMapper.readTree(json) as? com.fasterxml.jackson.databind.node.ArrayNode
            ?: return emptyList()
        val links = ArrayList<String>(node.size())
        var i = 0
        while (i < node.size()) {
            node.get(i).get("u")?.asText()?.let { links.add(it) }
            i++
        }
        links
    } catch (e: Exception) {
        emptyList()
    }

    /** The page's JSON-LD VideoObject script text (schema.org metadata: duration, thumbnailUrl). */
    fun videoLdJson(document: org.jsoup.nodes.Document): String? =
        document.selectFirst("script[type=application/ld+json]:containsData(VideoObject)")?.data()

    /** #564: every listing surface serves a `a.next.page-numbers` anchor when more
     *  pages exist (per-page card counts vary, count-based detection drifted);
     *  last page: cards, no anchor; past last: 404. */
    fun hasNextPage(document: org.jsoup.nodes.Document?): Boolean =
        document != null && document.selectFirst("a.next.page-numbers") != null

    /**
     * JSON-LD `thumbnailUrl` — the migrated theme serves an array (`"thumbnailUrl":["…"]`),
     * the pre-migration bare string form stays supported. Absent → null.
     */
    fun thumbnail(jsonLd: String?): String? = jsonLd
        ?.let { Regex("\"thumbnailUrl\"\\s*:\\s*\\[?\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    /**
     * Quick-search payload (`/wp-json/turbosearch/v1/suggest`): posts[] with
     * title/url/thumb. Blank thumbs → null poster; malformed payload →
     * no posts, never a throw.
     */
    fun suggestPosts(json: String): List<com.kraptor.CardFields> = try {
        val posts = jsonMapper.readTree(json).get("posts") as? com.fasterxml.jackson.databind.node.ArrayNode
            ?: return emptyList()
        val out = ArrayList<com.kraptor.CardFields>(posts.size())
        var i = 0
        while (i < posts.size()) {
            val post = posts.get(i)
            val title = post.get("title")?.asText()?.trim().takeUnless { t -> t.isNullOrEmpty() }
            val href = post.get("url")?.asText()?.trim().takeUnless { t -> t.isNullOrEmpty() }
            if (title != null && href != null) {
                val poster = post.get("thumb")?.asText()?.trim()?.takeIf { it.startsWith("http") }
                out.add(com.kraptor.CardFields(title, href, poster))
            }
            i++
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}
