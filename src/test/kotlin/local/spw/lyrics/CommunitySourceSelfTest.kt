package local.spw.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

private val WORD_YAML = """
version: '1.0'
metadata:
  title: 测试曲
  artist: 测试歌手
lines:
  - text: '你好'
    start_ms: 1200
    end_ms: 2800
    words:
      - text: 你
        start_ms: 1200
        end_ms: 1900
      - text: 好
        start_ms: 1900
        end_ms: 2800
""".trimIndent()
private val CATALOG = """{"metadata":[["musicName",["测试曲","別名"]],["artists",["甲","乙"]],["album",["专辑"]]],"rawLyricFile":"1700000000000-1-abcdef12.ttml"}"""

fun checkCommunitySources() = runBlocking {
    val document = LyricsCodec.parse(WORD_YAML)
    check(document.hasWords && document.kind == "Lyricsfile" && document.rows.single().words.map { it.start } == listOf(1200L, 1900L))
    check(LyricsCodec.parse(LyricsCodec.export(document, LyricsFormat.WORD)).rows.single().words == document.rows.single().words)
    for (invalid in listOf(WORD_YAML.replace("version: '1.0'", "version: '2.0'"), WORD_YAML.replace("end_ms: 1900", "end_ms: 1100"),
        WORD_YAML + "\nlines: []", WORD_YAML.replace("text: 好", "text: !!java.lang.ProcessBuilder []"), WORD_YAML + "\nunknown: &a [*a]")) {
        check(runCatching { Lyricsfile.parse(invalid) }.isFailure) { "Invalid or unsafe YAML accepted" }
    }
    val missingEnds = WORD_YAML.lineSequence().filterNot { "end_ms" in it }.joinToString("\n") + "\n  - text: 下一句\n    start_ms: 18000\n"
    val inferred = Lyricsfile.parse(missingEnds)
    check(inferred.rows.first().end == 18000L)
    check(InterludeCountdown.presentation(inferred, SavedLyrics()).cues.size == 1) { "Missing ends created a false interlude" }
    val root = Files.createTempDirectory(Path.of("build"), "community-cache-")
    var fetched = 0
    val ttml = LyricsCodec.ttml(document)
    val catalog = AmllCatalog(root) { uri, _ ->
        fetched++
        if (uri.host == "raw.githubusercontent.com") throw UnknownHostException("fixture")
        if (uri.path.endsWith(".jsonl")) CATALOG.toByteArray() else ttml.toByteArray()
    }
    check(catalog.search("测试曲", "甲 / 乙").single().artist == "甲 / 乙")
    check(fetched == 2)
    check(catalog.search("别名", "甲").single().title == "別名" && fetched == 2)
    val name = catalog.search("测试曲", "甲").single().lyricFile
    check(catalog.lyrics(name) == ttml && fetched == 4)
    val offline = AmllCatalog(root) { _, _ -> error("Unexpected network request for durable cache") }
    check(offline.search("测试曲", "甲").size == 1 && offline.cached(name) == ttml)
    check(runCatching { offline.lyrics("../escape.ttml") }.isFailure)
    check(runCatching { offline.parseIndex(CATALOG.replace(name, "../escape.ttml")) }.isFailure)
    check(offline.parseIndex(CATALOG.replace(name, name.replace("-1-", "-1,2-"))).all { it.lyricFile.contains(',') }) { "Official co-author filenames rejected the whole catalog" }
    check(runCatching { offline.parseIndex("<html>not a catalog</html>") }.isFailure)
    var requests = 0
    val broken = AmllCatalog { _, _ -> error("offline") }
    check(runCatching { broken.search("测试曲", "甲") }.isFailure && runCatching { broken.search("测试曲", "甲") }.isFailure)
    val amll = LyricSources(offline) { _, query ->
        requests++
        if (query.any { it.first == "artistName" }) Json.parseToJsonElement("""{"status":200,"data":{"items":[]}}""")
        else Json.parseToJsonElement("""{"status":200,"data":{"items":[{"id":1,"filename":"$name","musicNames":["测试曲"],"artistNames":["甲","乙"]}]}}""")
    }
    check(amll.search(LyricSource.AMLL, "测试曲", "甲").single().lyricFile == name && requests == 2)
    requests = 0
    check(amll.search(LyricSource.AMLL, "测试曲", "甲 / 乙").size == 1 && requests == 1) { "Compound singer was sent as one strict filter" }
    val unavailable = LyricSources(offline) { _, _ -> error("API offline") }
    check(unavailable.search(LyricSource.AMLL, "测试曲", "甲").size == 1)
    check(unavailable.download(Candidate(LyricSource.AMLL, name, "测试曲", "甲", lyricFile = name)).document().hasWords)
    val queryLog = mutableListOf<List<Pair<String, String>>>()
    val lrclib = LyricSources(offline) { _, query ->
        queryLog += query.toList()
        if (query.any { it.first == "artist_name" }) JsonArray(emptyList()) else buildJsonArray { add(buildJsonObject {
            put("id", 1); put("trackName", "测试曲"); put("artistName", "测试歌手"); put("duration", 180); put("hasWordSync", true); put("lyricsfile", WORD_YAML); put("syncedLyrics", "[00:01.200]你好")
        }) }
    }
    val words = lrclib.searchTyped(LyricSource.LRCLIB, "测试曲", "测试歌手", LyricsFormat.LINE)
    check(lrclib.download(words.single()).document().hasWords && words.single().text == WORD_YAML)
    check(queryLog.size == 2 && queryLog.none { query -> query.any { it.first == "q" } })
    val cachedSearch = LyricSources(offline) { _, query ->
        if (query.any { it.first == "artist_name" }) check(query.any { it == ("artist_name" to "Kelly Clarkson") }) { "Provider artist lost spaces" }
        if (query.none { it.first == "q" }) throw HttpFailure(503, "1")
        buildJsonArray { add(buildJsonObject { put("id", 2); put("trackName", "测试曲"); put("artistName", "测试歌手"); put("syncedLyrics", "[00:01.000]已恢复") }) }
    }
    check(cachedSearch.search(LyricSource.LRCLIB, "测试曲", "Kelly Clarkson").single().id == "2")
    val beforeWordSearch = queryLog.size
    check(lrclib.searchTyped(LyricSource.LRCLIB, "测试曲", "测试歌手", LyricsFormat.WORD).isEmpty() && queryLog.size == beforeWordSearch)
    check(cachedSearch.searchTyped(LyricSource.LRCLIB, "测试曲", "Kelly Clarkson", LyricsFormat.ENHANCED).isEmpty())
    println("PASS: LRCLIB real Lyricsfile word timing, safe YAML limits/versions, structured-title recovery; AMLL singer fallback, aliases, API failure/index/CDN recovery, durable offline cache and path validation.")
}

