package app.onetouch

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom

const val SERVICE_TYPE = "_onetouch._tcp"

/** A desktop OneTouch node (Mac/PC). [fp] is the SHA-256 of its TLS certificate. */
data class Peer(val id: String, val name: String, val os: String, val fp: String, val host: String, val port: Int) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("name", name).put("os", os).put("fp", fp).put("host", host).put("port", port)
        .toString()

    companion object {
        fun fromJson(s: String): Peer? = runCatching {
            val o = JSONObject(s)
            Peer(o.getString("id"), o.getString("name"), o.optString("os"), o.getString("fp"), o.getString("host"), o.getInt("port"))
        }.getOrNull()
    }
}

data class OfferFile(val name: String, val size: Long)

/** Files a desktop offers us; they are pulled from [host]:[port] over TLS pinned to [fp]. */
data class Offer(
    val id: String, val from: String, val fromId: String, val port: Int, val fp: String,
    val host: String, val files: List<OfferFile>,
) {
    val totalSize get() = files.sumOf { it.size }

    companion object {
        fun parse(json: String, host: String): Offer {
            val o = JSONObject(json)
            val arr = o.getJSONArray("files")
            val files = (0 until arr.length()).map {
                val f = arr.getJSONObject(it)
                OfferFile(f.getString("name"), f.optLong("size", -1))
            }
            require(files.isNotEmpty()) { "empty offer" }
            return Offer(o.getString("id"), o.optString("from", "Mac"), o.optString("fromId"), o.getInt("port"), o.getString("fp"), host, files)
        }
    }
}

/** One file to upload. [open] must be callable from a background thread. */
class SendJob(val name: String, val size: Long, val uri: Uri?, val open: () -> InputStream)

/** In-process event bus between the UI, the share sheet and the service. */
object Bus {
    val desktop = MutableStateFlow<Peer?>(null)
    val searching = MutableStateFlow(false)
    val sent = MutableStateFlow<Set<Uri>>(emptySet())
    val sending = MutableStateFlow<Uri?>(null)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val jobs = Channel<SendJob>(Channel.UNLIMITED)
    /** Name of the desktop our screen is being mirrored to, or null. */
    val mirroringTo = MutableStateFlow<String?>(null)
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("onetouch", Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        p(ctx).getString("id", null)?.let { return it }
        val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString("id", id).apply()
        return id
    }

    /** Secret this phone presents when opening screen sessions (remembered by the Mac). */
    fun sessionToken(ctx: Context): String {
        p(ctx).getString("token", null)?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString("token", t).apply()
        return t
    }

    fun deviceName(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun desktop(ctx: Context): Peer? = p(ctx).getString("desktop", null)?.let(Peer::fromJson)
    fun saveDesktop(ctx: Context, peer: Peer) = p(ctx).edit().putString("desktop", peer.toJson()).apply()
}

fun humanBytes(n: Long): String {
    if (n < 0) return "?"
    if (n < 1024) return "$n Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
