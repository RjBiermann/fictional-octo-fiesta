package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD seam for the KVS `videofile.php` resolver (issue #1445, ixiporn shape-D tier).
 *
 * ixiporn's import-era pages (2020-12 … 2021-02) embed a **raw** iframe — no
 * `div.video-player` b64 player-x wrapper, no `meta[itemprop=contentUrl]` — pointing at
 * `vjav.com/embed/<id>` or `videotxxx.com/embed/<id>` (canonical host `txxx.com`). Both
 * hosts are KVS sites: the embed page is a JS shell whose player calls
 * `/<origin>/api/videofile.php?video_id=<id>&lifetime=864000`, answering a JSON array
 * whose `video_url` is base64 with Cyrillic homoglyphs substituted for the Latin letters
 * (KVS's own obfuscation, not encryption). The payload is `<path>[,<query>]~`, so the
 * decoded relative media path is joined back to the API origin.
 *
 * Fixtures are live captures (2026-11-22): `kvs_videofile_vjav.json` (390635),
 * `kvs_videofile_txxx.json` (17065297), `kvs_videofile_empty.json` (a dead video's
 * `[]` answer — the decline path, no link, no throw).
 */
class KvsVideofileParseTest {

    private fun resource(path: String) =
        javaClass.getResourceAsStream(path)!!.readBytes().decodeToString()

    private val vjav = resource("/kvs_videofile_vjav.json")
    private val txxx = resource("/kvs_videofile_txxx.json")
    private val empty = resource("/kvs_videofile_empty.json")

    // ── embed URL → id + API endpoint ────────────────────────────────────────────

    @Test fun `vjav embed url yields the video id`() {
        assertEquals("390635", KvsVideofileParse.videoId("https://vjav.com/embed/390635/"))
    }

    @Test fun `videotxxx embed url (query tail) yields the video id`() {
        assertEquals(
            "17065297",
            KvsVideofileParse.videoId("https://videotxxx.com/embed/17065297/?source=728113931")
        )
    }

    @Test fun `non-embed url yields no id`() {
        assertNull(KvsVideofileParse.videoId("https://txxx.com/videos/17065297/"))
    }

    @Test fun `api url is built on the embed origin`() {
        assertEquals(
            "https://vjav.com/api/videofile.php?video_id=390635&lifetime=864000",
            KvsVideofileParse.apiUrl("https://vjav.com/embed/390635/")
        )
        assertEquals(
            "https://videotxxx.com/api/videofile.php?video_id=17065297&lifetime=864000",
            KvsVideofileParse.apiUrl("https://videotxxx.com/embed/17065297/?source=728113931")
        )
    }

    @Test fun `api url of an id-less url is null`() {
        assertNull(KvsVideofileParse.apiUrl("https://vjav.com/embed/"))
    }

    // ── payload deobfuscation ────────────────────────────────────────────────────

    /** vjav puts path and query in two comma-separated base64 parts terminated by `~`. */
    @Test fun `vjav payload decodes to the media path with its query`() {
        val sources = KvsVideofileParse.sources(vjav, "https://vjav.com")
        assertEquals(1, sources.size)
        assertTrue(
            "unexpected vjav media path: ${sources.firstOrNull()?.url}",
            sources.first().url.startsWith(
                "https://vjav.com/get_file/3/64ea014ebd0cf13a4b2d3c03e21b3b43d304767986/390000/390635/390635_hq.mp4/"
            )
        )
        assertTrue(
            "query tail lost: ${sources.first().url}",
            sources.first().url.contains("/?d=910&br=190&ti=")
        )
    }

    /** txxx keeps path and query in ONE base64 part — the `?` is inside the decoded text. */
    @Test fun `txxx payload decodes to the media path with its query`() {
        val sources = KvsVideofileParse.sources(txxx, "https://txxx.com")
        assertEquals(1, sources.size)
        assertTrue(
            "unexpected txxx media path: ${sources.firstOrNull()?.url}",
            sources.first().url.startsWith(
                "https://txxx.com/get_file/22/c6def928bd032a3b995ffc0258a39c340ace47ced9/17065000/17065297/17065297_hq.mp4/"
            )
        )
        assertTrue(
            "query tail lost: ${sources.first().url}",
            sources.first().url.contains("/?d=436&br=423&ti=")
        )
    }

    @Test fun `payload format is preserved as the source label`() {
        assertEquals("_hq.mp4", KvsVideofileParse.sources(vjav, "https://vjav.com").single().format)
    }

    @Test fun `an absolutely-addressed payload is kept as served`() {
        val json = """[{"format":"_hq.mp4","video_url":"aHR0cHM6Ly9jZG4uZXhhbXBsZS9hX2hxLm1wNA==~~"}]"""
        assertEquals(
            listOf("https://cdn.example/a_hq.mp4"),
            KvsVideofileParse.sources(json, "https://vjav.com").map { it.url }
        )
    }

    @Test fun `empty array yields no sources (dead video declines)`() {
        assertTrue(KvsVideofileParse.sources(empty, "https://vjav.com").isEmpty())
    }

    @Test fun `non-json answer yields no sources`() {
        assertTrue(KvsVideofileParse.sources("<html>nope</html>", "https://vjav.com").isEmpty())
    }

    @Test fun `undecodable payload yields no sources`() {
        val json = """[{"format":"_hq.mp4","video_url":"!!!not-base64!!!"}]"""
        assertTrue(KvsVideofileParse.sources(json, "https://vjav.com").isEmpty())
    }

    // ── registry rows (loadExtractor prefix dispatch) ─────────────────────────────

    /**
     * The shape-D embeds are only reachable if a row answers the iframe's own host —
     * `loadExtractor` matches on the ORIGINAL URL, so the videotxxx 302 → txxx.com never
     * helps dispatch.
     */
    @Test fun `ixiporn shape-D embed hosts have registry rows`() {
        val hosts = sharedHostRegistry().map { it.mainUrl.substringAfter("//").substringBefore('/').lowercase() }.toSet()
        val missing = listOf("vjav.com", "videotxxx.com", "txxx.com", "txxx.me").filter { it !in hosts }
        assertTrue("shape-D embed hosts with no registry row (loadExtractor drops them): $missing", missing.isEmpty())
    }
}

