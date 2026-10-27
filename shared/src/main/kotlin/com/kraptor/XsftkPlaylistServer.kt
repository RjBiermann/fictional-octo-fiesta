// Loopback host for the synthesized XSFTK playlists (issue #1353).
//
// The in-app player fetches every source through an HTTP datasource (Cronet, else OkHttp —
// CS3IPlayer.createVideoSource), so a `data:application/vnd.apple.mpegurl;base64,…` link
// never reaches the HLS parser: Cronet answers `net::ERR_UNKNOWN_URL_SCHEME`, which the app
// surfaces as ExoPlayer ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001) — the #840/#1328/
// #1353 symptom, reproduced on the emulator 2026-10-27 (FINDINGS-1353).
//
// The plugin runs inside the app process, so it hosts the playlist itself on 127.0.0.1 and
// hands the player a plain `http://` URL that every datasource can fetch. Segment and key
// URIs inside the playlist are absolute, so nothing else changes at play time.
package com.kraptor

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

object XsftkPlaylistServer {
    /** Playlists kept for replay (the player re-requests a manifest on retry/seeks). */
    private const val MAX_ENTRIES = 16

    private val playlists = object : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_ENTRIES
    }

    @Volatile
    private var port = -1

    private const val HEADERS_OK =
        "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
            "Content-Length: %d\r\nConnection: close\r\n\r\n"
    private const val NOT_FOUND =
        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    /** Stores [playlist] and returns its loopback URL. Returns the same server port on
     *  every call (one server per app process); each playlist gets its own id. */
    @Synchronized
    fun publish(playlist: String): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        synchronized(playlists) {
            playlists[id] = playlist.toByteArray(Charsets.UTF_8)
        }
        if (port <= 0) start()
        return "http://127.0.0.1:$port/$id.m3u8"
    }

    private fun start() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        Thread({
            while (true) {
                try {
                    serve(server.accept())
                } catch (e: Exception) {
                    // One bad connection must never stop the host; the next accept loops.
                    if (server.isClosed) return@Thread
                }
            }
        }, "xsftk-playlist").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val path = client.getInputStream().bufferedReader().readLine()
                ?.split(' ')?.getOrNull(1).orEmpty()
            val body = synchronized(playlists) {
                playlists[path.substringAfterLast('/').removeSuffix(".m3u8")]
            }
            val out = client.getOutputStream()
            if (body == null) {
                out.write(NOT_FOUND.toByteArray(Charsets.UTF_8))
            } else {
                out.write(HEADERS_OK.format(body.size).toByteArray(Charsets.UTF_8))
                out.write(body)
            }
            out.flush()
        }
    }
}
