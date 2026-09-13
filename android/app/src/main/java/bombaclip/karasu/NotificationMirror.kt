package bombaclip.karasu

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Mirrors all phone notifications to the PC: each posted notification is
 *  POSTed to the bombaclip server, which shows it via notify-send. Fire-and-
 *  forget, no state. Skipped only when the "mirror_notifs" pref is off. */
class NotificationMirror : NotificationListenerService() {

    private val prefs by lazy { getSharedPreferences("bombaclip", MODE_PRIVATE) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.getBoolean("mirror_notifs", false)) return
        val pkg = sbn.packageName
        if (pkg == packageName || pkg == "com.android.systemui") return
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = pickText(sbn, extras)
        if (title.isBlank() && text.isBlank()) return
        val base = prefs.getString("host", "").orEmpty()
        if (base.isBlank()) return
        val pkg0 = pkg
        pickAppName(pkg0).let { appName ->
            Thread {
                val j = JSONObject()
                    .put("app", appName)
                    .put("title", title)
                    .put("text", text)
                    .put("icon", iconPng(pkg0))
                Api.postNotification(base, j)
            }.start()
        }
    }

    private fun pickText(sbn: StatusBarNotification, extras: Bundle): String {
        extras.getString(Notification.EXTRA_TEXT)?.let {
            if (it.isNotBlank()) return it
        }
        extras.getString(Notification.EXTRA_BIG_TEXT)?.let {
            if (it.isNotBlank()) return it
        }
        return sbn.notification.tickerText?.toString().orEmpty()
    }

    private fun pickAppName(pkg: String): String {
        val app = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        return app?.toString() ?: pkg
    }

    private fun iconPng(pkg: String): String = try {
        val size = 30
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }
}