fun checkSourceRecovery() {
    check(SourceTransport.proxy("http://127.0.0.1:7897")?.type() == Proxy.Type.HTTP)
    check(SourceTransport.proxy("socks5://localhost:1080")?.type() == Proxy.Type.SOCKS)
    check(SourceTransport.proxy("http://invalid:70000") == null)
    check(SourceTransport.retryDelay("30") == 30000L)
    fun server(statuses: List<Int>, retry: String? = null, test: (URI) -> Unit) {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val handler = FutureTask<Unit> {
            statuses.forEach { status -> server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                check(reader.readLine().startsWith("GET "))
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 $status Test\r\nContent-Length: 2\r\nConnection: close\r\n" + (retry?.let { "Retry-After: $it\r\n" } ?: "") + "\r\n{}").toByteArray()); flush()
                }
            } }
        }
        Thread.ofVirtual().start(handler)
        try { test(URI.create("http://127.0.0.1:${server.localPort}/test")); handler.get(4, TimeUnit.SECONDS) }
        finally { server.close(); if (!handler.isDone) handler.cancel(true) }
    }
    server(listOf(503, 200), "1") { uri -> check(String(SourceTransport.bytes(uri)) == "{}") }
    server(listOf(200)) { uri ->
        val closed = ServerSocket(0).also { it.close() }.localPort
        check(String(SourceTransport.bytes(uri, routeOptions = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", closed)), Proxy.NO_PROXY))) == "{}")
    }
    server(listOf(200)) { uri ->
        val first = ServerSocket(0).also { it.close() }.localPort
        val second = ServerSocket(0).also { it.close() }.localPort
        val dead = listOf(first, second).map { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it)) }
        check(String(SourceTransport.bytes(uri, routeOptions = dead + Proxy.NO_PROXY)) == "{}") { "Third configured route was never tried" }
    }
    server(listOf(503), "30") { address ->
        val origin = URI.create("http://busy-fixture.invalid/test")
        val routes = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", address.port)))
        check(runCatching { SourceTransport.bytes(origin, routeOptions = routes) }.exceptionOrNull()?.message.orEmpty().contains("30"))
        check(runCatching { SourceTransport.bytes(origin, routeOptions = routes) }.exceptionOrNull()?.message.orEmpty().contains("限流"))
    }
    server(listOf(429), "30") { uri ->
        check(runCatching { SourceTransport.bytes(uri) }.exceptionOrNull()?.message.orEmpty().contains("30"))
        check(runCatching { SourceTransport.bytes(uri) }.exceptionOrNull()?.message.orEmpty().contains("限流"))
    }
    println("PASS: local recovery tests honor Retry-After, retry one 503, fail over a dead proxy to direct, and suppress repeat requests during 429 cooldown.")
}

