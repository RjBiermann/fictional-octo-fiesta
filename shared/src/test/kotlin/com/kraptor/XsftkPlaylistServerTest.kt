package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Loopback host contract for the synthesized XSFTK playlists (#1353, review round 1):
 * a stalled peer can never wedge the host, a miss is a clean 404, and the eviction cap
 * keeps a playing playlist alive long enough to survive the player's re-fetches.
 */
class XsftkPlaylistServerTest {

    private val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ENDLIST"

    private fun status(url: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun read(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.readTimeout = 8_000
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    @Test fun `a stalled connection cannot wedge the host`() {
        // review round 1, finding 1: a localhost client that connects and never finishes
        // its request line used to park the only accept thread forever, so every later
        // playlist fetch died as 2001. The read timeout is 5 s; the client gives up at 8 s.
        val port = URL(XsftkPlaylistServer.publish(playlist)).port
        val stalled = Socket("127.0.0.1", port)
        try {
            assertEquals(playlist, read(XsftkPlaylistServer.publish(playlist)))
        } finally {
            stalled.close()
        }
    }

    @Test fun `unknown id is a 404, not a hang`() {
        val base = XsftkPlaylistServer.publish(playlist).substringBeforeLast('/')
        val missing = "$base/${"0".repeat(32)}.m3u8"
        assertEquals(404, status(missing))
        // and a served id still works right after the miss (host not wedged)
        assertEquals(playlist, read(XsftkPlaylistServer.publish(playlist)))
    }

    @Test fun `the oldest playlist is evicted at the cap and the newest still serves`() {
        val oldest = XsftkPlaylistServer.publish(playlist)
        var newest = oldest
        repeat(XsftkPlaylistServer.MAX_ENTRIES) {
            newest = XsftkPlaylistServer.publish(playlist)
        }
        assertEquals(404, status(oldest))
        assertEquals(playlist, read(newest))
    }

    @Test fun `the cap leaves room for more than one video`() {
        // a gated video publishes up to 6 links (3 qualities + 3 alt copies); a paused
        // video must survive browsing (review round 1, finding 2)
        assertTrue("MAX_ENTRIES=$XsftkPlaylistServer.MAX_ENTRIES", XsftkPlaylistServer.MAX_ENTRIES >= 6 * 10)
    }
}
