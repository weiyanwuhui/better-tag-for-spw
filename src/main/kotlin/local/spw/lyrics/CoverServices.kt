@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import kotlinx.serialization.json.*
import org.jetbrains.skia.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest

enum class CoverSource(val label: String) { NETEASE("网易云"), QQ("QQ 音乐"), KUGOU("酷狗"), ITUNES("iTunes") }
data class CoverCandidate(val title: String, val artist: String, val url: String, val source: String, val album: String = "", val duration: Long = 0)
data class CoverPicture(val bytes: ByteArray, val width: Int, val height: Int, val source: String)

object Covers {
    const val MAX_BYTES = 20_971_520
    fun search(source: CoverSource, query: String): List<CoverCandidate> {
        require(query.isNotBlank() && query.length <= 600) { "请输入歌名或歌手" }
        val root = when (source) {
            CoverSource.NETEASE -> {
                val found = Network.json("https://music.163.com/api/search/get", "s" to query, "type" to "1", "offset" to "0", "limit" to "50")
                require(found.jsonObject["code"]?.jsonPrimitive?.intOrNull == 200) { "网易云封面搜索暂不可用" }
                val ids = found.jsonObject["result"]?.jsonObject?.get("songs").array().mapNotNull { it.jsonObject.string("id").toLongOrNull() }
                if (ids.isEmpty()) return emptyList()
                Network.json("https://music.163.com/api/song/detail/", "ids" to ids.joinToString(",", "[", "]"))
            }
            CoverSource.QQ -> QqMusic.search(query) { url, pairs -> Network.json(url, *pairs) }
            CoverSource.KUGOU -> Network.json("https://mobiles.kugou.com/api/v3/search/song", "keyword" to query, "page" to "1", "pagesize" to "50")
            CoverSource.ITUNES -> Network.json("https://itunes.apple.com/search", "term" to query, "entity" to "song", "country" to "TW", "limit" to "50")
        }
        return parseSongs(source, root)
    }

    fun parseSongs(source: CoverSource, root: JsonElement): List<CoverCandidate> = when (source) {
        CoverSource.NETEASE -> root.jsonObject["songs"].array().map { item ->
            val x = item.jsonObject; val album = (x["album"] ?: x["al"])?.jsonObject
            CoverCandidate(x.string("name"), (x["artists"] ?: x["ar"]).array().joinToString(" / ") { it.jsonObject.string("name") },
                album?.string("picUrl").orEmpty(), source.label, album?.string("name").orEmpty(), (x["duration"] ?: x["dt"])?.jsonPrimitive?.longOrNull ?: 0)
        }
        CoverSource.QQ -> {
            val section = root.jsonObject["search"]?.jsonObject
            require((section ?: root.jsonObject)["code"]?.jsonPrimitive?.intOrNull == 0) { "QQ 音乐封面搜索暂不可用" }
            val data = section?.get("data")?.jsonObject?.get("body")?.jsonObject ?: root.jsonObject["data"]?.jsonObject
            data?.get("song")?.jsonObject?.let { it["list"] }.array().mapNotNull { item ->
                val x = item.jsonObject; val album = x["album"]?.jsonObject
                val mid = album?.string("mid") ?: x.string("albummid")
                if (!mid.matches(Regex("[a-zA-Z0-9]+"))) null else CoverCandidate(x.string("title").ifBlank { x.string("songname") },
                    x["singer"].array().joinToString(" / ") { it.jsonObject.string("name") },
                    "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg", source.label,
                    album?.string("name") ?: x.string("albumname"), (x["interval"]?.jsonPrimitive?.longOrNull ?: 0) * 1000)
            }
        }
        CoverSource.KUGOU -> {
            require(root.jsonObject["status"]?.jsonPrimitive?.intOrNull == 1) { "酷狗封面搜索暂不可用" }
            root.jsonObject["data"]?.jsonObject?.get("info").array().flatMap { item -> listOf(item) + item.jsonObject["group"].array() }.map { item ->
                val x = item.jsonObject
                CoverCandidate(x.string("songname"), x.string("singername"),
                    x["trans_param"]?.jsonObject?.string("union_cover").orEmpty().ifBlank { x.string("imgurl") }.replace("{size}", "800"), source.label,
                    x.string("album_name"), (x["duration"]?.jsonPrimitive?.longOrNull ?: 0) * 1000)
            }
        }
        CoverSource.ITUNES -> root.jsonObject["results"].array().map { item ->
            val x = item.jsonObject
            CoverCandidate(x.string("trackName"), x.string("artistName"), x.string("artworkUrl100").replace("100x100bb", "1200x1200bb"), source.label,
                x.string("collectionName"), x["trackTimeMillis"]?.jsonPrimitive?.longOrNull ?: 0)
        }
    }.map { it.copy(url = it.url.replace("http://", "https://")) }.filter { it.url.startsWith("https://") && it.title.isNotBlank() }

