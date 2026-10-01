package app.onetouch

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Shows this phone's screen on the Mac: MediaProjection → hardware H.264
 * encoder (fed through its input Surface, no copies) → pinned-TLS session.
 * Files the Mac drops onto the mirror window arrive as offers and are saved.
 */
class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    @Volatile private var session: Session? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val peer = currentDesktop(this)
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        // Android 14+: the foreground service must exist before the projection is created.
        startForeground(NOTIF, notification(peer?.name ?: "Mac"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (peer == null || data == null || code != Activity.RESULT_OK || projection != null) {
            if (peer == null) Bus.toasts.tryEmit("Mac не найден")
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        Bus.mirroringTo.value = peer.name
        thread(name = "mirror") { run(peer, proj) }
        return START_NOT_STICKY
    }

    private fun run(peer: Peer, proj: MediaProjection) {
        try {
            val s = Session.open(this, peer, "mirror")
            session = s
            val metrics = resources.displayMetrics
            // Long side ≤ 1280: sharp enough on a Mac window, light on battery.
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
            val w = (metrics.widthPixels * scale).toInt() and -2 // even sizes for the encoder
            val h = (metrics.heightPixels * scale).toInt() and -2
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000) // keep the stream alive on a static screen
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = enc.createInputSurface()
            enc.start()
            encoder = enc
            display = proj.createVirtualDisplay("OneTouch", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null)
            s.json(Session.HELLO, JSONObject().put("w", w).put("h", h).put("name", Prefs.deviceName(this)))
            thread(name = "mirror-in") { readCommands(s, peer) }
            val info = MediaCodec.BufferInfo()
            while (!stopped) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = enc.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        s.write(Session.CONFIG, bytes)
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        s.write(Session.FRAME, byteArrayOf(if (key) 1 else 0) + bytes)
                    }
                }
                enc.releaseOutputBuffer(i, false)
            }
        } catch (e: Exception) {
            if (!stopped) Bus.toasts.tryEmit("Трансляция остановлена: ${e.message}")
        } finally {
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    private fun readCommands(s: Session, peer: Peer) {
        try {
            while (!stopped) {
                val (type, p) = s.read()
                when (type) {
                    Session.INPUT -> {
                        val ctl = ControlService.instance
                        if (ctl != null) {
                            Handler(Looper.getMainLooper()).post { ctl.handle(JSONObject(String(p))) }
                        } else {
                            askForControl(s)
                        }
                    }
                    Session.COMMAND -> when (JSONObject(String(p)).optString("cmd")) {
                        "keyframe" -> encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                        "screenshot" -> {
                            val ctl = ControlService.instance
                            if (ctl != null) Handler(Looper.getMainLooper()).post { ctl.screenshotToDesktop() } else askForControl(s)
                        }
                        "pick" -> openPicker()
                    }
                    Session.OFFER -> {
                        val offer = Offer.parse(String(p), peer.host)
                        thread {
                            runCatching { Receiver.receive(this, offer) }
                                .onFailure { Bus.toasts.tryEmit("Не удалось получить: ${it.message}") }
                        }
                    }
                    Session.STATUS -> Bus.toasts.tryEmit(JSONObject(String(p)).optString("text"))
                }
            }
        } catch (_: Exception) {
            if (!stopped) Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    private var askedForControl = false

    /** Control needs the accessibility service; tell the Mac once, and nudge the user here. */
    private fun askForControl(s: Session) {
        if (askedForControl) return
        askedForControl = true
        runCatching {
            s.json(Session.STATUS, JSONObject().put("text",
                "Чтобы управлять телефоном с Mac, включите на телефоне: Настройки → Спец. возможности → OneTouch — управление"))
        }
        Bus.toasts.tryEmit("Включите «OneTouch — управление» в Спец. возможностях")
    }

    /** Shows the system file picker on the phone (visible in the Mac window). */
    private fun openPicker() {
        val i = Intent(this, PickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i) // allowed: our accessibility service / projection makes this a visible-UI app
        } catch (e: Exception) {
            val pi = PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_IMMUTABLE)
            getSystemService(android.app.NotificationManager::class.java).notify(21,
                Notification.Builder(this, OneTouchService.CH_DONE).setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("Выберите файл для Mac").setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    override fun onDestroy() {
        stopped = true
        Bus.mirroringTo.value = null
        session?.close()
        runCatching { display?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { projection?.stop() }
        super.onDestroy()
    }

    private fun notification(to: String): Notification {
        val stop = PendingIntent.getService(this, 7, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, OneTouchService.CH_TRANSFER)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Экран транслируется на $to")
            .setContentText("Перетащите файл в окно на Mac — он придёт сюда")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    companion object {
        private const val NOTIF = 20
        private const val ACTION_STOP = "app.onetouch.MIRROR_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, MirrorService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
