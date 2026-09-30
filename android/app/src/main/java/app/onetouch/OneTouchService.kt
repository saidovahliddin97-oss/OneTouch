package app.onetouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Long-lived, mostly idle service:
 *  - uploads queued files (pinch, share sheet, tile) to the desktop;
 *  - listens for offers from the desktop and shows a «Получить» notification;
 *  - keeps this phone announced over mDNS.
 * While idle it holds no locks and does no polling: one thread sits in accept().
 */
class OneTouchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var discovery: Discovery
    private val server = OfferServer(::onOffer)
    private val offers = ConcurrentHashMap<String, Offer>()
    private val findLock = Mutex()
    private lateinit var nm: NotificationManager
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        nm = getSystemService(NotificationManager::class.java)
        createChannels(this)
        goForeground()
        discovery = Discovery(this)
        runCatching { server.start() }
        announce()
        watchNetwork()
        scope.launch { for (job in Bus.jobs) upload(job) }
        scope.launch { ensureDesktop(force = false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_REFRESH -> scope.launch { ensureDesktop(force = true) }
            ACTION_ACCEPT -> intent.getStringExtra(EXTRA_OFFER)?.let { id -> scope.launch { accept(id) } }
            ACTION_DECLINE -> intent.getStringExtra(EXTRA_OFFER)?.let { id ->
                offers.remove(id)
                nm.cancel(offerNotifId(id))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        discovery.unregister()
        server.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun announce() {
        if (server.port > 0) discovery.register(server.port, Prefs.deviceId(this), Prefs.deviceName(this))
    }

    /** Re-announce and re-check the desktop when Wi-Fi (re)connects. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { announce(); ensureDesktop(force = true) }
            }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
    }

    // ---- desktop lookup ----

    private suspend fun ensureDesktop(force: Boolean): Peer? = findLock.withLock {
        if (!force) Bus.desktop.value?.let { if (Net.alive(it)) return it }
        Bus.searching.value = true
        try {
            val cached = Prefs.desktop(this)
            if (cached != null && Net.alive(cached)) return setDesktop(cached)
            val found = discovery.findDesktops(Prefs.deviceId(this), 4000, cached?.id)
            setDesktop(found.firstOrNull { it.id == cached?.id } ?: found.firstOrNull())
        } finally {
            Bus.searching.value = false
        }
    }

    private fun setDesktop(p: Peer?): Peer? {
        Bus.desktop.value = p
        if (p != null) Prefs.saveDesktop(this, p)
        nm.notify(NOTIF_STATUS, statusNotification())
        return p
    }

    // ---- phone → desktop ----

    private suspend fun upload(job: SendJob) {
        val peer = ensureDesktop(force = false)
        if (peer == null) {
            Bus.toasts.tryEmit("Mac не найден. Запущен ли OneTouch на Mac и одна ли Wi‑Fi сеть?")
            return
        }
        Bus.sending.value = job.uri
        val nid = NOTIF_PROGRESS
        try {
            Net.upload(peer, job, Prefs.deviceName(this)) { done, total ->
                if (total > 4L * 1024 * 1024) nm.notify(nid, progressNotification("${job.name} → ${peer.name}", done, total))
            }
            job.uri?.let { u -> Bus.sent.value = Bus.sent.value + u }
            Bus.toasts.tryEmit("✓ ${job.name} → ${peer.name}")
        } catch (e: Exception) {
            Bus.toasts.tryEmit("Не отправилось: ${e.message}")
            Bus.desktop.value = null // force a fresh lookup next time
        } finally {
            Bus.sending.value = null
            nm.cancel(nid)
        }
    }

    // ---- desktop → phone ----

    private fun onOffer(o: Offer) {
        val known = Prefs.desktop(this)
        if (known != null && known.id == o.fromId && !known.fp.equals(o.fp, ignoreCase = true)) return // spoofed
        offers[o.id] = o
        val what = if (o.files.size == 1) o.files[0].name else "${o.files.size} файлов"
        val n = Notification.Builder(this, CH_OFFERS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("${o.from}: $what")
            .setContentText("${humanBytes(o.totalSize)} · нажмите «Получить»")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(15 * 60 * 1000L)
            .setContentIntent(servicePending(ACTION_ACCEPT, o.id, 1))
            .addAction(Notification.Action.Builder(null, "Получить", servicePending(ACTION_ACCEPT, o.id, 1)).build())
            .addAction(Notification.Action.Builder(null, "Нет", servicePending(ACTION_DECLINE, o.id, 2)).build())
            .build()
        nm.notify(offerNotifId(o.id), n)
    }

    private fun accept(id: String) {
        val o = offers.remove(id) ?: return
        nm.cancel(offerNotifId(id))
        var last: android.net.Uri? = null
        try {
            o.files.forEachIndexed { i, f ->
                val target = Media.createTarget(this, f.name)
                try {
                    contentResolver.openOutputStream(target)!!.use { out ->
                        Net.download(o, i, out) { done, total ->
                            nm.notify(NOTIF_PROGRESS, progressNotification("${f.name} ← ${o.from}", done, total))
                        }
                    }
                    Media.publish(this, target)
                    last = target
                } catch (e: Exception) {
                    runCatching { contentResolver.delete(target, null, null) }
                    throw e
                }
            }
            // The sender proved its identity via the pinned download: remember it.
            if (o.fromId.isNotEmpty()) setDesktop(Peer(o.fromId, o.from, "", o.fp, o.host, o.port))
            nm.cancel(NOTIF_PROGRESS)
            val what = if (o.files.size == 1) o.files[0].name else "${o.files.size} файлов"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(last, Media.mimeOf(this, last!!))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            nm.notify(
                NOTIF_DONE,
                Notification.Builder(this, CH_DONE)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("✓ Получено: $what")
                    .setContentText("Галерея → альбом OneTouch (или Загрузки/OneTouch)")
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 3, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .build(),
            )
        } catch (e: Exception) {
            nm.cancel(NOTIF_PROGRESS)
            Bus.toasts.tryEmit("Не удалось получить: ${e.message}")
        }
    }

    // ---- notifications ----

    private fun goForeground() {
        startForeground(NOTIF_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun statusNotification(): Notification {
        val d = Bus.desktop.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (d != null) "Подключено: ${d.name}" else "OneTouch готов")
            .setContentText(if (d != null) "Щипок по фото — и оно на Mac" else "Ищу Mac в этой Wi‑Fi сети")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun progressNotification(title: String, done: Long, total: Long): Notification =
        Notification.Builder(this, CH_TRANSFER)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText("${humanBytes(done)} из ${humanBytes(total)}")
            .setProgress(100, if (total > 0) (100 * done / total).toInt() else 0, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun servicePending(action: String, offerId: String, code: Int): PendingIntent {
        val i = Intent(this, OneTouchService::class.java).setAction(action).putExtra(EXTRA_OFFER, offerId)
        return PendingIntent.getForegroundService(this, offerId.hashCode() * 4 + code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun offerNotifId(id: String) = 1000 + (id.hashCode() and 0xffff)

    companion object {
        const val ACTION_REFRESH = "app.onetouch.REFRESH"
        const val ACTION_ACCEPT = "app.onetouch.ACCEPT"
        const val ACTION_DECLINE = "app.onetouch.DECLINE"
        const val EXTRA_OFFER = "offer"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_PROGRESS = 2
        private const val NOTIF_DONE = 3
        private const val CH_STATUS = "status"
        private const val CH_OFFERS = "offers"
        private const val CH_TRANSFER = "transfer"
        private const val CH_DONE = "done"

        @Volatile var running = false

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CH_STATUS, "Статус подключения", NotificationManager.IMPORTANCE_MIN),
                    NotificationChannel(CH_OFFERS, "Файлы с компьютера", NotificationManager.IMPORTANCE_HIGH),
                    NotificationChannel(CH_TRANSFER, "Передача", NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel(CH_DONE, "Готово", NotificationManager.IMPORTANCE_DEFAULT),
                ),
            )
        }

        fun start(ctx: Context) = launch(ctx, Intent(ctx, OneTouchService::class.java))

        fun refresh(ctx: Context) = launch(ctx, Intent(ctx, OneTouchService::class.java).setAction(ACTION_REFRESH))

        /** Queues files and makes sure the service is up to send them. */
        fun send(ctx: Context, jobs: List<SendJob>) {
            jobs.forEach { Bus.jobs.trySend(it) }
            if (!running) start(ctx)
        }

        private fun launch(ctx: Context, i: Intent): Boolean = try {
            if (running) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
            true
        } catch (e: Exception) {
            false // background start not allowed right now; the UI will start us
        }
    }
}
