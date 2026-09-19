package bombaclip.karasu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.InputStream

/** Appears in the system share sheet to push content straight to the PC. */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bombaclip", MODE_PRIVATE)
        val host = prefs.getString("host", "").orEmpty()
        if (!host.startsWith("http")) {
            toast("configure bombaclip first")
            finish()
            return
        }
        Api.token = prefs.getString("token", "").orEmpty()

        val intent = intent ?: run { finish(); return }
        val type = intent.type?.lowercase() ?: ""
        val sendText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val sendUri = if (type.startsWith("image/")) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
        } else null

        when {
            sendUri != null -> Api.postAsync {
                if (sendImage(host, sendUri, type)) toast("sent to PC")
                else toast("failed to send")
                runOnUiThread { finish() }
            }
            !sendText.isNullOrEmpty() -> Api.postAsync {
                if (runCatching { Api.postText(host, sendText) }.getOrDefault(false)) {
                    toast("sent to PC")
                } else {
                    toast("failed to send")
                }
                runOnUiThread { finish() }
            }
            else -> {
                toast("nothing to share")
                finish()
            }
        }
    }

    private fun sendImage(host: String, uri: Uri, fallbackType: String): Boolean {
        return try {
            val ct = contentResolver.getType(uri)?.lowercase()?.ifBlank { null }
                ?: fallbackType.ifBlank { "image/png" }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes(MAX_BYTES) }
                ?: return false
            Api.postImage(host, bytes, ct)
        } catch (_: Exception) {
            false
        }
    }

    /** Size-capped read. (No available() pre-check: it is only an estimate
     *  and wrongly rejects valid streams on some providers.) */
    private fun InputStream.readBytes(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun toast(s: String) = runOnUiThread {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_BYTES = 100 * 1024 * 1024 // reject >100MB
    }
}