    fun rank(candidates: List<CoverCandidate>, target: SearchTarget) = candidates.sortedWith(
        compareByDescending<CoverCandidate> { SearchRanking.key(it.title) == SearchRanking.key(target.title) }
            .thenByDescending { SearchRanking.score(Candidate(LyricSource.NETEASE, it.url, it.title, it.artist, it.album, it.duration), target) }
            .thenBy { it.source }.thenBy { it.url }).distinctBy { it.url }

    fun searchAlbum(source: CoverSource, query: String): List<CoverCandidate> {
        require(query.isNotBlank() && query.length <= 600) { "请输入专辑名" }
        val root = when (source) {
            CoverSource.NETEASE -> Network.json("https://music.163.com/api/search/get", "s" to query, "type" to "10", "offset" to "0", "limit" to "24")
            CoverSource.QQ -> Network.json("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg", "format" to "json", "key" to query)
            CoverSource.KUGOU -> Network.json("https://mobiles.kugou.com/api/v3/search/album", "keyword" to query, "page" to "1", "pagesize" to "24")
            CoverSource.ITUNES -> Network.json("https://itunes.apple.com/search", "term" to query, "entity" to "album", "country" to "TW", "limit" to "24")
        }
        return parse(source, root)
    }
    fun parse(source: CoverSource, root: JsonElement): List<CoverCandidate> = when (source) {
        CoverSource.NETEASE -> root.jsonObject["result"]?.jsonObject?.get("albums").array().map { val x = it.jsonObject
            CoverCandidate(x.string("name"), x["artist"]?.jsonObject?.string("name").orEmpty(),
                x.string("picUrl").ifEmpty { x.string("blurPicUrl") }.replace("http://", "https://"), source.label)
        }
        CoverSource.QQ -> root.jsonObject["data"]?.jsonObject?.get("album")?.jsonObject?.get("itemlist").array().map { val x = it.jsonObject
            val mid = x.string("mid")
            require(mid.matches(Regex("[a-zA-Z0-9]+"))) { "封面来源返回了无效的专辑编号" }
            CoverCandidate(x.string("name"), x.string("singer"), "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg", source.label)
        }
        CoverSource.KUGOU -> {
            require(root.jsonObject["status"]?.jsonPrimitive?.intOrNull == 1) { "酷狗封面搜索暂不可用" }
            root.jsonObject["data"]?.jsonObject?.get("info").array().map { val x = it.jsonObject
                CoverCandidate(x.string("albumname"), x.string("singername"), x.string("imgurl").replace("{size}", "800").replace("http://", "https://"), source.label)
            }
        }
        CoverSource.ITUNES -> root.jsonObject["results"].array().map { val x = it.jsonObject
            CoverCandidate(x.string("collectionName"), x.string("artistName"), x.string("artworkUrl100").replace("100x100bb", "1200x1200bb"), source.label)
        }

    }.filter { it.url.startsWith("https://") && it.title.isNotBlank() }.distinctBy { it.url }

