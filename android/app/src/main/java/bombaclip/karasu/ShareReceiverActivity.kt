package bombaclip.karasu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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
        val action = intent.action
        val type = intent.type?.lowercase() ?: ""
        val sendText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val sendUri = if (type.startsWith("image/")) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null

        when {
            sendUri != null -> Thread { sendImage(host, sendUri, type) }.start()
            !sendText.isNullOrEmpty() -> Thread { sendText(host, sendText) }.start()
            else -> {
                toast("nothing to share")
                finish()
            }
        }
    }

    private fun sendImage(host: String, uri: Uri, fallbackType: String) {
        try {
            val ct = contentResolver.getType(uri)?.lowercase()?.ifBlank { null } ?: fallbackType
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes(MAX_BYTES) }
            if (bytes == null) {
                toast("could not read shared file")
                return
            }
            post(host, bytes, ct.ifBlank { "image/png" })
            toast("sent to PC")
        } catch (e: Exception) {
            toast("failed to send")
        } finally {
            finish()
        }
    }

    private fun sendText(host: String, text: String) {
        try {
            post(host, text.toByteArray(), "text/plain")
            toast("sent to PC")
        } catch (e: Exception) {
            toast("failed to send")
        } finally {
            finish()
        }
    }

    private fun post(host: String, body: ByteArray, ct: String) {
        val c = URL("${host.trimEnd('/')}/clipboard").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 4000
            c.doOutput = true
            c.setRequestProperty("Content-Type", ct)
            if (Api.token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer ${Api.token}")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            if (c.responseCode !in 200..299) throw RuntimeException("http ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }

    private fun InputStream.readBytes(max: Int): ByteArray? {
        if (available() > max) return null
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