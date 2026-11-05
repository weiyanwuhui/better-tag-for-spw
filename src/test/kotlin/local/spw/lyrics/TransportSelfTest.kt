package local.spw.lyrics

import kotlinx.serialization.json.*
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

fun checkTransport() {
    var httpModulePresent = false
    try { Class.forName("java.net.http.HttpClient"); httpModulePresent = true } catch (_: ClassNotFoundException) { }
    check(!httpModulePresent) {
        "Run the regression with SPW's module list; a full JDK hides the original crash"
    }
    val sources = LyricSources()
    val method = LyricSources::class.java.getDeclaredMethod("request", String::class.java,
        arrayOf<Pair<String, String>>().javaClass).apply { isAccessible = true }
    fun request(base: String): JsonElement = try {
        method.invoke(sources, base, arrayOf("q" to "晴天 周杰伦")) as JsonElement
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    fun failure(base: String): Throwable? = try { request(base); null } catch (error: Exception) { error }

    fun response(status: Int, body: ByteArray, stall: Boolean = false, verify: (String) -> Unit) {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val handler = FutureTask<Unit> {
            val socket = server.accept()
            try {
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                check(reader.readLine().contains("q=%E6%99%B4%E5%A4%A9+%E5%91%A8%E6%9D%B0%E4%BC%A6"))
                while (!reader.readLine().isNullOrEmpty()) { }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $status Test\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                if (stall) Thread.sleep(30_000) else { output.write(body); output.flush() }
            } finally { socket.close() }
        }
        Thread.ofVirtual().start(handler)
        try {
            verify("http://127.0.0.1:${server.localPort}/lyrics")
            if (!stall) handler.get(3, TimeUnit.SECONDS)
        } finally { server.close(); if (!handler.isDone) handler.cancel(true) }
    }

    response(200, "{\"lyric\":\"晴天\"}".toByteArray()) {
        check(request(it).jsonObject["lyric"]?.jsonPrimitive?.content == "晴天")
    }
    val keepAlive = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    val reusable = FutureTask<Unit> {
        keepAlive.accept().use { socket ->
            socket.soTimeout = 3000
            val input = socket.getInputStream().bufferedReader()
            repeat(2) {
                check(input.readLine().startsWith("GET /reuse"))
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n{}".toByteArray()); flush() }
            }
        }
    }
    Thread.ofVirtual().start(reusable)
    try {
        repeat(2) { check(Network.json("http://127.0.0.1:${keepAlive.localPort}/reuse").toString() == "{}") }
        reusable.get(3, TimeUnit.SECONDS)
    } finally { keepAlive.close(); if (!reusable.isDone) reusable.cancel(true) }
    val postServer = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    val postHandler = FutureTask<Unit> {
        postServer.accept().use { socket ->
            socket.soTimeout = 3000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            check(reader.readLine().startsWith("POST /lyrics "))
            var length = 0
            var contentType = ""
            while (true) {
                val header = reader.readLine()
                if (header.isNullOrEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                if (header.startsWith("Content-Type:", true)) contentType = header.substringAfter(':').trim()
            }
            check(contentType == "application/x-www-form-urlencoded" && length == 14)
            val body = CharArray(length)
            var read = 0
            while (read < length) { val count = reader.read(body, read, length - read); check(count > 0); read += count }
            check(String(body) == "params=ABC1234")
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
        }
    }
    Thread.ofVirtual().start(postHandler)
    try {
        check(String(Network.bytes(java.net.URI.create("http://127.0.0.1:${postServer.localPort}/lyrics"), accept = "application/json", form = "params=ABC1234")) == "{}")
        postHandler.get(3, TimeUnit.SECONDS)
    } finally { postServer.close(); if (!postHandler.isDone) postHandler.cancel(true) }
    for (status in listOf(302, 503)) response(status, byteArrayOf()) {
        val error = failure(it)
        check(error is IllegalArgumentException && error.message.orEmpty().contains("HTTP $status"))
    }
    response(200, ByteArray(4_194_305) { ' '.code.toByte() }) {
        val error = failure(it)
        check(error is IllegalArgumentException && error.message == "歌词服务的响应过大")
    }
    response(200, byteArrayOf('['.code.toByte()), stall = true) {
        val start = System.nanoTime()
        check(failure(it) is SocketTimeoutException)
        check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 20) { "The body read outlived the request deadline" }
    }
    println("PASS: search client loads without java.net.http; UTF-8 HTTP, status/redirect rejection, response limit and stalled-body timeout.")
}
