package app.onetouch

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLEncoder
import javax.net.ssl.SSLSocket

/**
 * A screen session with a desktop over pinned TLS (see core/internal/transfer/session.go):
 * an HTTP upgrade, then frames `[type u8][length u32 BE][payload]`.
 */
class Session private constructor(private val socket: SSLSocket, val peer: Peer) {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream, 256 * 1024))
    private val out = BufferedOutputStream(socket.outputStream, 256 * 1024)

    /** Blocks until the next frame; throws on disconnect. */
    fun read(): Pair<Int, ByteArray> {
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (len < 0 || len > 32 shl 20) throw IOException("bad frame length $len")
        val payload = ByteArray(len)
        input.readFully(payload)
        return type to payload
    }

    @Synchronized
    fun write(type: Int, payload: ByteArray) {
        out.write(type)
        out.write(payload.size ushr 24)
        out.write(payload.size ushr 16)
        out.write(payload.size ushr 8)
        out.write(payload.size)
        out.write(payload)
        out.flush()
    }

    fun json(type: Int, obj: JSONObject) = write(type, obj.toString().toByteArray())

    fun close() = runCatching { socket.close() }

    companion object {
        const val HELLO = 1
        const val CONFIG = 2
        const val FRAME = 3
        const val STATUS = 4
        const val OFFER = 6
        const val INPUT = 16
        const val COMMAND = 17

        /** Opens `kind` ("screen" or "mirror"). Blocking; throws with a readable message. */
        fun open(ctx: Context, peer: Peer, kind: String): Session {
            val s = Net.factory(peer.fp).createSocket() as SSLSocket
            try {
                s.connect(InetSocketAddress(peer.host, peer.port), 5000)
                s.soTimeout = 0
                s.tcpNoDelay = true
                s.startHandshake() // fingerprint is checked here
                val q = "id=" + enc(Prefs.deviceId(ctx)) + "&name=" + enc(Prefs.deviceName(ctx)) + "&token=" + enc(Prefs.sessionToken(ctx))
                val req = "GET /v1/$kind?$q HTTP/1.1\r\nHost: ${peer.host}\r\nUpgrade: onetouch\r\nConnection: Upgrade\r\n\r\n"
                s.outputStream.write(req.toByteArray())
                s.outputStream.flush()
                val head = readHead(s.inputStream)
                val status = head.lineSequence().firstOrNull().orEmpty()
                if (!status.contains(" 101 ")) {
                    val body = head.substringAfter("\r\n\r\n", "").ifBlank { status }
                    throw IOException(body.trim().take(200))
                }
                return Session(s, peer)
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Reads the HTTP response head byte by byte so no frame bytes are consumed. */
        private fun readHead(input: InputStream): String {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
                if (sb.length > 16 * 1024) throw IOException("response too long")
            }
            // For errors the core sends a short body; read what is immediately available.
            if (!sb.startsWith("HTTP/1.1 101")) {
                val avail = input.available().coerceAtMost(4096)
                if (avail > 0) {
                    val buf = ByteArray(avail)
                    val n = input.read(buf)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
            }
            return sb.toString()
        }
    }
}

/** The desktop to talk to: the one found most recently, or the remembered one. */
fun currentDesktop(ctx: Context): Peer? = Bus.desktop.value ?: Prefs.desktop(ctx)

/** Splits Annex-B data into NAL units without start codes. */
fun splitNals(d: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var i = 0
    var start = -1
    while (i + 3 <= d.size) {
        if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
            if (start >= 0) {
                var end = i
                if (end > start && d[end - 1].toInt() == 0) end--
                if (end > start) out += d.copyOfRange(start, end)
            }
            i += 3
            start = i
        } else {
            i++
        }
    }
    if (start in 0 until d.size) out += d.copyOfRange(start, d.size)
    return out
}
