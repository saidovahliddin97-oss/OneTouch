package app.onetouch

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS client for desktop nodes. Desktops use self-signed certificates, so
 * instead of a CA chain we pin the SHA-256 fingerprint announced over mDNS.
 */
object Net {
    private val factories = HashMap<String, SSLSocketFactory>()

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun factory(fp: String): SSLSocketFactory = factories.getOrPut(fp) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("client certs not supported")

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !sha256Hex(chain[0].encoded).equals(fp, ignoreCase = true)) {
                    throw CertificateException("OneTouch: отпечаток сертификата не совпадает")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    fun hostForUrl(host: String): String {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
        return if (addr is Inet6Address) "[" + host.replace("%", "%25") + "]" else host
    }

    private fun open(host: String, port: Int, path: String, fp: String, connectMs: Int, readMs: Int): HttpsURLConnection {
        val c = URL("https://${hostForUrl(host)}:$port$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = factory(fp)
        c.hostnameVerifier = PinnedHostnames // identity is proven by the pinned fingerprint
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        return c
    }

    /** True if [peer] answers and presents the expected certificate. */
    fun alive(peer: Peer, timeoutMs: Int = 1500): Boolean = runCatching {
        val c = open(peer.host, peer.port, "/v1/info", peer.fp, timeoutMs, timeoutMs)
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    fun upload(peer: Peer, job: SendJob, from: String, progress: (Long, Long) -> Unit) {
        val q = "name=" + URLEncoder.encode(job.name, "UTF-8") + "&from=" + URLEncoder.encode(from, "UTF-8")
        val c = open(peer.host, peer.port, "/v1/files?$q", peer.fp, 5000, 60_000)
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            if (job.size >= 0) c.setFixedLengthStreamingMode(job.size) else c.setChunkedStreamingMode(256 * 1024)
            job.open().use { input -> c.outputStream.use { out -> copy(input, out, job.size, progress) } }
            val code = c.responseCode
            if (code != 200) throw IOException("Mac ответил $code")
        } finally {
            c.disconnect()
        }
    }

    /** Streams the n-th file of [offer] into [out]. */
    fun download(offer: Offer, n: Int, out: OutputStream, progress: (Long, Long) -> Unit) {
        val c = open(offer.host, offer.port, "/v1/offers/${offer.id}/$n", offer.fp, 5000, 60_000)
        try {
            val code = c.responseCode
            if (code != 200) throw IOException(if (code == 404) "предложение устарело" else "Mac ответил $code")
            c.inputStream.use { copy(it, out, offer.files[n].size, progress) }
        } finally {
            c.disconnect()
        }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, progress: (Long, Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var last = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - last > 250) { last = now; progress(done, total) }
        }
        progress(done, total)
    }
}

private object PinnedHostnames : javax.net.ssl.HostnameVerifier {
    override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?) = true
}