fun checkCommunityNetwork() = runBlocking {
    val sources = LyricSources()
    for ((source, title, artist, format) in listOf(
        listOf(LyricSource.LRCLIB, "Because of You", "Kelly Clarkson", LyricsFormat.LINE),
        listOf(LyricSource.AMLL, "晴天", "周杰伦 / Jay Chou", LyricsFormat.WORD))) {
        val found = sources.searchTyped(source as LyricSource, title as String, artist as String, format as LyricsFormat)
        check(found.isNotEmpty()) { "$source returned no verified lyrics" }
        val result = sources.download(found.first(), format)
        check(result.document().rows.isNotEmpty())
        println("LIVE $source results=${found.size} kind=${result.document().kind} words=${result.document().hasWords}")
    }
    val directory = Files.createTempDirectory(Path.of("build"), "community-live-")
    val catalog = AmllCatalog(directory)
    val indexed = catalog.search("晴天", "周杰伦")
    check(indexed.isNotEmpty())
    val text = catalog.lyrics(indexed.first().lyricFile)
    check(LyricsCodec.parse(text).hasWords)
    val offline = AmllCatalog(directory) { _, _ -> error("Live cache unexpectedly requested the network") }
    check(offline.search("晴天", "周杰伦").isNotEmpty() && offline.cached(indexed.first().lyricFile) == text)
    println("LIVE AMLL official index and raw lyrics verified; persistent cache reopened without network")
}

fun checkReportedCommunityNetwork() = runBlocking {
    val reports = listOf("因为爱情" to "王菲 / 陈奕迅", "我们俩" to "郭顶").flatMap { (title, artist) ->
        listOf(LyricSource.LRCLIB, LyricSource.AMLL).map { source -> async(Dispatchers.IO) {
            val found = LyricSources().searchTyped(source, title, artist, if (source == LyricSource.LRCLIB) LyricsFormat.LINE else LyricsFormat.WORD)
            val exact = found.filter { SearchRanking.key(it.title) == SearchRanking.key(title) && SearchRanking.artists(it.artist) == SearchRanking.artists(artist) }
            if (source == LyricSource.LRCLIB && title == "因为爱情") check(exact.isNotEmpty()) { "The reported LRCLIB results were still hidden by word-only filtering" }
            val samples = exact.take(2).joinToString { "${it.artist}: ${LyricSources().download(it).document().kind}" }
            "$source | $title | candidates=${found.size} | matchedRecording=${exact.size} | $samples"
        } }
    }.awaitAll()
    Files.write(Path.of("build/community-reported-0.6.8.txt"), reports)
    reports.forEach(::println)
}
