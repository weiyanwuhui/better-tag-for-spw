package local.spw.lyrics

import kotlinx.serialization.json.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

enum class MusicTag(val label: String, val field: FieldKey) {
    TITLE("歌曲名称", FieldKey.TITLE), ARTIST("歌手", FieldKey.ARTIST), ALBUM("专辑", FieldKey.ALBUM),
    ALBUM_ARTIST("专辑歌手", FieldKey.ALBUM_ARTIST), YEAR("发行年份", FieldKey.YEAR), TRACK("曲目序号", FieldKey.TRACK),
    DISC("碟片序号", FieldKey.DISC_NO), GENRE("流派", FieldKey.GENRE), COMPOSER("作曲", FieldKey.COMPOSER), LYRICIST("作词", FieldKey.LYRICIST)
}
data class MusicTags(val values: Map<MusicTag, String>, val cover: CoverPicture? = null, val source: String = "", val notice: String = "", val duration: Long = 0)

class TagSources(private val request: (String, Array<out Pair<String, String>>) -> JsonElement = { url, query -> Network.json(url, *query) },
    private val readPicture: (String) -> CoverPicture = { Covers.normalize(Network.bytes(java.net.URI.create(it), Covers.MAX_BYTES), "平台专辑封面") }) {
    private val artworkCache = LinkedHashMap<String, CoverPicture>(16, 0.75f, true)
    fun fetch(candidate: Candidate, includeCover: Boolean = true): MusicTags {
        val base = mapOf(MusicTag.TITLE to candidate.title, MusicTag.ARTIST to candidate.artist, MusicTag.ALBUM to candidate.album).filterValues { it.isNotBlank() }
        var notice = ""
        val detailed = try {
            when (candidate.source) {
                LyricSource.NETEASE -> netease(request("https://music.163.com/api/song/detail/", arrayOf("ids" to "[${candidate.id.toLong()}]")), includeCover)
                LyricSource.QQ -> {
                    val data = buildJsonObject {
                        putJsonObject("comm") { put("ct", 19); put("cv", 0) }
                        put("songinfo", QqMusic.detail(candidate.id, candidate.songId))
                    }
                    qq(request(QqMusic.URL, QqMusic.parameters(data)), includeCover)
                }
                LyricSource.KUGOU -> kugou(request("https://wwwapi.kugou.com/yy/index.php", arrayOf("r" to "play/getdata", "hash" to candidate.hash.ifBlank { candidate.id })), includeCover)
                else -> MusicTags(emptyMap())
            }
        } catch (cancel: InterruptedException) { throw cancel }
        catch (_: Exception) { notice = "详细标签获取失败，已保留搜索结果中的基础标签"; MusicTags(emptyMap()) }
        val identityMatches = LyricSources.kugouMatches(candidate.copy(duration = 0), detailed.values[MusicTag.TITLE]?.takeIf { it.isNotBlank() } ?: candidate.title,
            detailed.values[MusicTag.ARTIST]?.takeIf { it.isNotBlank() } ?: candidate.artist, 0)
        require(identityMatches) { "来源返回了其他歌曲的标签，已阻止应用" }
        val values = base + detailed.values.filterValues { it.isNotBlank() }
        var cover = detailed.cover
        if (includeCover && cover == null && candidate.album.isNotBlank()) {
            try {
                val found = Covers.searchAlbum(CoverSource.NETEASE, "${candidate.album} ${candidate.artist}")
                    .firstOrNull { it.title.equals(candidate.album, true) && it.artist.equals(candidate.artist, true) }
                if (found != null) cover = picture(found.url)
            } catch (cancel: InterruptedException) { throw cancel }
            catch (_: Exception) { }
        }
        return MusicTags(values, cover, candidate.source.label, notice)
    }
    private fun picture(url: String): CoverPicture? = try {
        if (url.isBlank()) null else {
            val key = url.replace("http://", "https://")
            synchronized(artworkCache) { artworkCache[key] } ?: readPicture(key).also { picture ->
                synchronized(artworkCache) {
                    artworkCache[key] = picture
                    while (artworkCache.size > 16 || artworkCache.values.sumOf { it.bytes.size.toLong() } > Covers.MAX_BYTES) {
                        artworkCache.remove(artworkCache.keys.first())
                    }
                }
            }
        }
    } catch (cancel: InterruptedException) { throw cancel } catch (_: Exception) { null }
    fun netease(root: JsonElement, includeCover: Boolean = true): MusicTags {
        val song = root.jsonObject["songs"].array().firstOrNull()?.jsonObject ?: error("来源没有返回歌曲标签")
        val album = (song["album"] ?: song["al"])?.jsonObject
        val artists = (song["artists"] ?: song["ar"]).array().joinToString(" / ") { it.jsonObject.text("name") }
        val values = mutableMapOf(MusicTag.TITLE to song.text("name"), MusicTag.ARTIST to artists, MusicTag.ALBUM to album?.text("name").orEmpty())
        album?.get("artist")?.jsonObject?.text("name")?.takeIf { it.isNotBlank() }?.let { values[MusicTag.ALBUM_ARTIST] = it }
        val date = (song["publishTime"] ?: album?.get("publishTime"))?.jsonPrimitive?.longOrNull ?: 0
        if (date > 0) values[MusicTag.YEAR] = Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC).year.toString()
        song.text("position").toIntOrNull()?.takeIf { it > 0 }?.let { values[MusicTag.TRACK] = it.toString() }
        return MusicTags(values, if (includeCover) picture(album?.text("picUrl").orEmpty()) else null)
    }
    fun qq(root: JsonElement, includeCover: Boolean = true): MusicTags {
        val data = root.jsonObject["songinfo"]?.jsonObject?.get("data")?.jsonObject ?: error("来源没有返回歌曲标签")
        val song = data["track_info"]?.jsonObject ?: error("来源没有返回歌曲标签")
        val album = song["album"]?.jsonObject
        val values = mutableMapOf(MusicTag.TITLE to song.text("title"), MusicTag.ARTIST to song["singer"].array().joinToString(" / ") { it.jsonObject.text("name") }, MusicTag.ALBUM to album?.text("name").orEmpty())
        song.text("time_public").take(4).toIntOrNull()?.takeIf { it > 0 }?.let { values[MusicTag.YEAR] = it.toString() }
        for ((field, name) in listOf(MusicTag.TRACK to "index_album", MusicTag.DISC to "index_cd")) song.text(name).toIntOrNull()?.takeIf { it > 0 }?.let { values[field] = it.toString() }
        val infoItems = when (val info = data["info"]) { is JsonObject -> info.values.toList(); else -> info.array() }
        infoItems.forEach { item ->
            val info = item.jsonObject
            val field = when {
                info.text("selected") == "genre" || info.text("title") in listOf("流派", "歌曲流派") -> MusicTag.GENRE
                info.text("type") == "composer" || info.text("title") == "作曲" -> MusicTag.COMPOSER
                info.text("type") == "lyricist" || info.text("title") == "作词" -> MusicTag.LYRICIST
                info.text("selected") == "pub_time" -> MusicTag.YEAR
                info.text("selected") == "album_singer" || info.text("title") == "专辑歌手" -> MusicTag.ALBUM_ARTIST
                else -> null
            }
            val value = info["content"].array().map { it.jsonObject.text("value") }.filter(String::isNotBlank).distinct().joinToString(" / ")
            if (field != null && value.isNotBlank()) {
                if (field == MusicTag.YEAR) value.take(4).toIntOrNull()?.takeIf { it > 0 }?.let { values[field] = it.toString() }
                else values[field] = value
            }
        }
        val mid = album?.text("mid").orEmpty()
        val url = if (mid.matches(Regex("[a-zA-Z0-9]+"))) "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg" else ""
        return MusicTags(values, if (includeCover) picture(url) else null, duration = (song["interval"]?.jsonPrimitive?.longOrNull ?: 0) * 1000)
    }
    fun kugou(root: JsonElement, includeCover: Boolean = true): MusicTags {
        val song = root.jsonObject["data"]?.jsonObject ?: error("来源没有返回歌曲标签")
        val values = mutableMapOf(MusicTag.TITLE to song.text("song_name"), MusicTag.ARTIST to song.text("author_name"), MusicTag.ALBUM to song.text("album_name"))
        song.text("publish_date").take(4).toIntOrNull()?.takeIf { it > 0 }?.let { values[MusicTag.YEAR] = it.toString() }
        return MusicTags(values, if (includeCover) picture(song.text("img").replace("{size}", "800")) else null)
    }
    private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
}

