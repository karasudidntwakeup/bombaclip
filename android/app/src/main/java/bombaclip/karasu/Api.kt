package bombaclip.karasu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

data class ClipState(
    val kind: String,
    val hash: String,
    val text: String = "",
    val ct: String = "text/plain",
)

object Api {
    @Volatile var token = ""

    private const val CONNECT_TIMEOUT = 3000
    private const val READ_TIMEOUT = 5000

    /** Single shared pool for fire-and-forget posts: caps thread churn when a
     *  burst of clipboard/notification events arrives at once. */
    private val io: ExecutorService = Executors.newFixedThreadPool(2,
        object : ThreadFactory {
            private val n = AtomicInteger()
            override fun newThread(r: Runnable) =
                Thread(r, "bombaclip-io-${n.incrementAndGet()}").apply {
                    isDaemon = true
                }
        })

    fun postAsync(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    private fun conn(host: String, path: String): HttpURLConnection {
        val c = URL("${host.trimEnd('/')}$path").openConnection() as HttpURLConnection
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = CONNECT_TIMEOUT
        c.readTimeout = READ_TIMEOUT
        return c
    }

    /** Always disconnect: the server answers `Connection: close`, so without
     *  this every poll leaked a socket in CLOSE_WAIT until GC. */
    private fun <T> with(host: String, path: String, block: (HttpURLConnection) -> T): T {
        val c = conn(host, path)
        try {
            return block(c)
        } finally {
            try {
                // Drain errors so the pool can reuse the socket when possible.
                if (c.responseCode >= 400) c.errorStream?.close()
            } catch (_: Exception) {
            }
            c.disconnect()
        }
    }

    private fun postBytes(host: String, body: ByteArray, ct: String): Boolean =
        with(host, "/clipboard") {
            it.requestMethod = "POST"
            it.doOutput = true
            it.setRequestProperty("Content-Type", ct)
            it.setFixedLengthStreamingMode(body.size)
            it.outputStream.use { o -> o.write(body) }
            it.responseCode in 200..299
        }

    /** Polls /clipboard?since=. Returns (newState or null if unchanged, httpOk). */
    fun poll(host: String, since: String): Pair<ClipState?, Boolean> = with(host, "/clipboard?since=$since") {
        when (it.responseCode) {
            204 -> null to true
            200 -> {
                val j = JSONObject(it.inputStream.bufferedReader().readText())
                ClipState(j.getString("kind"), j.getString("h"),
                    j.optString("text", ""), j.optString("ct", "text/plain")) to true
            }
            else -> null to false
        }
    }

    fun image(host: String): ByteArray = with(host, "/image") {
        it.inputStream.use { b -> b.readBytes() }
    }

    fun postText(host: String, text: String): Boolean =
        postBytes(host, text.toByteArray(), "text/plain; charset=utf-8")

    fun postImage(host: String, bytes: ByteArray, mime: String = "image/png"): Boolean =
        postBytes(host, bytes, mime)

    fun postNotification(host: String, j: JSONObject): Boolean =
        with(host, "/notification") {
            val body = j.toString().toByteArray()
            it.requestMethod = "POST"
            it.doOutput = true
            it.setRequestProperty("Content-Type", "application/json")
            it.setFixedLengthStreamingMode(body.size)
            it.outputStream.use { o -> o.write(body) }
            it.responseCode in 200..299
        }
}
