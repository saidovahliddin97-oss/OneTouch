package app.onetouch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile: one tap sends the newest photo/video to the Mac. */
class SendTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Фото → Mac"
            subtitle = Bus.desktop.value?.name ?: Prefs.desktop(this@SendTileService)?.name
            updateTile()
        }
    }

    override fun onClick() {
        val latest = runCatching { Media.recent(this, 1).firstOrNull() }.getOrNull()
        if (latest == null || !OneTouchService.running) {
            openApp() // no media permission yet, or the service needs a foreground start
            return
        }
        OneTouchService.send(this, listOf(Media.job(this, latest.uri)))
        Toast.makeText(this, "Отправляю последнее фото…", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // the Intent overload is the only one before API 34
    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