object TagFiles {
    fun readLyrics(path: Path): String {
        val tag = AudioFileIO.read(path.toFile()).tag
        val lyrics = (if (tag is org.jaudiotagger.tag.wav.WavTag) tag.getID3Tag() else tag)?.getFirst(FieldKey.LYRICS).orEmpty()
        require(lyrics.toByteArray().size <= LyricsStore.MAX_LYRICS_BYTES) { "内嵌歌词过大" }
        return lyrics
    }
    fun localPath(value: String): Path {
        val path = if (value.startsWith("file:", true)) Path.of(java.net.URI.create(value)) else {
            require(!value.contains("://") && !value.startsWith("\\\\")) { "标签写入仅支持本机音频文件" }
            Path.of(value)
        }
        require(path.isAbsolute && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "找不到本机音频文件" }
        // shortcut: enable more containers only after tag, artwork and audio-integrity round trips pass.
        require(path.fileName.toString().substringAfterLast('.').lowercase() in setOf("flac", "mp3", "m4a", "mp4", "ogg", "wav", "opus", "aif", "aiff")) { "此格式暂不支持写入音频标签" }
        return path.toRealPath()
    }
    fun read(path: Path, decodeCover: Boolean = true): MusicTags {
        val audio = AudioFileIO.read(path.toFile())
        val tag = writableTag(audio)
        return MusicTags(MusicTag.entries.associateWith { tag.getFirst(it.field) }, tag.firstArtwork?.binaryData?.let {
            if (decodeCover) Covers.normalize(it, "文件内嵌封面") else CoverPicture(it, 0, 0, "文件内嵌封面")
        }, duration = (audio.audioHeader.preciseTrackLength * 1000).toLong())
    }
    private fun digest(path: Path) = Files.newInputStream(path).use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }; hash.digest()
    }
    @Synchronized fun write(path: Path, values: Map<MusicTag, String>, cover: CoverPicture?, lyrics: String?, backups: Path?,
        replace: (Path, Path) -> Unit = { stage, target -> Files.move(stage, target, ATOMIC_MOVE, REPLACE_EXISTING); Unit }): Path? {
        val target = localPath(path.toString())
        require(Files.isWritable(target)) { "音频文件不可写" }
        require(values.isNotEmpty() || cover != null || lyrics != null) { "没有选择要写入的标签" }
        require(values.values.all { it.length <= 8192 && '\u0000' !in it }) { "标签文本无效或过长" }
        require(lyrics == null || lyrics.toByteArray().size <= LyricsStore.MAX_LYRICS_BYTES) { "歌词过大" }
        require(values[MusicTag.YEAR].isNullOrBlank() || values[MusicTag.YEAR]!!.matches(Regex("[0-9]{4}"))) { "年份应为四位数字" }
        for (key in listOf(MusicTag.TRACK, MusicTag.DISC)) require(values[key].isNullOrBlank() || values[key]!!.matches(Regex("[0-9]+(/[0-9]+)?"))) { "曲目、碟片序号应为数字" }
        if (backups != null) Files.createDirectories(backups)
        val originalHash = digest(target)
        val created = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java).creationTime()
        val extension = target.fileName.toString().substringAfterLast('.')
        val stage = Files.createTempFile(target.parent, ".spw-tags-", ".$extension")
        val backup = backups?.resolve(java.util.UUID.randomUUID().toString() + ".$extension")
        try {
            Files.copy(target, stage, REPLACE_EXISTING, COPY_ATTRIBUTES)
            check(digest(stage).contentEquals(originalHash)) { "音频文件正在被其他程序修改" }
            val audio = AudioFileIO.read(stage.toFile()); val before = audio.audioHeader
            val tag = writableTag(audio)
            values.forEach { (key, value) -> if (value.isBlank()) tag.deleteField(key.field) else tag.setField(key.field, value) }
            if (lyrics != null) tag.setField(FieldKey.LYRICS, lyrics)
            if (cover != null) {
                val artwork = ArtworkFactory.getNew().apply { binaryData = cover.bytes; mimeType = "image/png"; description = "Cover"; pictureType = 3; width = cover.width; height = cover.height }
                tag.deleteArtworkField(); tag.setField(artwork)
            }
            commitAudio(audio, stage)
            val verified = AudioFileIO.read(stage.toFile())
            val verifiedTag = writableTag(verified)
            check(absDuration(before.preciseTrackLength, verified.audioHeader.preciseTrackLength) && before.sampleRateAsNumber == verified.audioHeader.sampleRateAsNumber && before.channels == verified.audioHeader.channels) { "写入后的音频信息校验失败" }
            values.forEach { (key, value) -> check(verifiedTag.getFirst(key.field) == value) { "${key.label}写入校验失败" } }
            if (cover != null) check(verifiedTag.firstArtwork?.binaryData?.contentEquals(cover.bytes) == true) { "封面写入校验失败" }
            if (lyrics != null) check(verifiedTag.getFirst(FieldKey.LYRICS) == lyrics) { "歌词写入校验失败" }
            check(digest(target).contentEquals(originalHash)) { "音频已被其他程序修改，已取消写入" }
            if (backup != null) {
                Files.copy(target, backup, COPY_ATTRIBUTES)
                check(digest(backup).contentEquals(originalHash)) { "备份校验失败，已取消写入" }
                Files.writeString(backups!!.resolve(backup.fileName.toString() + ".txt"), target.toString())
            }
            // Tag writers may replace their own temp file; SPW reads creationTime as the library's addedTime.
            Files.getFileAttributeView(stage, java.nio.file.attribute.BasicFileAttributeView::class.java).setTimes(null, null, created)
            check(Files.readAttributes(stage, java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis() == created.toMillis()) { "无法保留原添加时间，已取消写入" }
            // Require atomic replacement: a locked file or unsupported filesystem leaves the original intact.
            replace(stage, target)
            return backup
        } finally { Files.deleteIfExists(stage) }
    }
    private fun absDuration(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.1
}
