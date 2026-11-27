// TDD parse tests for the PornSlash seam (ADR-0005, S1 #1643 + S2 #1644 + S3 #1645 + S4 #1646).
// No HTTP: every fixture is a live capture from 2026-11-26/27 —
//   home-p1.html            = /videos/ page 1, 50 cards (S2's `Latest` surface; the bare `/`
//                             serves the same 50-card grid)
//   search-milf-p1.html     = /search/milf/ page 1, 25 cards
//   cat-milf-p1.html        = /cat/milf/ page 1, 25 cards (S2's curated category-row shape)
//   cat-partial.html        = /cat/tired+working+computer/, a 1-card partial page — the
//                             pagination stop-rule evidence
//   categories.html         = /categories/, the 92-tile listing the curated rows are pinned from
//   watch-*.html            = /watch/<id> detail pages, long-form + shorts item
//   shorts-p1.json          = /get/shorts?p=1, 10 items — S4's shorts-row fixture
//   shorts-p2.json          = /get/shorts?p=2, 10 different items — the pagination evidence
//                             (0 shared item_id with page 1)
// The shorts feeds are captured with the `url` field (the ephemeral playback base) dropped: S4's
// play path is the mp4 each `/watch/<item_id>` page derives, so no tunnel host is read at all.
// Titles are never asserted as literals: the expected value is always the fixture attribute
// the parse function is supposed to read, so the tests document the selector contract.
package com.rjbiermann

