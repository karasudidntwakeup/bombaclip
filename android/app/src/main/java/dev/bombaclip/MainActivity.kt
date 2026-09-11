package dev.bombaclip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusView: TextView

    private val prefs by lazy { getSharedPreferences("bombaclip", MODE_PRIVATE) }
    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Shizuku drops permission requests that have no result listener, so
    // register one up front and re-trigger the dialog if it was dismissed.
    private val shizukuRequest = {
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (result != PackageManager.PERMISSION_GRANTED) {
                try {
                    Shizuku.requestPermission(1)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            Shizuku.requestPermission(1)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serverInput = findViewById(R.id.server)
        tokenInput = findViewById(R.id.token)
        statusView = findViewById(R.id.status)

        serverInput.setText(prefs.getString("host", getString(R.string.server_hint)))
        tokenInput.setHint("optional shared secret")
        tokenInput.setText(prefs.getString("token", ""))

        findViewById<Button>(R.id.save).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            saveAndStart()
        }

        requestShizuku()

        // If a host is already configured, sync starts automatically.
        val host = prefs.getString("host", "").orEmpty()
        if (host.isNotEmpty() && !host.startsWith("http")) {
            statusR("server must start with http://")
        } else if (host.isNotEmpty()) {
            Api.token = prefs.getString("token", "").orEmpty()
            ClipboardSyncService.start(this, host)
            statusR("syncing with $host")
        }
    }

    private fun requestShizuku() {
        // The binder arrives asynchronously, so request permission only once
        // we know the connection to the Shizuku server is live. Sticky = fires
        // immediately if the binder is already there.
        Shizuku.addBinderReceivedListenerSticky {
            try {
                Shizuku.checkSelfPermission()
            } catch (_: SecurityException) {
                shizukuRequest()
            }
        }
    }

    private fun saveAndStart() {
        val host = serverInput.text.toString().trim()
        if (!host.startsWith("http")) {
            status("server must start with http:// or https://")
            return
        }
        prefs.edit().putString("host", host)
            .putString("token", tokenInput.text.toString().trim()).apply()
        Api.token = tokenInput.text.toString().trim()
        ClipboardSyncService.start(this, host)
        status("syncing with $host")
    }

    private fun status(s: String) {
        statusView.text = s
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun statusR(s: String) {
        statusView.text = s
    }
}