package com.rjbiermann

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browse grids (TDD-first, ADR-0005): Channels / Models / Categories detail pages.
 *
 * Fixtures are live captures (2026-11-27, mobile Chrome UA) of
 * `https://yesporn.vip/channels/brazzers-or8iip/` and `.../2/` — the post-redirect URL, since
 * `/channels/brazzers-z2i1uw/` 301s to it (slug canonicalisation, same as a video page). The
 * index captures (`channels-index.html`, `models-index.html`, `categories-index.html`) are the
 * S1 browse-row fixtures, which are the same index pages this story's rows page through.
 */
class BrowseUrlTest {

    private fun fixture(name: String): Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    private fun ids(name: String): Set<String> =
        YesPornVipParse.cards(fixture(name)).mapNotNull { it.videoId }.toSet()

    // --- URL shape: path paging, every section ---

    @Test
    fun pageOneIsTheSlugPathWithoutAPageSegment() {
        for (section in listOf("channels", "models", "categories")) {
            assertEquals(
                "https://yesporn.vip/$section/some-slug/",
                YesPornVipParse.browseUrl(section, "some-slug", 1),
            )
        }
    }

    @Test
    fun laterPagesAppendThePathSegment() {
        // Unlike search (`?from_videos=`), browse grids page by path: `<path>/<n>/`.
        assertEquals(
            "https://yesporn.vip/channels/brazzers-or8iip/2/",
            YesPornVipParse.browseUrl("channels", "brazzers-or8iip", 2),
        )
        assertEquals(
            "https://yesporn.vip/models/jodie-johnson-pnbhnj/3/",
            YesPornVipParse.browseUrl("models", "jodie-johnson-pnbhnj", 3),
        )
        assertEquals(
            "https://yesporn.vip/categories/japanese/2/",
            YesPornVipParse.browseUrl("categories", "japanese", 2),
        )
    }

    @Test
    fun surroundingSlashesAreTolerated() {
        // A section coming from a browse-row href prefix (`/channels/`) and a slug from a card
        // href (`brazzers-or8iip/`) must not produce `//` or a doubled slash.
        assertEquals(
            "https://yesporn.vip/channels/brazzers-or8iip/2/",
            YesPornVipParse.browseUrl("/channels/", "/brazzers-or8iip/", 2),
        )
    }

    // --- a taxonomy page is recognisable, a video/index page is not ---

    @Test
    fun taxonomyPagesParseIntoSectionSlugAndPage() {
        assertEquals(
            YesPornVipParse.Taxonomy("channels", "brazzers-or8iip", 1),
            YesPornVipParse.taxonomy("https://yesporn.vip/channels/brazzers-or8iip/"),
        )
        assertEquals(
            YesPornVipParse.Taxonomy("models", "jodie-johnson-pnbhnj", 2),
            YesPornVipParse.taxonomy("https://yesporn.vip/models/jodie-johnson-pnbhnj/2/"),
        )
        // Root-relative and query-tailed forms resolve to the same page.
        assertEquals(
            YesPornVipParse.Taxonomy("categories", "japanese", 1),
            YesPornVipParse.taxonomy("/categories/japanese/?x=1"),
        )
    }

    @Test
    fun videoAndIndexPagesAreNotTaxonomyPages() {
        assertNull(YesPornVipParse.taxonomy("https://yesporn.vip/video/76685/the-threesome-tutorial-fhd-bzua6a/"))
        // A bare index has no slug, so it is a row source, not a grid.
        assertNull(YesPornVipParse.taxonomy("https://yesporn.vip/channels/"))
        assertNull(YesPornVipParse.taxonomy("https://yesporn.vip/latest-updates/"))
    }

    // --- the grid itself reuses the S1 card parser ---

    @Test
    fun aBrowseGridYieldsItsVideoCards() {
        val cards = YesPornVipParse.cards(fixture("channels-brazzers.html"))
        assertEquals(YesPornVipParse.PAGE_SIZE, cards.size)
        assertTrue(cards.all { it.videoId != null && it.title.isNotBlank() && it.poster != null })
        assertEquals(
            "https://yesporn.vip/video/75801/cassandra-s-ream-ebk44f/",
            cards.first().href,
        )
    }

    @Test
    fun gridPageTwoDiffersFromPageOne() {
        val page1 = ids("channels-brazzers.html")
        val page2 = ids("channels-brazzers-2.html")
        assertEquals(YesPornVipParse.PAGE_SIZE, page2.size)
        assertTrue(
            "grid page 1 and page 2 must not share a card: ${page1 intersect page2}",
            (page1 intersect page2).isEmpty(),
        )
    }

    @Test
    fun theGridTitleIsThePagesOwnHeading() {
        assertEquals(
            "New Videos from Brazzers",
            YesPornVipParse.browseTitle(fixture("channels-brazzers.html")),
        )
        assertNull(YesPornVipParse.browseTitle(Jsoup.parse("<html><body></body></html>")))
    }

    // --- next-page proof comes from the site's own pager, not a guess ---

    @Test
    fun theSitePagerProvidesTheNextGridPage() {
        // `a.next` is a real path link on browse grids (search's pager is AJAX-only), so an
        // exhausted grid simply has none — no fabricated "more" for a 404 page.
        assertEquals(
            "https://yesporn.vip/channels/brazzers-or8iip/2/",
            YesPornVipParse.browseNextPage(fixture("channels-brazzers.html")),
        )
        assertEquals(
            "https://yesporn.vip/channels/brazzers-or8iip/3/",
            YesPornVipParse.browseNextPage(fixture("channels-brazzers-2.html")),
        )
        val noPager = Jsoup.parse("""<div class="pagination"><a href="#" class="next">Next</a></div>""")
        assertNull(YesPornVipParse.browseNextPage(noPager))
    }

    @Test
    fun theNextGridPageMatchesTheUrlBuilder() {
        // The pager link and `browseUrl` agree on the page-2 address, which is what lets the
        // adapter follow the site's own link instead of rebuilding it from the slug.
        val taxonomy = YesPornVipParse.taxonomy("https://yesporn.vip/channels/brazzers-or8iip/")!!
        assertEquals(
            YesPornVipParse.browseUrl(taxonomy.section, taxonomy.slug, taxonomy.page + 1),
            YesPornVipParse.browseNextPage(fixture("channels-brazzers.html")),
        )
    }
}
