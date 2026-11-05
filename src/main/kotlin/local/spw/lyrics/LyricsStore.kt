package local.spw.lyrics

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SavedLyrics(val text: String? = null, val source: String = "", val offset: Long = 0,
    val translation: String? = null, val romanization: String? = null,
    val format: LyricsFormat = LyricsFormat.AUTO, val chinese: ChineseMode = ChineseMode.ORIGINAL,
    val showTranslation: Boolean = true, val showRomanization: Boolean = true, val removeEmptyLines: Boolean = true,
    val order: List<LyricsPart> = LyricsPart.entries, val coverFile: String? = null, val coverSource: String = "",
    val originalText: String? = null, val lyricsInFile: Boolean = false)

/** Editing data and display settings; applied lyrics are also committed through LyricsPersistence. */
class LyricsStore(private val directory: Path) {
    private val cache = ConcurrentHashMap<String, SavedLyrics>()

    fun get(id: String): SavedLyrics = cache.computeIfAbsent(id) {
        val path = path(id)
        if (!Files.exists(path)) SavedLyrics() else {
            require(Files.size(path) <= MAX_LYRICS_BYTES * 24L + 8192) { "保存的歌词过大" }
            val json = Json.parseToJsonElement(Files.readString(path)).jsonObject
            SavedLyrics(json["text"]?.jsonPrimitive?.contentOrNull,
                json["source"]?.jsonPrimitive?.content.orEmpty(),
                json["offset"]?.jsonPrimitive?.long ?: 0,
                json["translation"]?.jsonPrimitive?.contentOrNull,
                json["romanization"]?.jsonPrimitive?.contentOrNull,
                json["format"]?.jsonPrimitive?.content?.let(LyricsFormat::valueOf) ?: LyricsFormat.AUTO,
                json["chinese"]?.jsonPrimitive?.content?.let(ChineseMode::valueOf) ?: ChineseMode.ORIGINAL,
                json["showTranslation"]?.jsonPrimitive?.boolean ?: true,
                json["showRomanization"]?.jsonPrimitive?.boolean ?: true,
                json["removeEmptyLines"]?.jsonPrimitive?.boolean ?: true,
                json["order"]?.jsonArray?.map { LyricsPart.valueOf(it.jsonPrimitive.content) } ?: LyricsPart.entries,
                json["coverFile"]?.jsonPrimitive?.contentOrNull,
                json["coverSource"]?.jsonPrimitive?.content.orEmpty(),
                json["originalText"]?.jsonPrimitive?.contentOrNull,
                json["lyricsInFile"]?.jsonPrimitive?.boolean ?: false).also { validate(it) }
        }
    }

    @Synchronized
    fun save(id: String, value: SavedLyrics) {
        require(id.isNotBlank()) { "没有当前歌曲" }
        validate(value)
        Files.createDirectories(directory)
        val target = path(id)
        val temporary = Files.createTempFile(directory, "lyrics-", ".tmp")
        try {
            val json = buildJsonObject {
                put("text", value.text?.let(::JsonPrimitive) ?: JsonNull)
                put("source", value.source)
                put("offset", value.offset)
                put("translation", value.translation?.let(::JsonPrimitive) ?: JsonNull)
                put("romanization", value.romanization?.let(::JsonPrimitive) ?: JsonNull)
                put("format", value.format.name)
                put("chinese", value.chinese.name)
                put("showTranslation", value.showTranslation)
                put("showRomanization", value.showRomanization)
                put("removeEmptyLines", value.removeEmptyLines)
                put("order", buildJsonArray { value.order.forEach { add(it.name) } })
                put("coverFile", value.coverFile?.let(::JsonPrimitive) ?: JsonNull)
                put("coverSource", value.coverSource)
                put("originalText", value.originalText?.let(::JsonPrimitive) ?: JsonNull)
                put("lyricsInFile", value.lyricsInFile)
            }
            Files.writeString(temporary, json.toString())
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, REPLACE_EXISTING)
            }
            cache[id] = value
        } finally { Files.deleteIfExists(temporary) }
    }

    @Synchronized
    fun update(id: String, change: (SavedLyrics) -> SavedLyrics): SavedLyrics = change(get(id)).also { save(id, it) }

    fun hasSaved(id: String) = Files.isRegularFile(path(id))

    @Synchronized fun remove(id: String) { Files.deleteIfExists(path(id)); cache.remove(id) }

    val coversDirectory: Path get() = directory.resolve("covers")

    private fun path(id: String): Path {
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return directory.resolve("$key.json")
    }

    companion object {
        const val MAX_LYRICS_BYTES = 1_048_576
        fun validate(value: SavedLyrics) {
            require(value.offset in -60_000..60_000) { "时间偏移范围为前后 60 秒" }
            require((value.text?.toByteArray()?.size ?: 0) <= MAX_LYRICS_BYTES) { "歌词不能超过 1 MiB" }
            require(value.text == null || value.text.isNotBlank()) { "歌词为空" }
            require((value.originalText?.toByteArray()?.size ?: 0) <= MAX_LYRICS_BYTES) { "原歌词不能超过 1 MiB" }
            require(listOf(value.translation, value.romanization).all { (it?.toByteArray()?.size ?: 0) <= MAX_LYRICS_BYTES }) { "辅助歌词不能超过 1 MiB" }
            require(value.order.size == 3 && value.order.toSet() == LyricsPart.entries.toSet()) { "歌词内容顺序无效" }
            require(value.coverFile == null || value.coverFile.matches(Regex("[a-f0-9]{64}\\.png"))) { "封面文件名无效" }
        }
    }
}
