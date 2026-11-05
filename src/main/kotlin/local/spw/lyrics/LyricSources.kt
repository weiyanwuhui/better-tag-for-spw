package local.spw.lyrics

import kotlinx.serialization.json.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

enum class LyricSource(val label: String) { NETEASE("网易云"), QQ("QQ 音乐"), KUGOU("酷狗"), LRCLIB("LRCLIB"), AMLL("AMLL 歌词库") }
data class Candidate(val source: LyricSource, val id: String, val title: String, val artist: String,
    val album: String = "", val duration: Long = 0, val accessKey: String = "", val text: String? = null,
    val loaded: DownloadedLyrics? = null, val hash: String = "", val songId: String = "", val lyricFile: String = "") {
    val key: String get() = "${source.name}:$id"
}
data class DownloadedLyrics(val text: String, val source: String, val translation: String? = null, val romanization: String? = null) {
    private val parsed by lazy { LyricsCodec.withAuxiliary(LyricsCodec.parse(text), translation, romanization) }
    fun document(): LyricsDocument = parsed
    fun matches(format: LyricsFormat): Boolean = when (format) {
        LyricsFormat.WORD, LyricsFormat.ENHANCED -> document().hasWords
        LyricsFormat.TTML -> document().kind == "TTML"
        else -> true
    }
    fun applyTo(saved: SavedLyrics, part: LyricsPart?, format: LyricsFormat, fallback: String): SavedLyrics = when (part) {
        null -> saved.copy(text = text, source = source, translation = translation, romanization = romanization, format = format)
        LyricsPart.ORIGINAL -> {
            val previous = (saved.text ?: fallback).takeIf { it.isNotBlank() }?.let { LyricsCodec.source(saved, fallback) }
            saved.copy(text = LyricsCodec.ttml(document().copy(rows = document().rows.map { it.copy(translation = "", romanization = "") })), source = source, format = format,
                translation = saved.translation ?: previous?.let { auxiliaryText(it, LyricsPart.TRANSLATION) },
                romanization = saved.romanization ?: previous?.let { auxiliaryText(it, LyricsPart.ROMANIZATION) })
        }
        LyricsPart.TRANSLATION -> saved.copy(translation = auxiliaryText(document(), part))
        LyricsPart.ROMANIZATION -> saved.copy(romanization = auxiliaryText(document(), part))
    }
}

