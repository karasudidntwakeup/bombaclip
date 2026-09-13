package bombaclip.karasu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ClipState(
    val kind: String,
    val hash: String,
    val text: String = "",
    val ct: String = "text/plain",
)

object Api {
    @Volatile var token = ""

    private fun conn(host: String, path: String): HttpURLConnection {
        val c = URL("${host.trimEnd('/')}$path").openConnection() as HttpURLConnection
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun <T> with(host: String, path: String, block: (HttpURLConnection) -> T): T {
        val c = conn(host, path)
        c.connectTimeout = 3000
        c.readTimeout = 4000
        return block(c)
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

    fun image(host: String, hash: String): ByteArray = with(host, "/image?h=$hash") {
        it.inputStream.use { b -> b.readBytes() }
    }

    fun postText(host: String, text: String): Boolean = with(host, "/clipboard") {
        it.requestMethod = "POST"
        it.doOutput = true
        it.setRequestProperty("Content-Type", "text/plain")
        it.outputStream.use { o -> o.write(text.toByteArray()) }
        it.responseCode in 200..299
    }

    fun postImage(host: String, bytes: ByteArray, mime: String = "image/png"): Boolean =
        with(host, "/clipboard") {
            it.requestMethod = "POST"
            it.doOutput = true
            it.setRequestProperty("Content-Type", mime)
            it.outputStream.use { o -> o.write(bytes) }
            it.responseCode in 200..299
        }

    fun postNotification(host: String, j: JSONObject): Boolean = with(host, "/notification") {
        it.requestMethod = "POST"
        it.doOutput = true
        it.setRequestProperty("Content-Type", "application/json")
        it.outputStream.use { o -> o.write(j.toString().toByteArray()) }
        it.responseCode in 200..299
    }
}