package dev.bombaclip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Synchronizes clipboard both ways:
 *  - PC -> phone: poll the server, mirror changes into the phone clipboard.
 *  - phone -> PC: OnPrimaryClipChangedListener POSTs new phone copies to the
 *    server (which runs wl-copy). The listener ignores writes we just made
 *    (lastPhoneHash) so copies never loop back. */
class ClipboardSyncService : Service() {

    @Volatile private var running = false
    @Volatile private var base = ""
    private var lastHash = ""
    private var lastPhoneHash = ""
    private var thread: Thread? = null

    private val prefs by lazy { getSharedPreferences("bombaclip", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        fun start(context: Context, base: String) {
            ContextCompat.startForegroundService(context,
                Intent(context, ClipboardSyncService::class.java)
                    .putExtra("base", base))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardSyncService::class.java))
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onPhoneClipChanged()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NOTIFICATION_SERVICE).let {
            it as NotificationManager
            it.createNotificationChannel(NotificationChannel("sync", "bombaclip sync",
                NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        base = intent?.getStringExtra("base")
            ?: prefs.getString("host", "")
            ?: ""
        val savedToken = prefs.getString("token", "") ?: ""
        if (savedToken.isNotEmpty()) Api.token = savedToken
        if (base.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putString("host", base).apply()
        // Reuse the persisted hash so a restart never re-copies content we
        // already pushed (no copy storm when toggling sync on and off).
        lastHash = if (prefs.getString("syncedHost", "") == base)
            prefs.getString("lastHash", "") ?: "" else ""

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif())
        }
        // Enable auto-restart after the OS kills us in the background.
        startWatching()
        return START_STICKY
    }

    private fun notif() = Notification.Builder(this, "sync")
        .setContentTitle("bombaclip")
        .setContentText("syncing with $base")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()

    /** Hooks the phone clipboard listener (main thread only). */
    private fun startWatching() {
        running = true
        mainHandler.post {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.addPrimaryClipChangedListener(clipListener)
        }
        if (thread?.isAlive != true) {
            thread = Thread { loop() }.apply { start() }
        }
    }

    private fun loop() {
        while (running) {
            pollOnce()
            try {
                Thread.sleep(1500)
            } catch (e: InterruptedException) {
                break // stop requested via onDestroy
            }
        }
    }

    private fun pollOnce() {
        try {
            val (state, ok) = Api.poll(base, lastHash)
            if (ok && state != null) {
                lastHash = state.hash
                applyToClipboard(state)
                prefs.edit().putString("lastHash", lastHash)
                    .putString("syncedHost", base)
                    .commit()
            }
        } catch (e: Exception) {
            android.util.Log.w("bombaclip", "poll error", e)
        }
    }

    private fun applyToClipboard(s: ClipState) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        lastPhoneHash = s.hash
        if (s.kind == "image") {
            val ct = s.ct.ifBlank { "image/png" }
            val ext = when {
                ct.contains("jpeg") -> "jpg"
                ct.contains("png") -> "png"
                ct.contains("webp") -> "webp"
                ct.contains("gif") -> "gif"
                else -> "img"
            }
            val file = File(cacheDir, "clip.$lastHash.$ext")
            file.writeBytes(Api.image(base, s.hash))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val description = android.content.ClipDescription("bombaclip", arrayOf(ct))
            cm.setPrimaryClip(ClipData(description, ClipData.Item(uri)))
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("bombaclip", s.text))
        }
    }

    /** A phone copy happened. If it's not one we just wrote to the clipboard,
     *  push it to the PC. Set on main thread to avoid re-entrancy issues. */
    private fun onPhoneClipChanged() {
        if (!running) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null) {
            val text = ShizukuClipboard.read() ?: return
            val h = sha1(text.toByteArray())
            if (h == lastPhoneHash) return
            lastPhoneHash = h
            Thread { Api.postText(base, text) }.start()
            return
        }
        try {
            val desc = clip.description
            if (desc != null && desc.hasMimeType("image/png")) {
                val uri = clip.getItemAt(0).uri ?: return
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return
                val h = sha1(bytes)
                if (h == lastPhoneHash) return // our own write from the poll
                lastPhoneHash = h
                Thread {
                    Api.postImage(base, bytes)
                }.start()
            } else {
                val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
                val h = sha1(text.toByteArray())
                if (h == lastPhoneHash) return // our own write from the poll
                lastPhoneHash = h
                Thread {
                    Api.postText(base, text)
                }.start()
            }
        } catch (e: Exception) {
            // ignore clipboard read races
        }
    }

    private fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1")
            .digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        mainHandler.post {
            getSystemService(CLIPBOARD_SERVICE).let {
                (it as ClipboardManager).removePrimaryClipChangedListener(clipListener)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}