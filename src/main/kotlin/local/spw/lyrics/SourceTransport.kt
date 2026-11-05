package local.spw.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Recovery for community sources only; platform batching keeps its existing transport. */
object SourceTransport {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val nextRequest = ConcurrentHashMap<String, Long>()
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val workingRoutes = ConcurrentHashMap<String, Pair<Long, Proxy>>()
    private var windowsProxy: Pair<Long, String?> = 0L to null
    fun uri(base: String, query: Array<out Pair<String, String>>) = URI.create(base + if (query.isEmpty()) "" else
        "?" + query.joinToString("&") { URLEncoder.encode(it.first, Charsets.UTF_8) + "=" + URLEncoder.encode(it.second, Charsets.UTF_8) })
    fun json(base: String, query: Array<out Pair<String, String>>): JsonElement = Json.parseToJsonElement(String(bytes(uri(base, query)), Charsets.UTF_8))
    fun proxy(value: String?): Proxy? = runCatching {
        if (value.isNullOrBlank()) return null
        val parsed = URI.create(if ("://" in value) value else "http://$value")
        require(parsed.scheme in setOf("http", "https", "socks", "socks5", "socks5h"))
        val port = if (parsed.port == -1) if (parsed.scheme.startsWith("socks")) 1080 else if (parsed.scheme == "https") 443 else 80 else parsed.port
        require(parsed.host != null && port in 1..65535)
        Proxy(if (parsed.scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(parsed.host, port))
    }.getOrNull()
    private fun bypass(host: String): Boolean = (System.getenv("NO_PROXY") ?: System.getenv("no_proxy")).orEmpty().split(',').any {
        val name = it.trim().substringBefore(':').removePrefix("*.").removePrefix(".")
        name == "*" || name.isNotEmpty() && (host.equals(name, true) || host.endsWith(".$name", true))
    }
    @Synchronized private fun windows(): String? {
        if (System.currentTimeMillis() - windowsProxy.first < 30000) return windowsProxy.second
        val value = runCatching {
            if (!System.getProperty("os.name").startsWith("Windows", true)) return@runCatching null
            val root = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
            val path = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            if (com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(root, path, "ProxyEnable") != 1) null
            else com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(root, path, "ProxyServer")
        }.getOrNull()
        windowsProxy = System.currentTimeMillis() to value; return value
    }
    fun routes(uri: URI): List<Proxy> {
        if (uri.host in setOf("localhost", "127.0.0.1", "::1") || bypass(uri.host)) return listOf(Proxy.NO_PROXY)
        val environment = (if (uri.scheme == "https") listOf("HTTPS_PROXY", "https_proxy") else listOf("HTTP_PROXY", "http_proxy")) + listOf("ALL_PROXY", "all_proxy")
        val configured = environment.firstNotNullOfOrNull { proxy(System.getenv(it)) }
        val setting = windows()?.let { server ->
            if ('=' !in server) server else server.split(';').firstOrNull { it.startsWith("${uri.scheme}=", true) }?.substringAfter('=')
        }?.let(::proxy)
        val selected = runCatching { ProxySelector.getDefault()?.select(uri).orEmpty().filter { it.type() != Proxy.Type.DIRECT } }.getOrDefault(emptyList())
        val candidates = (listOf(Proxy.NO_PROXY) + selected + listOfNotNull(setting, configured)).distinct()
        val working = workingRoutes[uri.host]?.takeIf { System.currentTimeMillis() - it.first < 600000 && it.second in candidates }?.second
        return if (working == null) candidates else (listOf(working) + candidates).distinct()
    }
    fun retryDelay(header: String?, now: Long = System.currentTimeMillis()): Long =
        header?.toLongOrNull()?.coerceIn(0, 86400)?.times(1000) ?: runCatching {
            (java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 86400000)
        }.getOrDefault(1000)
    fun bytes(uri: URI, limit: Int = 4_194_304, routeOptions: List<Proxy> = routes(uri)): ByteArray {
        val lock = locks.computeIfAbsent(uri.host) { ReentrantLock() }
        lock.lockInterruptibly()
        try {
            val available = cooldowns[uri.host] ?: 0
            if (available > System.currentTimeMillis()) error("${uri.host} 正在限流，请在 ${(available - System.currentTimeMillis() + 999) / 1000} 秒后重试")
            val routes = routeOptions.also { require(it.isNotEmpty()) }
            var route = 0
            val attempts = maxOf(2, routes.size)
            repeat(attempts) { attempt ->
                val wait = (nextRequest[uri.host] ?: 0) - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                try {
                    return Network.bytes(uri, limit, "application/json, text/plain, application/xml", proxy = routes[route], deadlineMillis = 20000).also {
                        workingRoutes[uri.host] = System.currentTimeMillis() to routes[route]
                    }
                } catch (cancel: InterruptedException) { throw cancel }
                catch (failure: HttpFailure) {
                    if (failure.status !in setOf(429, 502, 503, 504)) throw failure
                    val delay = retryDelay(failure.retryAfter)
                    val deadline = System.currentTimeMillis() + delay
                    nextRequest.compute(uri.host) { _, previous -> maxOf(previous ?: 0, deadline) }
                    if (failure.status == 429 || delay > 2000) cooldowns[uri.host] = deadline
                    if (attempt >= 1 || delay > 2000) throw IllegalStateException("${uri.host} 暂时繁忙（HTTP ${failure.status}），请在 ${(delay + 999) / 1000} 秒后重试", failure)
                    Thread.sleep(delay)
                } catch (failure: java.io.IOException) {
                    if (attempt == attempts - 1) throw IllegalStateException("${uri.host} 连接失败：${when (failure) { is javax.net.ssl.SSLException -> "TLS 校验或握手失败"; is SocketTimeoutException -> "请求超时"; is UnknownHostException -> "域名解析失败"; else -> "请检查网络或系统代理" }}", failure)
                    if (route + 1 < routes.size) route++ else Thread.sleep(250)
                } finally { nextRequest.compute(uri.host) { _, previous -> maxOf(previous ?: 0, System.currentTimeMillis() + if (uri.host == "lrclib.net") 250 else 0) } }
            }
            error("歌词服务暂不可用")
        } finally { lock.unlock() }
    }
}
