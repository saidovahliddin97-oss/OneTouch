package app.onetouch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Downloads offered files into the gallery / Downloads with progress notifications. */
object Receiver {
    private const val NOTIF_PROGRESS = 2
    private const val NOTIF_DONE = 3

    /** Blocking; call from a background thread. Returns the last saved file. */
    fun receive(ctx: Context, o: Offer): Uri {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        var last: Uri? = null
        try {
            o.files.forEachIndexed { i, f ->
                val target = Media.createTarget(ctx, f.name)
                try {
                    ctx.contentResolver.openOutputStream(target)!!.use { out ->
                        Net.download(o, i, out) { done, total ->
                            if (total > 2L * 1024 * 1024) nm.notify(NOTIF_PROGRESS, progress(ctx, "${f.name} ← ${o.from}", done, total))
                        }
                    }
                    Media.publish(ctx, target)
                    last = target
                } catch (e: Exception) {
                    runCatching { ctx.contentResolver.delete(target, null, null) }
                    throw e
                }
            }
        } finally {
            nm.cancel(NOTIF_PROGRESS)
        }
        val uri = last!!
        val what = if (o.files.size == 1) o.files[0].name else "${o.files.size} файлов"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Media.mimeOf(ctx, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        nm.notify(
            NOTIF_DONE,
            Notification.Builder(ctx, OneTouchService.CH_DONE)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("✓ Получено: $what")
                .setContentText("Галерея → альбом OneTouch (или Загрузки/OneTouch)")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 3, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build(),
        )
        return uri
    }

    fun progress(ctx: Context, title: String, done: Long, total: Long): Notification =
        Notification.Builder(ctx, OneTouchService.CH_TRANSFER)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText("${humanBytes(done)} из ${humanBytes(total)}")
            .setProgress(100, if (total > 0) (100 * done / total).toInt() else 0, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
}
