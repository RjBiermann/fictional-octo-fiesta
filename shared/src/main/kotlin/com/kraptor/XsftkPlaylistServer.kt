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

import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object XsftkPlaylistServer {
    /** Playlists kept for replay (the player re-requests a manifest on retry/seeks). One
     *  gated video publishes up to 6 links (3 qualities + 3 `(alt)` copies), and a media
     *  playlist is re-fetched after a fatal segment error, so the cap has to leave room
     *  for browsing: 64 entries ≈ 10 videos ≈ 2.8 MB at the measured 44 kB maximum. */
    internal const val MAX_ENTRIES = 64

    /** Longest request line accepted; a peer that sends more is malformed, not a player. */
    private const val MAX_REQUEST_LINE = 2_048

    /** Request headers drained before the response (bounded so a flood cannot pin a worker). */
    private const val MAX_HEADER_LINES = 64

    /** A loopback fetch completes in milliseconds; a stalled peer is dropped, not waited on. */
    private const val READ_TIMEOUT_MS = 5_000

    /** Serving threads. A stalled connection parks one worker, never the accept loop. */
    private const val WORKERS = 4

    private val playlists = object : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_ENTRIES
    }

    @Volatile
    private var port = -1

    /** Optional diagnostics sink (HostAdapters wires logcat; unit tests leave it null). */
    @Volatile
    var logger: ((String) -> Unit)? = null

    private val workers = ThreadPoolExecutor(
        WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WORKERS * 2),
        { runnable -> Thread(runnable, "xsftk-playlist-conn").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

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
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    // One bad connection must never stop the host; the next accept loops.
                    if (server.isClosed) return@Thread
                    log("accept failed: ${e.javaClass.simpleName}")
                    continue
                }
                try {
                    workers.execute { serve(client) }
                } catch (e: RejectedExecutionException) {
                    log("serve rejected: queue full")
                    closeQuietly(client)
                }
            }
        }, "xsftk-playlist").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { client ->
                // #1353 review: without a timeout, a client that opens the socket and
                // never finishes its request line parks a worker forever.
                client.soTimeout = READ_TIMEOUT_MS
                val reader = client.getInputStream().bufferedReader()
                val requestLine = readLine(reader) ?: return
                // Drain the request headers before responding: closing a socket that still
                // has unread bytes makes Linux send RST instead of FIN, truncating the reply.
                var headers = 0
                while (headers < MAX_HEADER_LINES) {
                    val line = readLine(reader) ?: break
                    headers++
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
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
        } catch (e: Exception) {
            // SocketTimeoutException for a stalled peer; never let it reach the accept loop.
            log("serve failed: ${e.javaClass.simpleName}")
        }
    }

    /** One CRLF-terminated line, capped at [MAX_REQUEST_LINE] chars; null on EOF/overrun. */
    private fun readLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            if (c == '\r'.code) continue
            if (line.length >= MAX_REQUEST_LINE) return null
            line.append(c.toChar())
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun log(message: String) {
        try {
            logger?.invoke(message)
        } catch (e: Exception) {
            // diagnostics must never take the host down
        }
    }
}
