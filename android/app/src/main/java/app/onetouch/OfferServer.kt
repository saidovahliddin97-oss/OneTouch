package app.onetouch

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP server that accepts `POST /v1/offer` from desktops. It only
 * receives small JSON metadata; the files themselves are pulled over pinned
 * TLS after the user taps «Получить». Idle cost: one thread blocked in accept().
 */
class OfferServer(private val onOffer: (Offer) -> Unit) {
    private var socket: ServerSocket? = null
    val port: Int get() = socket?.localPort ?: 0

    fun start() {
        val s = ServerSocket(0)
        socket = s
        thread(name = "offer-server", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(c: Socket) {
        c.soTimeout = 5000
        val input = BufferedInputStream(c.getInputStream())
        val request = readLine(input) ?: return
        var length = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("content-length", ignoreCase = true)) {
                length = line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        val parts = request.split(" ")
        if (parts.size < 2 || parts[0] != "POST" || !parts[1].startsWith("/v1/offer")) {
            respond(c, 404, "{\"error\":\"not found\"}")
            return
        }
        if (length <= 0 || length > 64 * 1024) {
            respond(c, 413, "{\"error\":\"bad length\"}")
            return
        }
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) return
            off += n
        }
        val host = c.inetAddress.hostAddress ?: return
        val offer = try {
            Offer.parse(String(body, Charsets.UTF_8), host)
        } catch (e: Exception) {
            respond(c, 400, "{\"error\":\"bad offer\"}")
            return
        }
        respond(c, 200, "{}")
        onOffer(offer)
    }

    private fun respond(c: Socket, code: Int, body: String) {
        val b = body.toByteArray()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(head.toByteArray()); write(b); flush() }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buf.size() > 8192) throw IOException("header too long")
            buf.write(b)
        }
    }
}
