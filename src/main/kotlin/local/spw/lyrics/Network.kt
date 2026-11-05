package local.spw.lyrics

import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HttpFailure(val status: Int, val retryAfter: String? = null) :
    IllegalArgumentException("服务返回 HTTP $status，请稍后重试")

object Network {
    fun json(base: String, vararg query: Pair<String, String>): JsonElement {
        val suffix = query.joinToString("&") { URLEncoder.encode(it.first, Charsets.UTF_8) + "=" + URLEncoder.encode(it.second, Charsets.UTF_8) }
        return Json.parseToJsonElement(String(bytes(URI.create(base + if (suffix.isEmpty()) "" else "?$suffix"), 4_194_304, "application/json"), Charsets.UTF_8))
    }

    // SPW omits java.net.http; java.base supplies URL connections and virtual threads.
    fun bytes(uri: URI, limit: Int = 20_971_520, accept: String = "image/*", form: String? = null,
        proxy: java.net.Proxy? = null, deadlineMillis: Long = 12000): ByteArray {
        require(uri.scheme in listOf("http", "https") && uri.userInfo == null && !uri.host.isNullOrEmpty()) { "请输入有效的 HTTP(S) 地址" }
        val opened = java.util.concurrent.atomic.AtomicReference<HttpURLConnection>()
        val pending = FutureTask<ByteArray> {
            val connection = (if (proxy == null) uri.toURL().openConnection() else uri.toURL().openConnection(proxy)) as HttpURLConnection
            opened.set(connection)
            var consumed = false
            connection.connectTimeout = 8_000; connection.readTimeout = 12_000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "BetterTagForSPW/0.6.26 (local Salt Player desktop plugin)")
            if (uri.host.endsWith("qq.com") || uri.host.endsWith("gtimg.cn")) connection.setRequestProperty("Referer", "https://y.qq.com/")
            else if (uri.host.endsWith("163.com")) connection.setRequestProperty("Referer", "https://music.163.com/")
            connection.setRequestProperty("Accept", accept)
            try {
                if (form != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                if (status != 200) throw HttpFailure(status, connection.getHeaderField("Retry-After"))
                val stream = connection.inputStream
                val data = try { stream.readNBytes(limit + 1) } finally { stream.close() }
                require(data.size <= limit) { "歌词服务的响应过大" }
                consumed = true
                data
            } finally { if (!consumed) connection.disconnect() }
        }
        Thread.ofVirtual().name("spw-lyrics-request").start(pending)
        try { return pending.get(deadlineMillis, TimeUnit.MILLISECONDS) }
        catch (failure: ExecutionException) { throw failure.cause ?: failure }
        catch (_: TimeoutException) { throw SocketTimeoutException("服务请求超时，请稍后重试") }
        finally { if (!pending.isDone) { pending.cancel(true); opened.get()?.disconnect() } }
    }
}

fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()
