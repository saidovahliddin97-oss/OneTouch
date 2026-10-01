package app.onetouch

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Opened by the «Файл с телефона» button in the Mac's mirror window: the
 * system file picker appears on the phone (and so in the mirror window),
 * and the chosen files go to the Mac.
 */
class PickActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val jobs = uris.mapNotNull { uri ->
            runCatching {
                val (name, size) = Media.nameAndSize(this, uri)
                val stream = contentResolver.openInputStream(uri) ?: return@runCatching null
                SendJob(name, size, null) { stream }
            }.getOrNull()
        }
        if (jobs.isNotEmpty()) {
            OneTouchService.send(this, jobs)
            Toast.makeText(this, "Отправляю на ${currentDesktop(this)?.name ?: "Mac"}…", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pick.launch(arrayOf("*/*"))
    }
}
