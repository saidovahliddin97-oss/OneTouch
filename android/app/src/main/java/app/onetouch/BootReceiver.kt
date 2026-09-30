package app.onetouch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the (idle) service back after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            OneTouchService.start(ctx)
        }
    }
}
