package local.spw.lyrics

import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

class AmllCatalog(private val directory: Path? = null, private val fetch: (URI, Int) -> ByteArray = SourceTransport::bytes) {
    private var records: List<Candidate>? = null
    private var loadedAt = 0L
    private var retryAt = 0L
    private val lyricCache = LinkedHashMap<String, String>(16, 0.75f, true)
    private fun filename(value: String) = value.matches(Regex("[A-Za-z0-9_,-]+\\.ttml"))
    private fun sources(path: String) = listOf("https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/$path",
        "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/$path")
    private fun download(path: String, limit: Int, validate: (ByteArray) -> Unit): ByteArray {
        var error: Exception? = null
        for (url in sources(path)) try {
            return fetch(URI.create(url), limit).also(validate)
        } catch (cancel: InterruptedException) { throw cancel }
        catch (failure: Exception) { error = failure }
        throw IllegalStateException("AMLL 官方词库及 CDN 均暂时无法读取", error)
    }
    fun parseIndex(text: String): List<Candidate> {
        require(text.toByteArray().size <= 8388608) { "AMLL 索引过大" }
        val result = text.lineSequence().filter(String::isNotBlank).flatMap { line ->
            val item = Json.parseToJsonElement(line).jsonObject
            val name = item.string("rawLyricFile"); require(filename(name)) { "AMLL 索引文件名无效" }
            val metadata = item["metadata"].array().associate { entry -> val pair = entry.jsonArray; pair[0].jsonPrimitive.content to pair[1].jsonArray.map { it.jsonPrimitive.content } }
            metadata["musicName"].orEmpty().asSequence().map { title -> Candidate(LyricSource.AMLL, name, title, metadata["artists"].orEmpty().joinToString(" / "), metadata["album"]?.firstOrNull().orEmpty(), lyricFile = name) }
        }.toList()
        require(result.isNotEmpty() && result.size <= 50000 && result.all { it.title.isNotBlank() }) { "AMLL 索引内容无效" }
        return result
    }
    private fun persist(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "lyrics-cache-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try { Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun search(title: String, artist: String): List<Candidate> {
        val now = System.currentTimeMillis()
        val path = directory?.resolve("amll-index.jsonl")
        if (records == null && path != null && Files.isRegularFile(path)) runCatching {
            require(Files.size(path) <= 8388608)
            records = parseIndex(Files.readString(path)); loadedAt = Files.getLastModifiedTime(path).toMillis()
        }
        if (records == null && now < retryAt) error("AMLL 词库索引暂不可用，请稍后重试")
        if (now - loadedAt > 86400000 && now >= retryAt) {
            try {
                var parsed = emptyList<Candidate>()
                val bytes = download("metadata/raw-lyrics-index.jsonl", 8388608) { parsed = parseIndex(String(it, Charsets.UTF_8)) }
                records = parsed; loadedAt = now
                if (path != null) runCatching { persist(path, bytes) }
            } catch (cancel: InterruptedException) { throw cancel }
            catch (failure: Exception) { retryAt = now + 60000; if (records == null) throw failure }
        }
        val query = SearchRanking.key(title)
        require(query.isNotEmpty()) { "请输入有效歌名" }
        val found = records.orEmpty().filter { SearchRanking.key(it.title).contains(query) }
        return SearchRanking.rank(found, SearchTarget(title, artist, "", 0)).map { it.candidate }.distinctBy { it.lyricFile }.take(30)
    }
    @Synchronized fun cached(name: String): String? {
        if (!filename(name)) return null
        lyricCache[name]?.let { return it }
        val file = directory?.resolve("amll-$name") ?: return null
        return runCatching { require(Files.size(file) <= LyricsStore.MAX_LYRICS_BYTES); Files.readString(file).also { LyricsCodec.parse(it); remember(name, it) } }.getOrNull()
    }
    @Synchronized fun remember(name: String, text: String) {
        require(filename(name)) { "AMLL 歌词文件名无效" }; LyricsCodec.parse(text)
        lyricCache[name] = text
        while (lyricCache.size > 64 || lyricCache.values.sumOf { it.toByteArray().size } > 8388608) lyricCache.remove(lyricCache.keys.first())
    }
    fun store(name: String, text: String): String {
        remember(name, text)
        directory?.let { folder -> runCatching {
            persist(folder.resolve("amll-$name"), text.toByteArray())
            Files.list(folder).use { files -> files.filter { it.fileName.toString().startsWith("amll-") && it.fileName.toString().endsWith(".ttml") }
                .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed()).skip(128).forEach { Files.deleteIfExists(it) } }
        } }
        return text
    }
    fun lyrics(name: String): String {
        require(filename(name)) { "AMLL 歌词文件名无效" }
        cached(name)?.let { return it }
        val bytes = download("raw-lyrics/$name", LyricsStore.MAX_LYRICS_BYTES) { LyricsCodec.parse(String(it, Charsets.UTF_8)) }
        return store(name, String(bytes, Charsets.UTF_8))
    }
    companion object { @Volatile var shared = AmllCatalog() }
}
