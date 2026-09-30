package app.onetouch

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * «Поделиться → На Mac»: no UI. Streams are opened here, while we still hold
 * the sender's temporary read grant, then handed to the service in-process.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val jobs = uris.mapNotNull { uri ->
            runCatching {
                val (name, size) = Media.nameAndSize(this, uri)
                val stream = contentResolver.openInputStream(uri) ?: return@runCatching null
                SendJob(name, size, null) { stream }
            }.getOrNull()
        }
        if (jobs.isEmpty()) {
            Toast.makeText(this, "Нечего отправлять", Toast.LENGTH_SHORT).show()
        } else {
            OneTouchService.send(this, jobs)
            val to = Bus.desktop.value?.name ?: Prefs.desktop(this)?.name ?: "Mac"
            Toast.makeText(this, "Отправляю на $to…", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
}
