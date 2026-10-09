package com.byayzen

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0005: issue #534 — javtiful.com redesign, live saves 2026-10-09. The site dropped
 *  the whole `front-*` vocabulary: cards are `article.video-card`, pagination is
 *  `a.pagination__link`, watch page is `watch-title`/`watch-detail`/`watch-actor-card`,
 *  and the player config script moved from `#frontWatchConfig` to `script#watch-config`. */
class JavtifulParseTest {

    private fun fixture(name: String): Element =
        Jsoup.parse(javaClass.getResourceAsStream("/$name")!!, "UTF-8", "https://javtiful.com")

    private val home by lazy { fixture("javtiful-home.html") }
    private val homePage2 by lazy { fixture("javtiful-home-page2.html") }
    private val search by lazy { fixture("javtiful-search.html") }
    private val watch by lazy { fixture("javtiful-watch.html") }

    @Test fun `listing cards are article video-card excluding partner ads`() {
        val cards = videoCards(home)
        assertEquals(23, cards.size)
        assertTrue(cards.none { it.hasClass("video-card--partner") })
        assertTrue(cards.all { it.selectFirst("a.video-card__title") != null })
    }

    @Test fun `search page uses the same card markup`() {
        assertTrue(videoCards(search).isNotEmpty())
    }

    @Test fun `card parses title href and lazy poster`() {
        val (title, href, poster) = videoCard(videoCards(home).first()!!)!!
        assertEquals(
            "MDSR-0006-2 Xiaofeng becomes a sex slave to her cold new wife after her wedding",
            title
        )
        assertEquals("/video/61787/mdsr-0006-2", href)
        // real thumb comes from data-front-lazy-src; src is only the placeholder svg
        assertTrue(poster.startsWith("/uploads/uploads/videos/thumbs/"))
        assertFalse(poster.endsWith(".svg"))
    }

    @Test fun `card without title link is dropped`() {
        val bare = Jsoup.parse("<article class=\"video-card\"></article>").body().child(0)
        assertNull(videoCard(bare))    }

    @Test fun `pagination Next enables page hunting`() {
        assertTrue(hasNextPage(home))
        assertTrue(hasNextPage(homePage2))
        assertFalse(hasNextPage(watch)) // no pagination on a watch page
        // a disabled Next (last page) must not claim another page
        val last = Jsoup.parse(
            """<div><a class="pagination__link" href="?">1</a>
               <a class="pagination__link is-disabled" href="#" aria-disabled="true">Next</a></div>"""
        ).body()
        assertFalse(hasNextPage(last))
    }

    @Test fun `page 2 lists different cards than page 1`() {
        val h1 = videoCards(home).mapNotNull { videoCard(it) }
        val h2 = videoCards(homePage2).mapNotNull { videoCard(it) }
        assertTrue(h2.isNotEmpty())
        assertTrue(h1.map { it.second }.intersect(h2.map { it.second }).isEmpty())
    }

    @Test fun `watch title lives in watch-title h1`() {
        assertEquals(
            "ROYD-238 My innocent stepson was actually a super slut! Big breasted wife falls " +
                "into the pleasure of the virile cocks of a group of bratty boys, Yurika Aoi",
            watch.selectFirst("div.watch-title h1")!!.text().trim()
        )
    }

    @Test fun `year comes from the Added on watch-detail datetime`() {
        assertEquals(2025, addedOnYear(watch))
        assertNull(addedOnYear(home))
    }

    @Test fun `tags chips pick categories and plain tags`() {
        val tags = watchTags(watch)
        assertTrue("creampie" in tags)
        assertTrue(tags.contains("Married Woman")) // chip carries is-category
        assertTrue(tags.size >= 4)
        assertTrue(tags.none { it.contains("Added on") })
    }

    @Test fun `related grid is video-grid related-grid cards`() {
        val cards = watch.select("div.video-grid.related-grid article.video-card")
        assertTrue(cards.size >= 10)
        val (title, href, _) = videoCard(cards.first()!!)!!
        assertTrue(title.isNotBlank())
        assertTrue(href.startsWith("/video/"))
    }

    /** The redesign also moved the player config id (#frontWatchConfig → #watch-config);
     *  schema (playerSources src/type/size) is unchanged. */
    @Test fun `watch-config script carries playerSources`() {
        val raw = watch.selectFirst("script#watch-config")!!.data()
        val cfg = ObjectMapper().registerKotlinModule()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue<WatchConfigTest>(raw)
        val src = cfg.playerSources!!.first().src
        assertTrue(src.contains("fast-stream.jav.si/p/"))
        assertEquals("video/mp4", cfg.playerSources!!.first().type)
        assertNotEquals(0, cfg.playerSources!!.first().size)
    }

    private data class WatchConfigTest(
        @param:JsonProperty("playerSources") val playerSources: List<Src>? = null
    )
    private data class Src(
        @param:JsonProperty("src") val src: String,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("size") val size: Int? = null
    )
}