import com.kraptor.DistinctBar
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PornSlashParseTest {

    private fun doc(res: String): Document {
        val html = javaClass.getResourceAsStream(res)!!.reader().readText()
        return Jsoup.parse(html, "https://www.pornslash.com/")
    }

    private fun text(res: String): String = javaClass.getResourceAsStream(res)!!.reader().readText()

    private fun json(res: String): List<SlashCard> = PornSlashParse.shortsCards(text(res))

    // ---- card() -------------------------------------------------------------------

    @Test
    fun `home page yields 50 cards, all parseable`() {
        val document = doc("/home-p1.html")
        assertEquals(50, document.select(PornSlashParse.CARD).size)
        assertEquals(50, PornSlashParse.cards(document).size)
        assertEquals(PornSlashParse.PAGE_SIZE_VIDEOS, PornSlashParse.cards(document).size)
    }

    @Test
    fun `card identities are distinct (Distinct bar, fixture level)`() {
        // The bar exists to catch the placeholder/misparse class: a listing that parses to N
        // cards but only M identities. Fixtures already satisfy it, so this is a guard.
        for ((label, res) in listOf(
            "home" to "/home-p1.html",
            "search" to "/search-milf-p1.html"
        )) {
            val cards = doc(res).select(PornSlashParse.CARD).mapNotNull { PornSlashParse.card(it) }
            DistinctBar.assertDistinctVideos(
                cards.map { DistinctBar.VideoIdentity(it.title, it.poster, it.id) }
            )
            assertTrue("$label: blank title", cards.all { it.title.isNotBlank() })
            assertTrue("$label: missing poster", cards.all { it.poster != null })
        }
    }

    @Test
    fun `home card carries id title poster quality and duration`() {
        val el = doc("/home-p1.html").select("div.video-item").first()!!
        val card = PornSlashParse.card(el)!!
        val meta = el.selectFirst("div.video-info")!!

        // id: the watch route's own id (data-id), not a derived slug
        assertEquals(meta.attr("data-id"), card.id)
        assertEquals(meta.attr("data-title"), card.title)
        assertEquals(meta.attr("data-quality") + "p", card.quality)
        assertEquals(meta.attr("data-duration").toInt(), card.duration)
        // poster: the thumbnail image, absolute on the per-video *.pages.dev project
        val poster = card.poster!!
        assertTrue(poster.startsWith("https://"))
        assertTrue(poster.contains("/t/"))
    }

    @Test
    fun `search page yields 25 cards, all parseable`() {
        val document = doc("/search-milf-p1.html")
        assertEquals(25, document.select(PornSlashParse.CARD).size)
        assertEquals(25, PornSlashParse.cards(document).size)
        assertEquals(PornSlashParse.PAGE_SIZE_SEARCH, PornSlashParse.cards(document).size)
    }

    @Test
    fun `curated category page yields a full 25-card page`() {
        // The shape every one of the eight curated rows requests: /cat/<slug>/ page 1.
        val document = doc("/cat-milf-p1.html")
        val cards = PornSlashParse.cards(document)
        assertEquals(25, cards.size)
        assertEquals(PornSlashParse.PAGE_SIZE_SEARCH, cards.size)
        // a full page means another page may exist (the row must stay scrollable)
        assertTrue(PornSlashParse.hasNext(cards.size, PornSlashParse.PAGE_SIZE_SEARCH))
    }

    @Test
    fun `partial category page yields its single card`() {
        val cards = PornSlashParse.cards(doc("/cat-partial.html"))
        assertEquals(1, cards.size)
        assertTrue(cards.first().quality == "480p")
        assertFalse(PornSlashParse.hasNext(cards.size, PornSlashParse.PAGE_SIZE_SEARCH))
    }

    @Test
    fun `card without an id or a title is dropped, never half-filled`() {
        assertNull(PornSlashParse.card(Jsoup.parse("<div class=\"video-item\"><img src=\"https://x/t/a.jpeg\"></div>")))
        assertNull(
            PornSlashParse.card(
                Jsoup.parse(
                    "<div class=\"video-item\"><a href=\"/watch/abc\"></a>" +
                        "<div class=\"video-info\" data-id=\"abc\"></div></div>"
                )
            )
        )
    }

    @Test
    fun `duration falls back to the card clock when data-duration is absent`() {
        val el = Jsoup.parse(
            "<div class=\"video-item\"><a href=\"/watch/abc\"><div class=\"thumbnail\">" +
                "<img src=\"https://cdn.pages.dev/1/t/enc.jpeg\"><div class=\"quality\">720p</div>" +
                "<div class=\"duration\">1:01:12</div></div></a>" +
                "<div class=\"video-info\" data-id=\"abc\" data-title=\"t\"></div></div>"
        ).selectFirst("div.video-item")!!
        assertEquals(3672, PornSlashParse.card(el)!!.duration)
    }

    // ---- shorts feed (/get/shorts) ------------------------------------------------

    @Test
    fun `shorts feed page 1 yields ten cards with title poster duration and quality`() {
        val raw = text("/shorts-p1.json")
        val cards = json("/shorts-p1.json")

        assertEquals(10, cards.size)
        assertEquals(PornSlashParse.PAGE_SIZE_SHORTS, cards.size)
        // cards follow the feed's own item order
        assertEquals(
            Regex("\"item_id\":\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList(),
            cards.map { it.id }
        )
        // every field is the feed's own value under the key the parse is supposed to read
        for (card in cards) {
            assertTrue("title not from the feed: ${card.title}", raw.contains("\"title\":\"${card.title}\""))
            assertTrue("poster not from the feed: ${card.poster}", raw.contains("\"poster\":\"${card.poster}\""))
            assertTrue("quality not from the feed: ${card.quality}", raw.contains("\"quality\":${card.quality!!.removeSuffix("p")}"))
            assertTrue("duration not from the feed: ${card.duration}", raw.contains("\"duration\":${card.duration}"))
        }
    }

    @Test
    fun `shorts feed page 2 is a full page with no id shared with page 1`() {
        val page1 = json("/shorts-p1.json")
        val page2 = json("/shorts-p2.json")
        assertEquals(10, page1.size)
        assertEquals(10, page2.size)
        assertEquals(0, page1.map { it.id }.intersect(page2.map { it.id }.toSet()).size)
        assertEquals(20, (page1 + page2).map { it.id }.distinct().size)
    }

    @Test
    fun `a full shorts page means another page may exist`() {
        assertEquals(10, PornSlashParse.PAGE_SIZE_SHORTS)
        assertTrue(PornSlashParse.hasNext(json("/shorts-p1.json").size, PornSlashParse.PAGE_SIZE_SHORTS))
        assertFalse(PornSlashParse.hasNext(9, PornSlashParse.PAGE_SIZE_SHORTS))
    }

    @Test
    fun `shorts cards link the watch route the detail surface parses`() {
        val card = json("/shorts-p1.json").first()
        assertEquals("https://www.pornslash.com/watch/${card.id}", PornSlashParse.watchUrl(card.id))
        // the row pages with the same `?p=`-only contract as every other surface
        assertEquals(PornSlashParse.SHORTS, PornSlashParse.pageUrl(PornSlashParse.SHORTS, 1))
        assertEquals("https://www.pornslash.com/get/shorts?p=2", PornSlashParse.pageUrl(PornSlashParse.SHORTS, 2))
    }

    @Test
    fun `the feed's playback url is never read, the poster falls back to the image`() {
        // S4's play path is the mp4 derived from /watch/<item_id>: an item's own playback url
        // must not influence the card at all, so the parse is identical with and without it.
        val withoutUrl = """{"videos":[{"item_id":"abc","title":"t","poster":"https://cdn/t/a.jpeg",""" +
            """"duration":30,"quality":720}]}"""
        val withUrl = """{"videos":[{"item_id":"abc","title":"t","poster":"https://cdn/t/a.jpeg",""" +
            """"duration":30,"quality":720,"url":"https://example.invalid/master/x"}]}"""
        assertEquals(PornSlashParse.shortsCards(withoutUrl), PornSlashParse.shortsCards(withUrl))

        // feed without a poster: the item's own `image` is the grid poster
        val imageOnly = """{"videos":[{"item_id":"abc","title":"t","image":"https://cdn/t/a.jpeg"}]}"""
        assertEquals("https://cdn/t/a.jpeg", PornSlashParse.shortsCards(imageOnly).first().poster)
    }

    @Test
    fun `shorts items without an id or a title are dropped and junk yields no cards`() {
        assertEquals(0, PornSlashParse.shortsCards(null).size)
        assertEquals(0, PornSlashParse.shortsCards("").size)
        assertEquals(0, PornSlashParse.shortsCards("<html>not json</html>").size)
        assertEquals(0, PornSlashParse.shortsCards("{}").size)
        assertEquals(0, PornSlashParse.shortsCards("""{"videos":[]}""").size)
        assertEquals(
            0,
            PornSlashParse.shortsCards(
                """{"videos":[{"title":"no id"},{"item_id":"abc"},{"item_id":"","title":"blank id"}]}"""
            ).size
        )
        // one broken item never kills the page
        assertEquals(
            1,
            PornSlashParse.shortsCards(
                """{"videos":[{"title":"no id"},{"item_id":"abc","title":"kept"}]}"""
            ).size
        )
    }

    // ---- pageUrl() ----------------------------------------------------------------

    @Test
    fun `page url is a query param only, page 1 is the bare surface`() {
        val videos = "https://www.pornslash.com/videos/"
        assertEquals(videos, PornSlashParse.pageUrl(videos, 1))
        assertEquals("${videos}?p=2", PornSlashParse.pageUrl(videos, 2))
        assertEquals(
            "https://www.pornslash.com/search/milf/?p=2",
            PornSlashParse.pageUrl("https://www.pornslash.com/search/milf/", 2)
        )
        assertEquals(
            "https://www.pornslash.com/cat/milf/?p=2",
            PornSlashParse.pageUrl("https://www.pornslash.com/cat/milf/", 2)
        )
        assertEquals(
            "https://www.pornslash.com/get/shorts?p=2",
            PornSlashParse.pageUrl("https://www.pornslash.com/get/shorts", 2)
        )
    }

    @Test
    fun `page url drops a stale p param instead of stacking it`() {
        assertEquals(
            "https://www.pornslash.com/get/shorts?p=3",
            PornSlashParse.pageUrl("https://www.pornslash.com/get/shorts?p=1", 3)
        )
        assertEquals(
            "https://www.pornslash.com/get/shorts",
            PornSlashParse.pageUrl("https://www.pornslash.com/get/shorts?p=7", 1)
        )
    }

    @Test
    fun `page url keeps a sibling query param and only rewrites p`() {
        // S5's browse filters put `p` next to other params: the `?` belongs to the query,
        // not to the `p` pair, so removing only that pair must not eat it.
        val filtered = "https://www.pornslash.com/get/shorts?p=1&sort=new"
        assertEquals(
            "https://www.pornslash.com/get/shorts?sort=new",
            PornSlashParse.pageUrl(filtered, 1)
        )
        assertEquals(
            "https://www.pornslash.com/get/shorts?sort=new&p=2",
            PornSlashParse.pageUrl(filtered, 2)
        )
    }

    // ---- searchUrl() --------------------------------------------------------------

    @Test
    fun `search url is the raw query, percent-encoded, page 1 bare`() {
        assertEquals(
            "https://www.pornslash.com/search/milf/",
            PornSlashParse.searchUrl("milf", 1)
        )
        assertEquals(
            "https://www.pornslash.com/search/milf/?p=2",
            PornSlashParse.searchUrl("milf", 2)
        )
        // `%20`, never the Porntrex `-` normalization and never a bare space
        assertEquals(
            "https://www.pornslash.com/search/tired%20working%20computer/",
            PornSlashParse.searchUrl("tired working computer", 1)
        )
        assertEquals(
            "https://www.pornslash.com/search/tired%20working%20computer/?p=3",
            PornSlashParse.searchUrl("  tired working computer  ", 3)
        )
    }

    // ---- curated home rows --------------------------------------------------------

    @Test
    fun `every curated category row is still a tile on the live categories page`() {
        // Drift guard: the pinned slugs+labels are read from `/categories/`, so a slug the site
        // retires (or a relabelled tile) fails here instead of becoming a dead home row.
        val tiles = doc("/categories.html").select(PornSlashParse.CATEGORY_ITEM)
            .mapNotNull { tile ->
                val slug = tile.attr("href").trim('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() && !it.contains('/') }
                val label = tile.selectFirst(".cat-name")?.text()?.trim()
                if (slug == null || label.isNullOrEmpty()) null else slug to label
            }
        assertTrue("categories fixture yields no tiles", tiles.isNotEmpty())
        assertEquals(8, PornSlashParse.CURATED_CATEGORIES.size)
        for ((slug, label) in PornSlashParse.CURATED_CATEGORIES) {
            val live = tiles.firstOrNull { it.first == slug }?.second
            assertNotNull("curated slug /cat/$slug/ is no longer listed", live)
            assertEquals(
                "curated row label for /cat/$slug/ drifted",
                live!!.lowercase(),
                label.lowercase()
            )
            assertEquals(
                "https://www.pornslash.com/cat/$slug/",
                PornSlashParse.categoryUrl(slug)
            )
        }
    }

    @Test
    fun `home rows stay under the ten-row cap`() {
        // Latest + the shorts feed + the eight curated categories; the spec caps home at ten rows.
        assertEquals(10, 2 + PornSlashParse.CURATED_CATEGORIES.size)
        assertTrue(2 + PornSlashParse.CURATED_CATEGORIES.size <= 10)
    }

    // ---- mp4FromThumb() -----------------------------------------------------------

    @Test
    fun `thumbnail path yields the mp4 on the same cdn host`() {
        val thumb = "https://cscxm34s5k-642.pages.dev/6420/t/HxtzetMQwouQzwkQkwtn.jpeg"
        assertEquals(
            "https://cscxm34s5k-642.pages.dev/6420/p/HxtzetMQwouQzwkQkwtn.mp4",
            PornSlashParse.mp4FromThumb(thumb)
        )
        // host and per-video <ptid> segment are preserved, never templated
        val derived = PornSlashParse.mp4FromThumb(thumb)!!
        assertEquals("cscxm34s5k-642.pages.dev", derived.substringAfter("://").substringBefore('/'))
        assertTrue(derived.contains("/6420/p/"))
    }

    @Test
    fun `mp4 derivation honours the live detail page image`() {
        val og = doc("/watch-1ZxeqYAL0xy.html").selectFirst("meta[property=og:image]")!!.attr("content")
        val derived = PornSlashParse.mp4FromThumb(og)
        assertNotNull(derived)
        assertTrue(derived!!.endsWith(".mp4"))
        assertTrue(derived.contains("/p/"))
        assertEquals(og.substringBefore("/t/"), derived.substringBefore("/p/"))
    }

    @Test
    fun `a non-thumbnail url never fabricates a stream`() {
        assertNull(PornSlashParse.mp4FromThumb(null))
        assertNull(PornSlashParse.mp4FromThumb(""))
        assertNull(PornSlashParse.mp4FromThumb("https://www.pornslash.com/logo.png"))
        assertNull(PornSlashParse.mp4FromThumb("https://cdn.pages.dev/1/t/enc"))
    }

    // ---- detail() -----------------------------------------------------------------

    @Test
    fun `detail page carries title poster tags description and quality`() {
        val document = doc("/watch-1ZxeqYAL0xy.html")
        val detail = PornSlashParse.detail(document)!!
        val share = document.selectFirst("div.share-video")!!

        assertEquals(document.selectFirst("h1.video-page-title")!!.text().trim(), detail.title)
        assertEquals(document.selectFirst("meta[property=og:image]")!!.attr("content"), detail.poster)
        assertEquals(
            document.selectFirst("meta[name=description]")!!.attr("content"),
            detail.description
        )
        assertEquals(share.attr("data-quality") + "p", detail.quality)
        assertEquals(
            document.select("meta[property=og:video:tag]").map { it.attr("content") },
            detail.tags
        )
    }

    @Test
    fun `duration is minutes and never invented from a sub-minute value`() {
        // the live og:duration on these pages is bare seconds ("7", "681")
        assertEquals(11, PornSlashParse.durationMinutes("681", null))
        assertNull(PornSlashParse.durationMinutes("7", null))
        assertNull(PornSlashParse.durationMinutes(null, null))
        assertNull(PornSlashParse.durationMinutes("", ""))
        // the ISO-8601 grammar (ld+json `duration`) is read when the site ships it
        assertEquals(1, PornSlashParse.durationMinutes("PT1M21S", null))
        assertEquals(62, PornSlashParse.durationMinutes(null, "{\"duration\": \"PT1H2M3S\"}"))
        assertEquals(3, PornSlashParse.durationMinutes(null, "{\"duration\": \"PT3M\"}"))
    }

    @Test
    fun `detail derives the mp4 primary from the page's own thumbnail`() {
        for (fixture in listOf("/watch-1ZxeqYAL0xy.html", "/watch-shorts-Vpg8p8DYegA.html")) {
            val detail = PornSlashParse.detail(doc(fixture))!!

            assertNotNull("no mp4 from $fixture", detail.mp4)
            assertTrue(detail.mp4!!.endsWith(".mp4"))
            assertTrue(detail.mp4!!.contains("/p/"))
            // host and <ptid> segment come from the page's own og:image, never templated
            assertEquals(detail.poster!!.substringBefore("/t/"), detail.mp4!!.substringBefore("/p/"))
        }
    }

    @Test
    fun `related cards are the watch page's recommendation surface`() {
        val document = doc("/watch-1ZxeqYAL0xy.html")
        val detail = PornSlashParse.detail(document)!!
        val selfId = document.selectFirst("div.share-video")!!.attr("data-id")

        assertEquals(50, document.select("div.related div.video-item").size)
        assertEquals(50, detail.recommendations.size)
        assertTrue("recommendations link the video itself", detail.recommendations.none { it.id == selfId })
        // the related rail repeats titles as site content (two cards can share one), so the
        // identity bar here is id + poster path — both derive from the per-video encode id
        assertEquals(50, detail.recommendations.map { it.id }.distinct().size)
        assertEquals(50, detail.recommendations.mapNotNull { it.poster }.distinct().size)
        assertTrue("blank recommendation title", detail.recommendations.all { it.title.isNotBlank() })
        // the spec's cap: more related cards than 50 still yield at most 50
        assertEquals(3, PornSlashParse.recommendations(document, limit = 3).size)
    }

    @Test
    fun `watch fixtures satisfy the distinct bar`() {
        val details = listOf("/watch-1ZxeqYAL0xy.html", "/watch-shorts-Vpg8p8DYegA.html")
            .map { PornSlashParse.detail(doc(it))!! }
        DistinctBar.assertDistinctVideos(
            details.map { DistinctBar.VideoIdentity(it.title, it.poster, it.mp4) }
        )
    }

    @Test
    fun `detail is null when the page carries no title`() {
        assertNull(PornSlashParse.detail(Jsoup.parse("<html><body><p>gone</p></body></html>")))
    }

    // ---- quality() ----------------------------------------------------------------

    @Test
    fun `quality normalises a bare number and invents nothing`() {
        assertEquals("480p", PornSlashParse.quality("480"))
        assertEquals("1080p", PornSlashParse.quality("1080p"))
        assertEquals("720p", PornSlashParse.quality(" 720 "))
        assertEquals("360p", PornSlashParse.quality("360"))
        assertNull(PornSlashParse.quality(null))
        assertNull(PornSlashParse.quality(""))
        assertNull(PornSlashParse.quality("HD"))
    }

    // ---- hasNext() ----------------------------------------------------------------

    @Test
    fun `paginate while the page is full, stop on a partial page`() {
        assertTrue(PornSlashParse.hasNext(50, 50))   // /videos/ full page
        assertTrue(PornSlashParse.hasNext(25, 25))   // /search/ full page
        assertTrue(PornSlashParse.hasNext(10, 10))   // /get/shorts full page
        assertFalse(PornSlashParse.hasNext(1, 25))   // /cat/ partial last page
        assertFalse(PornSlashParse.hasNext(24, 25))
        assertFalse(PornSlashParse.hasNext(0, 25))
        assertFalse(PornSlashParse.hasNext(0, 0))
    }
}
