package com.rjbiermann

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1 card parse (TDD-first, ADR-0005). Fixtures are live captures of yesporn.vip
 * (2026-11-26, mobile Chrome UA) — the home page mixes video thumbs with the taxonomy
 * strips and an off-site ad thumb, all sharing the `div.thumb` container, so these tests
 * pin the href-based split that keeps a video row free of taxonomy entries.
 * HTTP flows stay with pipeline Verification.
 */
class HomeParseTest {

    private fun fixture(name: String): Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    private fun ids(cards: List<YesPornVipParse.Card>) = cards.mapNotNull { it.videoId }

    @Test
    fun homePageYieldsEveryVideoCardAndNothingElse() {
        val cards = YesPornVipParse.cards(fixture("home.html"))
        // 19 video thumbs on the captured home page; the Channels / Models / Categories
        // strips and the off-site ad thumb share `div.thumb` and must be filtered out.
        assertEquals(19, cards.size)
        assertEquals(19, ids(cards).toSet().size)
        assertTrue(cards.all { it.videoId != null })
        assertTrue(cards.all { it.title.isNotBlank() })
    }

    @Test
    fun knownCardKeepsItsIdAndSlug() {
        val card = YesPornVipParse.cards(fixture("home.html")).first { it.videoId == "76391" }
        assertEquals("https://yesporn.vip/video/76391/sweet-as-sugar-episode-3-ebk44f/", card.href)
        assertEquals("Sweet As Sugar Episode 3", card.title)
        // title attribute wins over the visible text (same string here, pinned as the source)
        assertEquals("https://yesporn.vip/contents/videos_screenshots/76000/76391/390x218/1.jpg", card.poster)
    }

    @Test
    fun lazyLoadedPosterResolvesToTheRealImage() {
        // Video 76623 is a `lazy-load` card: `src` is the 1px `data:` gif and the poster
        // lives in `data-original` — the card must never carry the placeholder.
        val card = YesPornVipParse.cards(fixture("latest-updates-1.html")).first { it.videoId == "76623" }
        assertEquals("Code 34", card.title)
        assertFalse(card.poster!!.startsWith("data:"))
        assertEquals("https://yesporn.vip/contents/videos_screenshots/76000/76623/390x218/1.jpg", card.poster)
    }

    @Test
    fun duplicateCardCollapsesOnTheVideoId() {
        // The same video twice, with the two slug spellings the site serves for it
        // (`-ebk44f` on the home page, `-2av635` on the listing) — the id is the identity,
        // so exactly one card survives.
        val html = """
            <div class="thumb item">
              <a href="https://yesporn.vip/video/76391/sweet-as-sugar-episode-3-ebk44f/" title="Sweet As Sugar Episode 3">
                <img src="https://yesporn.vip/contents/videos_screenshots/76000/76391/390x218/1.jpg"/>
              </a>
            </div>
            <div class="thumb item">
              <a href="https://yesporn.vip/video/76391/sweet-as-sugar-episode-3-2av635/" title="Sweet As Sugar Episode 3">
                <img src="https://yesporn.vip/contents/videos_screenshots/76000/76391/390x218/1.jpg"/>
              </a>
            </div>
        """.trimIndent()
        val cards = YesPornVipParse.cards(Jsoup.parse(html))
        assertEquals(1, cards.size)
        assertEquals(listOf("76391"), ids(cards))
    }

    @Test
    fun brokenCardIsDroppedWithoutKillingTheRow() {
        // One card without a title, one ad thumb that links off-site, one good card.
        val html = """
            <div class="thumb item"><a href="https://yesporn.vip/video/1/no-title/"><img src="x.jpg"/></a></div>
            <div class="thumb item"><a href="https://clothoff.app/r/yespornthumb26">ad</a></div>
            <div class="thumb item"><a href="https://yesporn.vip/video/2/real/" title="Real">Real</a></div>
        """.trimIndent()
        val cards = YesPornVipParse.cards(Jsoup.parse(html))
        assertEquals(1, cards.size)
        assertEquals("https://yesporn.vip/video/2/real/", cards.single().href)
    }

    @Test
    fun browseRowsListTaxonomyEntriesNotVideoOrAdThumbs() {
        val categories = YesPornVipParse.browseCards(fixture("categories-index.html"), "/categories/")
        assertTrue(categories.size > 0)
        assertTrue(categories.all { it.href.startsWith("https://yesporn.vip/categories/") })
        assertTrue(categories.none { it.href == "https://yesporn.vip/categories/" })
        assertTrue(categories.all { it.videoId == null })
        val japanese = categories.first { it.href.endsWith("/categories/japanese/") }
        assertEquals("Japanese", japanese.title)
        assertEquals("https://yesporn.vip/contents/categories/72/s1_Japanese.jpg", japanese.poster)

        val channels = YesPornVipParse.browseCards(fixture("channels-index.html"), "/channels/")
        assertTrue(channels.size > 0)
        assertTrue(channels.all { it.href.startsWith("https://yesporn.vip/channels/") })
        // Channels are the lazy-load index: the poster only exists in `data-original`.
        val channel = channels.first { it.href.endsWith("/channels/xxxjobinterviews-z2i1uw/") }
        assertEquals("XXXJobInterviews", channel.title)
        assertEquals("https://yesporn.vip/contents/dvds/377/cf1_DCDSD.jpg", channel.poster)

        val models = YesPornVipParse.browseCards(fixture("models-index.html"), "/models/")
        assertTrue(models.size > 0)
        assertTrue(models.all { it.href.startsWith("https://yesporn.vip/models/") })
        assertEquals("Jodie Johnson", models.first { it.href.endsWith("/models/jodie-johnson-pnbhnj/") }.title)
    }

    @Test
    fun relativeAndProtocolRelativeHrefsNormaliseToTheSite() {
        val html = """
            <div class="thumb item"><a href="/video/9/rel/" title="Rel">Rel</a></div>
            <div class="thumb item"><a href="//yesporn.vip/video/10/proto/" title="Proto">Proto</a></div>
        """.trimIndent()
        val cards = YesPornVipParse.cards(Jsoup.parse(html))
        assertEquals(
            listOf("https://yesporn.vip/video/9/rel/", "https://yesporn.vip/video/10/proto/"),
            cards.map { it.href },
        )
    }

    @Test
    fun cardWithoutAnyTitleIsNotInventedFromTheSlug() {
        val html = """<div class="thumb item"><a href="https://yesporn.vip/video/3/slug-only/"></a></div>"""
        assertNull(YesPornVipParse.cards(Jsoup.parse(html)).firstOrNull())
    }
}
