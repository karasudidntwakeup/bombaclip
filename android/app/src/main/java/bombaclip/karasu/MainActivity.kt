package bombaclip.karasu

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var serverInput: TextInputEditText
    private lateinit var tokenInput: TextInputEditText
    private lateinit var statusDot: ImageView
    private lateinit var statusHeadline: TextView
    private lateinit var statusSub: TextView
    private lateinit var startStop: Button
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuAction: Button
    private lateinit var notifStatus: TextView

    private val prefs by lazy { getSharedPreferences("bombaclip", MODE_PRIVATE) }
    private var shizukuListenerRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverInput = findViewById(R.id.server)
        tokenInput = findViewById(R.id.token)
        statusDot = findViewById(R.id.status_dot)
        statusHeadline = findViewById(R.id.status_headline)
        statusSub = findViewById(R.id.status_sub)
        startStop = findViewById(R.id.start_stop)
        shizukuStatus = findViewById(R.id.shizuku_status)
        shizukuAction = findViewById(R.id.shizuku_action)
        notifStatus = findViewById(R.id.notif_status)

        serverInput.setText(prefs.getString("host", getString(R.string.server_hint)))
        tokenInput.setText(prefs.getString("token", ""))
        statusSub.ellipsize = TextUtils.TruncateAt.MIDDLE
        statusSub.isSingleLine = true

        findViewById<TextView>(R.id.version_footer).text =
            getString(R.string.version_footer, appVersion())

        val mirrorSwitch = findViewById<MaterialSwitch>(R.id.mirror_switch)
        mirrorSwitch.isChecked = prefs.getBoolean("mirror_notifs", false)
        mirrorSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("mirror_notifs", on).apply()
            if (on && !hasNotificationAccess()) openNotificationSettings()
        }

        findViewById<Button>(R.id.grant_access).setOnClickListener {
            openNotificationSettings()
        }
        shizukuAction.setOnClickListener { requestShizukuPermission() }
        startStop.setOnClickListener {
            if (prefs.getBoolean("syncing", false)) stopSync() else startSync()
        }
        findViewById<Button>(R.id.test).setOnClickListener { testConnection() }

        observeShizukuBinder()
        refreshShizukuUi()

        // Resume a previous session: host + syncing flag persisted.
        val host = prefs.getString("host", "").orEmpty().trim()
        if (prefs.getBoolean("syncing", false) && host.startsWith("http")) {
            Api.token = prefs.getString("token", "").orEmpty()
            ClipboardSyncService.start(this, host)
            renderSyncing(host)
        } else {
            prefs.edit().putBoolean("syncing", false).apply()
            renderIdle(if (host.isEmpty()) "enter your PC address below" else host)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuUi()
        refreshNotifUi()
    }

    // ---------------------------------------------------------- sync control

    private fun startSync() {
        val host = serverInput.text.toString().trim()
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            serverInput.error = "must start with http:// or https://"
            return
        }
        val token = tokenInput.text.toString().trim()
        prefs.edit().putString("host", host).putString("token", token)
            .putBoolean("syncing", true).apply()
        Api.token = token
        ClipboardSyncService.start(this, host)
        renderSyncing(host)
        toast("syncing with $host")
    }

    private fun stopSync() {
        ClipboardSyncService.stop(this)
        prefs.edit().putBoolean("syncing", false).apply()
        renderIdle(prefs.getString("host", "").orEmpty())
        toast("sync stopped")
    }

    private fun testConnection() {
        val host = serverInput.text.toString().trim()
        if (!host.startsWith("http")) {
            serverInput.error = "must start with http:// or https://"
            return
        }
        Api.token = tokenInput.text.toString().trim()
        renderBusy("Testing…", host)
        Thread {
            val ok = try {
                Api.poll(host, "").second
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    setStatus(R.color.status_ok, "Server reachable", host)
                    toast("server answered")
                } else {
                    setStatus(R.color.status_bad, "No answer",
                        "check the address, port and token")
                    toast("server did not answer")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------- status render

    private fun renderIdle(sub: String) {
        setStatus(R.color.status_idle, "Sync off",
            sub.ifBlank { "enter your PC address below" })
        startStop.text = "Start sync"
    }

    private fun renderSyncing(host: String) {
        setStatus(R.color.status_ok, "Syncing", host)
        startStop.text = "Stop sync"
    }

    private fun renderBusy(headline: String, sub: String) {
        setStatus(R.color.status_warn, headline, sub)
    }

    private fun setStatus(colorRes: Int, headline: String, sub: String) {
        statusDot.setColorFilter(ContextCompat.getColor(this, colorRes))
        statusHeadline.text = headline
        statusSub.text = sub
    }

    // ---------------------------------------------------------- shizuku

    private fun observeShizukuBinder() {
        Shizuku.addBinderReceivedListenerSticky {
            runOnUiThread { refreshShizukuUi() }
        }
        Shizuku.addBinderDeadListener {
            runOnUiThread {
                shizukuStatus.text = "Shizuku not running"
                shizukuAction.isEnabled = true
            }
        }
    }

    private fun refreshShizukuUi() {
        if (!Shizuku.pingBinder()) {
            shizukuStatus.text = "Shizuku not running — foreground sync only"
            shizukuAction.isEnabled = false
            return
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        if (granted) {
            shizukuStatus.text = "ready for background sync"
            shizukuAction.text = "Granted"
            shizukuAction.isEnabled = false
        } else {
            shizukuStatus.text = "permission needed for background sync"
            shizukuAction.text = "Authorize"
            shizukuAction.isEnabled = true
        }
    }

    private fun requestShizukuPermission() {
        // Shizuku drops requests with no result listener, so register one
        // exactly once; it unregisters itself after a grant.
        if (!shizukuListenerRegistered) {
            shizukuListenerRegistered = true
            Shizuku.addRequestPermissionResultListener(object :
                Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(code: Int, result: Int) {
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        shizukuListenerRegistered = false
                    }
                    runOnUiThread { refreshShizukuUi() }
                }
            })
        }
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("allow bombaclip in the Shizuku app first")
                return
            }
            Shizuku.requestPermission(1)
        } catch (_: IllegalStateException) {
            // Binder not ready yet; the sticky listener retries on arrival.
            toast("waiting for Shizuku…")
        } catch (_: Exception) {
            toast("Shizuku request failed")
        }
    }

    // ---------------------------------------------------------- notifications

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrEmpty()) return false
        val me = ComponentName(this, NotificationMirror::class.java)
        return flat.split(":").any {
            try {
                ComponentName.unflattenFromString(it) == me
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun refreshNotifUi() {
        notifStatus.text =
            if (hasNotificationAccess()) "allowed" else "needed for notification mirror"
        // Mirror without access is a silent no-op; nudge instead of nagging.
        if (findViewById<MaterialSwitch>(R.id.mirror_switch).isChecked && !hasNotificationAccess()) {
            notifStatus.text = "mirror is on but access is off — tap Grant"
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(this@MainActivity, NotificationMirror::class.java)
                            .flattenToString())
                })
            } else {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                toast("open Settings → Notification access")
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun appVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName,
                PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