class LyricSources(private val amllCatalog: AmllCatalog = AmllCatalog.shared,
    private val requestJson: (String, Array<out Pair<String, String>>) -> JsonElement = { base, query ->
    if (java.net.URI.create(base).host in setOf("lrclib.net", "api.amll.dev")) SourceTransport.json(base, query) else Network.json(base, *query)
}) {
    suspend fun searchTyped(source: LyricSource, title: String, artist: String, format: LyricsFormat): List<Candidate> = coroutineScope {
        if (source !in available(format)) return@coroutineScope emptyList()
        val candidates = runInterruptible(Dispatchers.IO) { search(source, title, artist) }
        if (format in listOf(LyricsFormat.AUTO, LyricsFormat.LINE)) return@coroutineScope candidates
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
        val checked = candidates.map { candidate -> async {
            probePermits.acquire()
            try {
                try {
                    val loaded = runInterruptible(Dispatchers.IO) { download(candidate, format) }
                    if (loaded.matches(format)) candidate.copy(loaded = loaded) else null
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { errors.add(failure); null }
            } finally { probePermits.release() }
        } }.awaitAll().filterNotNull()
        if (candidates.isNotEmpty() && errors.size == candidates.size) throw errors.first()
        checked
    }

    fun search(source: LyricSource, title: String, artist: String): List<Candidate> {
        require(title.isNotBlank() && title.length <= 300 && artist.length <= 300) { "请输入有效的歌名和歌手" }
        val query = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        return when (source) {
            LyricSource.NETEASE -> {
                val root = request("https://music.163.com/api/search/get", "s" to query, "type" to "1", "offset" to "0", "limit" to "20").jsonObject
                require(root["code"]?.jsonPrimitive?.int == 200) { "网易云搜索暂时不可用" }
                root["result"]?.jsonObject?.get("songs").array().map { item ->
                    val x = item.jsonObject
                    Candidate(source, x.s("id"), x.s("name"), x["artists"].array().joinToString(" / ") { it.jsonObject.s("name") },
                        x["album"]?.jsonObject?.s("name").orEmpty(), x.n("duration"))
                }
            }
            LyricSource.QQ -> QqMusic.records(QqMusic.search(query, requestJson)).map { x ->
                Candidate(source, x.s("mid").ifBlank { x.s("songmid") }, x.s("title").ifBlank { x.s("songname") },
                    x["singer"].array().joinToString(" / ") { it.jsonObject.s("name") },
                    (x["album"] as? JsonObject)?.s("name") ?: x.s("albumname"), x.n("interval") * 1000,
                    songId = x.s("id").ifBlank { x.s("songid") })
            }
            LyricSource.KUGOU -> {
                val root = request("https://mobiles.kugou.com/api/v3/search/song", "format" to "json", "keyword" to query,
                    "page" to "1", "pagesize" to "20", "showtype" to "1").jsonObject
                require(root["status"]?.jsonPrimitive?.int == 1) { "酷狗搜索暂时不可用" }
                root["data"]?.jsonObject?.get("info").array().map { val x = it.jsonObject
                    Candidate(source, x.s("hash"), x.s("songname"), x.s("singername"), x.s("album_name"),
                        duration = x.n("duration") * 1000, hash = x.s("hash"))
                }
            }
            LyricSource.LRCLIB -> {
                searchLrclib(title, artist)
            }
            LyricSource.AMLL -> {
                searchAmll(title, artist)
            }
        }.distinctBy { it.key }.take(30)
    }

    fun lyrics(candidate: Candidate): String = download(candidate).text

    fun download(candidate: Candidate, format: LyricsFormat = LyricsFormat.AUTO): DownloadedLyrics {
        candidate.loaded?.let { return it }
        if (candidate.source == LyricSource.KUGOU && candidate.text == null) return downloadKugou(candidate, format)
        var translation: String? = null
        var romanization: String? = null
        val raw = candidate.text ?: when (candidate.source) {
            LyricSource.NETEASE -> {
                val x = try { WordSources.netease(candidate.id) }
                    catch (cancel: InterruptedException) { throw cancel }
                    catch (_: Exception) { request("https://music.163.com/api/song/lyric", "id" to candidate.id, "lv" to "-1", "kv" to "-1", "tv" to "-1", "rv" to "-1").jsonObject }
                require(x["code"]?.jsonPrimitive?.int == 200) { "网易云歌词暂时不可用" }
                translation = x["tlyric"]?.jsonObject?.s("lyric")?.takeIf { it.isNotBlank() }
                romanization = x["romalrc"]?.jsonObject?.s("lyric")?.takeIf { it.isNotBlank() }
                x["yrc"]?.jsonObject?.s("lyric")?.takeIf { it.isNotBlank() } ?: x["lrc"]?.jsonObject?.s("lyric").orEmpty()
            }
            LyricSource.QQ -> {
                val loaded = try { WordSources.qq(candidate) }
                catch (cancel: InterruptedException) { throw cancel }
                catch (failure: Exception) {
                    if (format !in listOf(LyricsFormat.AUTO, LyricsFormat.LINE)) throw failure
                    val x = request("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", "format" to "json", "nobase64" to "1", "g_tk" to "5381", "songmid" to candidate.id).jsonObject
                    require(x["code"]?.jsonPrimitive?.int == 0) { "QQ 音乐歌词暂时不可用" }
                    DownloadedLyrics(htmlDecode(x.s("lyric")), candidate.source.label,
                        htmlDecode(x.s("trans")).takeIf { it.isNotBlank() }, htmlDecode(x.s("roma")).takeIf { it.isNotBlank() })
                }
                translation = loaded.translation; romanization = loaded.romanization
                loaded.text
            }
            LyricSource.AMLL -> downloadAmll(candidate)
            else -> error("此候选没有同步歌词")
        }
        val result = DownloadedLyrics(raw.removePrefix("\uFEFF").trim(), candidate.source.label, translation, romanization)
        result.document()
        if (candidate.source == LyricSource.KUGOU) validateKugouContent(candidate, result.text)
        return result
    }

    private fun request(base: String, vararg query: Pair<String, String>): JsonElement {
        return requestJson(base, query)
    }

    private fun searchLrclib(title: String, artist: String): List<Candidate> {
        val filters = mutableListOf("track_name" to title.trim())
        queryArtist(artist)?.let { filters += "artist_name" to it }
        val queries = listOf(filters.toTypedArray(), arrayOf("track_name" to title.trim()), arrayOf("q" to title.trim())).distinctBy { it.toList() }
        var failure: Exception? = null
        for (query in queries) try {
            val root = request("https://lrclib.net/api/search", *query)
            require(root is JsonArray) { "LRCLIB 返回结构异常" }
            val found = root.mapNotNull { item ->
                val x = item.jsonObject
                val yaml = x["lyricsfile"]?.jsonPrimitive?.contentOrNull
                val synced = x["syncedLyrics"]?.jsonPrimitive?.contentOrNull
                val text = if (!yaml.isNullOrBlank() && x["hasWordSync"]?.jsonPrimitive?.booleanOrNull == true) yaml else synced
                if (text.isNullOrBlank()) null else Candidate(LyricSource.LRCLIB, x.s("id"), x.s("trackName"), x.s("artistName"), x.s("albumName"),
                    (x["duration"]?.jsonPrimitive?.doubleOrNull?.times(1000))?.toLong() ?: 0, text = text)
            }
            if (found.isNotEmpty()) return found
        } catch (cancel: InterruptedException) { throw cancel }
        catch (error: Exception) { failure = error }
        if (failure != null) throw failure
        return emptyList()
    }
    private fun queryArtist(artist: String): String? = artist.takeIf { SearchRanking.artists(it).size == 1 }
        ?.replace(Regex("(?<=[\\p{IsHan}\\s])\\s*[(（][^)）]*[)）]"), "")?.trim()?.takeIf(String::isNotEmpty)
    private fun amllData(root: JsonElement): JsonObject {
        val value = root.jsonObject
        require(value["status"]?.jsonPrimitive?.intOrNull == 200) { "AMLL 接口异常：${value.s("message").take(120)}" }
        return value["data"] as? JsonObject ?: error("AMLL 返回结构异常")
    }
    private fun searchAmll(title: String, artist: String): List<Candidate> {
        var failed: Exception? = null
        val filters = mutableListOf("musicName" to title.trim(), "pageSize" to "30")
        queryArtist(artist)?.let { filters += "artistName" to it }
        val queries = listOf(filters.toTypedArray(), arrayOf("musicName" to title.trim(), "pageSize" to "30")).distinctBy { it.toList() }
        for (query in queries) try {
            val data = amllData(request("https://api.amll.dev/v1/lyrics/search", *query))
            require(data["items"] is JsonArray) { "AMLL 搜索结果结构异常" }
            val found = data["items"].array().map { item -> val x = item.jsonObject
                val titles = x["musicNames"].array().map { it.jsonPrimitive.content }
                Candidate(LyricSource.AMLL, x.s("id"), titles.firstOrNull { SearchRanking.key(it) == SearchRanking.key(title) } ?: titles.firstOrNull().orEmpty(),
                    x["artistNames"].array().joinToString(" / ") { it.jsonPrimitive.content }, x["albumNames"].array().firstOrNull()?.jsonPrimitive?.content.orEmpty(), lyricFile = x.s("filename"))
            }
            if (found.isNotEmpty()) return found
        } catch (cancel: InterruptedException) { throw cancel }
        catch (failure: Exception) { failed = failure; break }
        return try { amllCatalog.search(title, artist) }
        catch (cancel: InterruptedException) { throw cancel }
        catch (failure: Exception) { throw IllegalStateException("AMLL 搜索及词库索引均不可用：${(failed ?: failure).message.orEmpty().take(160)}", failed ?: failure) }
    }
    private fun downloadAmll(candidate: Candidate): String {
        if (candidate.lyricFile.isNotBlank()) amllCatalog.cached(candidate.lyricFile)?.let { return it }
        try {
            val query = if (candidate.id.toLongOrNull() != null) "id" to candidate.id else "filename" to candidate.lyricFile
            val text = amllData(request("https://api.amll.dev/v1/lyrics/get", query)).s("lyrics")
            require(text.isNotBlank()) { "AMLL 没有返回歌词正文" }; LyricsCodec.parse(text)
            return if (candidate.lyricFile.isNotBlank()) amllCatalog.store(candidate.lyricFile, text) else text
        } catch (cancel: InterruptedException) { throw cancel }
        catch (failure: Exception) { if (candidate.lyricFile.isBlank()) throw failure }
        return amllCatalog.lyrics(candidate.lyricFile)
    }

    private fun downloadKugou(song: Candidate, format: LyricsFormat): DownloadedLyrics {
        val candidates = if (song.hash.isBlank()) listOf(song) else {
            val root = request("https://lyrics.kugou.com/search", "ver" to "1", "man" to "yes", "client" to "pc",
                "hash" to song.hash, "duration" to song.duration.toString()).jsonObject
            require(root["status"]?.jsonPrimitive?.int == 200) { "酷狗歌词搜索暂时不可用" }
            root["candidates"].array().map { it.jsonObject }.filter {
                kugouMatches(song, it.s("song"), it.s("singer"), it.n("duration"))
            }.map { song.copy(id = it.s("id"), accessKey = it.s("accesskey")) }
        }
        require(candidates.isNotEmpty()) { "酷狗未取得匹配的歌词候选" }
        var failure: Exception? = null
        val kinds = if (format == LyricsFormat.LINE) listOf("lrc") else if (format == LyricsFormat.AUTO) listOf("krc", "lrc") else listOf("krc")
        for (kind in kinds) for (candidate in candidates) {
            try {
                val root = request("https://lyrics.kugou.com/download", "ver" to "1", "client" to "pc", "id" to candidate.id,
                    "accesskey" to candidate.accessKey, "fmt" to kind, "charset" to "utf8").jsonObject
                require(root["status"]?.jsonPrimitive?.int == 200) { "酷狗候选下载失败" }
                val bytes = Base64.getDecoder().decode(root.s("content"))
                val text = if (kind == "krc") WordSources.krc(bytes) else String(bytes, Charsets.UTF_8)
                validateKugouContent(song, text)
                val downloaded = DownloadedLyrics(text.removePrefix("\uFEFF").trim(), song.source.label)
                require(kind != "krc" || downloaded.document().hasWords) { "此 KRC 未包含字时间，继续检查其他候选" }
                require(downloaded.matches(format)) { "此候选未包含字时间，继续检查其他候选" }
                downloaded.document()
                return downloaded
            } catch (cancel: InterruptedException) { throw cancel }
            catch (error: Exception) { failure = error }
        }
        throw IllegalStateException("酷狗的匹配候选均未取得所需歌词", failure)
    }

    companion object {
        private val probePermits = Semaphore(4)
        fun available(format: LyricsFormat): List<LyricSource> = when (format) {
            LyricsFormat.WORD, LyricsFormat.ENHANCED -> LyricSource.entries.filter { it != LyricSource.LRCLIB }
            LyricsFormat.TTML -> listOf(LyricSource.AMLL)
            else -> LyricSource.entries
        }
        fun sourceLabel(source: LyricSource, format: LyricsFormat): String = source.label + if (source in available(format)) "" else if (format == LyricsFormat.TTML) " · 不用于 TTML 搜索" else " · 不用于逐字搜索"
        private fun normalized(value: String) = ChineseConverter.convert(value, ChineseMode.SIMPLIFIED).lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        private fun artistKey(value: String): List<String> = value.replace(Regex("(?<=[\\p{IsHan}\\s])\\s*[(（][^)）]*[)）]"), "")
            .split(Regex("[/、,&;，；＆]")).map(::normalized).filter { it.isNotBlank() }.distinct().sorted()
        fun kugouMatches(song: Candidate, title: String, artist: String, duration: Long): Boolean =
            normalized(song.title) == normalized(title) &&
                (song.artist.isBlank() || artistKey(song.artist) == artistKey(artist)) &&
                (song.duration <= 0 || duration > 0 && kotlin.math.abs(song.duration - duration) <= maxOf(3000L, song.duration / 50))

        fun validateKugouContent(song: Candidate, text: String) {
            val title = Regex("(?im)^\\[ti:([^]]+)]").find(text)?.groupValues?.get(1)
            val artist = Regex("(?im)^\\[ar:([^]]+)]").find(text)?.groupValues?.get(1)
            require(title.isNullOrBlank() || normalized(title) == normalized(song.title)) { "酷狗返回了其他歌曲或版本，已阻止应用" }
            require(artist.isNullOrBlank() || song.artist.isBlank() || artistKey(artist) == artistKey(song.artist)) { "酷狗返回的歌手不匹配，已阻止应用" }
        }
        private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        private fun JsonObject.n(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0
        private fun JsonElement?.array() = (this as? JsonArray).orEmpty()

        fun htmlDecode(text: String): String = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);").replace(text) {
            val code = if (it.groupValues[1].startsWith("x")) it.groupValues[1].drop(1).toIntOrNull(16) else it.groupValues[1].toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else it.value
        }.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        // SPL does not interpret LRC's offset tag; normalize it once before storing the source.
        fun applyLrcOffset(text: String): String {
            val tag = Regex("(?im)^\\[offset:([+-]?\\d+)\\]\\s*$")
            val offset = tag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val without = tag.replace(text, "")
            if (offset == 0L) return without
            require(offset in -60_000..60_000) { "源歌词的 offset 超出支持范围" }
            return Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?\\]").replace(without) {
                val millis = it.groupValues[1].toLong() * 60_000 + it.groupValues[2].toLong() * 1000 + it.groupValues[3].padEnd(3, '0').toLong()
                val shifted = (millis + offset).coerceAtLeast(0)
                "[%02d:%02d.%03d]".format(shifted / 60_000, shifted / 1000 % 60, shifted % 1000)
            }
        }
    }
}