    fun file(path: Path): CoverPicture {
        require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_BYTES.toLong()) { "请选择不超过 20 MiB 的图片文件" }
        return normalize(Files.readAllBytes(path), "本地图片 · ${path.fileName}")
    }

    fun normalize(bytes: ByteArray, source: String, edge: Int = 2048): CoverPicture {
        require(bytes.size in 1..MAX_BYTES) { "封面图片不能超过 20 MiB" }
        val data = Data.makeFromBytes(bytes)
        try {
            val codec = Codec.makeFromData(data)
            try { require(codec.width > 0 && codec.height > 0 && codec.width.toLong() * codec.height <= 25_000_000) { "封面分辨率不能超过 2500 万像素" } }
            finally { codec.close() }
        } finally { data.close() }
        val image = Image.makeFromEncoded(bytes)
        try {
            val ratio = minOf(1.0, edge.coerceIn(1, 2048).toDouble() / maxOf(image.width, image.height))
            val width = (image.width * ratio).toInt().coerceAtLeast(1)
            val height = (image.height * ratio).toInt().coerceAtLeast(1)
            val surface = checkNotNull(Surface.makeRasterN32Premul(width, height))
            try {
                surface.canvas.clear(0)
                surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
                val snapshot = surface.makeImageSnapshot()
                try {
                    val encoded = checkNotNull(snapshot.encodeToData(EncodedImageFormat.PNG)) { "封面转码失败" }
                    try { return CoverPicture(encoded.bytes, width, height, source) } finally { encoded.close() }
                } finally { snapshot.close() }
            } finally { surface.close() }
        } finally { image.close() }
    }

    fun persist(directory: Path, picture: CoverPicture): String {
        val name = MessageDigest.getInstance("SHA-256").digest(picture.bytes).joinToString("") { "%02x".format(it) } + ".png"
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        if (!Files.exists(target)) {
            val temporary = Files.createTempFile(directory, "cover-", ".tmp")
            try {
                Files.write(temporary, picture.bytes)
                try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
        return name
    }
}

/** Keeps one owning reference; renderers acquire their own references through the host's existing pipeline. */
class CoverController(private val plugin: LyricsPlugin) : AutoCloseable {
    private var file: String? = null
    private var image: com.xuncorp.voxzen.image.RefCountedImageBitmap? = null
    private val retired = mutableListOf<com.xuncorp.voxzen.image.RefCountedImageBitmap>()
    private var reported: String? = null

    fun publicationHook(currentId: () -> String?): MethodHook = object : MethodHook() {
        override fun after(call: MethodHookParam) {
            if (call.throwable != null) return
            val id = currentId() ?: return
            synchronized(this@CoverController) {
                val original = call.args[1]
                val changed = override(id, original)
                // SPW discards argument replacements; publish into its retained flow after the original call.
                if (changed !== original) NativeHost.replacePublishedCover(changed)
                afterPublication()
            }
        }
    }

    @Synchronized fun override(id: String, original: Any?): Any? {
        val selected = plugin.store.get(id).coverFile
        if (selected != file) {
            image?.let { retired += it }; image = null; file = selected
        }
        if (selected == null) return original
        return try {
            if (image == null) {
                val path = plugin.store.coversDirectory.resolve(selected)
                require(Files.size(path) <= Covers.MAX_BYTES) { "封面文件过大" }
                val data = Data.makeFromBytes(Files.readAllBytes(path))
                try {
                    val codec = Codec.makeFromData(data)
                    try {
                        require(codec.width in 1..2048 && codec.height in 1..2048) { "封面尺寸无效" }
                        image = com.xuncorp.voxzen.image.RefCountedImageBitmap(codec.readPixels())
                    } finally { codec.close() }
                } finally { data.close() }
            }
            image
        } catch (failure: Exception) {
            if (reported != selected) { reported = selected; java.awt.EventQueue.invokeLater { plugin.notify("自定义封面读取失败，暂用原封面", true) } }
            original
        }
    }

    @Synchronized fun afterPublication() { retired.forEach { it.close() }; retired.clear() }
    @Synchronized override fun close() { image?.close(); image = null; file = null; afterPublication() }
}
