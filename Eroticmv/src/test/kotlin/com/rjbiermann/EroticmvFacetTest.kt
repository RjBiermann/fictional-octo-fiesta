package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Browse-facet mapping (issue #497): mainPage must expose the site's 75
 * /category/<kind>/<slug>/ facets, and facet pages share the homepage card
 * markup (`article.post-item`) with no pagination (theme preloads all cards
 * client-side). Fixtures: facet-*.html captured live 2026-09-29.
 */
class EroticmvFacetTest {

    private fun facetDoc(name: String) =
        Jsoup.parse(
            javaClass.getResourceAsStream("/facet-$name.html")!!, "UTF-8",
            "https://eroticmv.com/"
        )

    @Test fun `facet slugs map to facet urls`() {
        assertEquals(
            "https://eroticmv.com/category/genre/ghost/",
            Eroticmv.facetUrl("genre/ghost")
        )
        assertEquals(
            "https://eroticmv.com/category/country/canada/",
            Eroticmv.facetUrl("country/canada")
        )
        assertEquals(
            "https://eroticmv.com/category/decades/2000s/",
            Eroticmv.facetUrl("decades/2000s")
        )
    }

    @Test fun `country facet resolves with homepage card parser`() {
        val doc = facetDoc("country-canada-country")
        val cards = doc.select("article.post-item").mapNotNull { Eroticmv().parseCard(it) }
        assertTrue("expected cards on canada facet", cards.isNotEmpty())
        assertTrue(cards.first().name.isNotBlank())
        assertTrue(cards.first().url.contains("eroticmv.com/"))
    }

    @Test fun `genre facet resolves cards and titles derive from keys`() {
        val ghost = facetDoc("genre-ghost-genre")
        assertTrue(Eroticmv().parseCard(ghost.selectFirst("article.post-item")!!) != null)
        // facet name = row title, derived from the kind/slug key
        assertEquals("Ghost", Eroticmv.facetTitle("genre/ghost"))
        assertEquals("Canada", Eroticmv.facetTitle("country/canada"))
        assertEquals("2000s", Eroticmv.facetTitle("decades/2000s"))
    }

    @Test fun `decades facet resolves with homepage card parser`() {
        val doc = facetDoc("decades-2000s-decades")
        val cards = doc.select("article.post-item").mapNotNull { Eroticmv().parseCard(it) }
        assertTrue("expected cards on decades/2000s", cards.size >= 6)
    }
}
