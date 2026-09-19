package bombaclip.karasu

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Mirrors phone notifications to the PC: each posted notification is POSTed
 *  to the bombaclip server, which shows it via notify-send. Fire-and-forget,
 *  no state. Skipped when the "mirror_notifs" pref is off. Identical repeats
 *  within a few seconds (progress/ongoing spam) are coalesced. */
class NotificationMirror : NotificationListenerService() {

    private val prefs by lazy { getSharedPreferences("bombaclip", MODE_PRIVATE) }

    /** key -> last sent uptimeMs, guarded by itself. Bounded: drops the oldest. */
    private val recent = LinkedHashMap<String, Long>()
    private val recentLock = Any()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.getBoolean("mirror_notifs", false)) return
        val pkg = sbn.packageName
        if (pkg == packageName || pkg == "com.android.systemui") return
        // Ongoing stuff (media players, foreground services, progress bars)
        // reposts constantly; mirroring it would DDoS notify-send.
        if (sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title: String
        val text: String
        try {
            title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
            text = pickText(sbn, extras)
        } catch (_: Exception) {
            return
        }
        if (title.isBlank() && text.isBlank()) return
        if (!dedup("$pkg|$title|$text")) return
        val base = prefs.getString("host", "").orEmpty()
        if (base.isBlank()) return
        Api.token = prefs.getString("token", "").orEmpty()
        val appName = pickAppName(pkg)
        Api.postAsync {
            val j = JSONObject()
                .put("app", appName)
                .put("title", title)
                .put("text", text)
                .put("icon", iconPng(pkg))
            Api.postNotification(base, j)
        }
    }

    private fun pickText(sbn: StatusBarNotification, extras: Bundle): String {
        extras.getString(Notification.EXTRA_TEXT)?.let {
            if (it.isNotBlank()) return it
        }
        extras.getString(Notification.EXTRA_BIG_TEXT)?.let {
            if (it.isNotBlank()) return it
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return sbn.notification.tickerText?.toString().orEmpty()
    }

    /** True if this exact notification wasn't sent in the last window. */
    private fun dedup(key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(recentLock) {
            recent[key]?.let { if (now - it < DEDUP_MS) return false }
            recent[key] = now
            if (recent.size > DEDUP_CAP) {
                val oldest = recent.keys.firstOrNull()
                if (oldest != null) recent.remove(oldest)
            }
            return true
        }
    }

    private fun pickAppName(pkg: String): String = try {
        val app = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0))
        app?.toString() ?: pkg
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    } catch (_: Exception) {
        pkg
    }

    private fun iconPng(pkg: String): String = try {
        val size = 48
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (!bmp.isRecycled) bmp.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val DEDUP_MS = 4000L
        private const val DEDUP_CAP = 64
    }
}
