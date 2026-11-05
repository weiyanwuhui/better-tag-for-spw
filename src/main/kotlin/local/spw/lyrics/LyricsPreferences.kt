package local.spw.lyrics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.Properties

data class LyricsPreferences(
    val format: LyricsFormat = LyricsFormat.WORD,
    val source: LyricSource? = null,
    val enabled: Set<LyricSource> = LyricSource.entries.toSet(),
    val exportFormat: LyricsFormat = LyricsFormat.AUTO,
    val chinese: ChineseMode = ChineseMode.ORIGINAL,
    val translation: Boolean = true,
    val romanization: Boolean = true,
    val removeEmpty: Boolean = true,
    val order: List<LyricsPart> = LyricsPart.entries,
    val tagFields: Set<MusicTag> = MusicTag.entries.toSet(),
    val tagCover: Boolean = true,
    val tagLyrics: Boolean = true,
    val coverSources: Set<CoverSource> = CoverSource.entries.toSet(),
    val autoTags: AutoTagSettings = AutoTagSettings(),
    val interludeCountdown: Boolean = false,
    val countdown: CountdownSettings = CountdownSettings(),
    val entry: LyricsEntry = LyricsEntry.CONTROLS
) {
    fun applyTo(value: SavedLyrics) = applyContentTo(value).copy(format = format)
    fun applyContentTo(value: SavedLyrics) = value.copy(chinese = chinese,
        showTranslation = translation, showRomanization = romanization, removeEmptyLines = removeEmpty, order = order)
}

class LyricsPreferencesStore(private val path: Path, private val saved: (LyricsPreferences) -> Unit = {}) {
    fun load(): LyricsPreferences {
        if (!Files.exists(path)) return LyricsPreferences()
        require(Files.size(path) < 16_384) { "歌词设置文件过大" }
        val p = Properties().apply { Files.newInputStream(path).use { load(it) } }
        return LyricsPreferences(
            p.getProperty("format")?.let(LyricsFormat::valueOf) ?: LyricsFormat.WORD,
            p.getProperty("source")?.takeIf { it.isNotEmpty() }?.let(LyricSource::valueOf),
            p.getProperty("enabled")?.split(',')?.map(LyricSource::valueOf)?.toSet() ?: LyricSource.entries.toSet(),
            p.getProperty("exportFormat")?.let(LyricsFormat::valueOf) ?: LyricsFormat.AUTO,
            p.getProperty("chinese")?.let(ChineseMode::valueOf) ?: ChineseMode.ORIGINAL,
            p.getProperty("translation", "true").toBooleanStrict(),
            p.getProperty("romanization", "true").toBooleanStrict(),
            p.getProperty("removeEmpty", "true").toBooleanStrict(),
            p.getProperty("order")?.split(',')?.map(LyricsPart::valueOf) ?: LyricsPart.entries,
            p.getProperty("tagFields")?.split(',')?.filter { it.isNotEmpty() }?.map(MusicTag::valueOf)?.toSet() ?: MusicTag.entries.toSet(),
            p.getProperty("tagCover", "true").toBooleanStrict(), p.getProperty("tagLyrics", "true").toBooleanStrict(),
            p.getProperty("coverSources")?.split(',')?.map(CoverSource::valueOf)?.toSet() ?: CoverSource.entries.toSet(),
            AutoTagSettings(p.getProperty("auto.enabled", "false").toBooleanStrict(),
                p.getProperty("auto.mode")?.let(AutoTagMode::valueOf) ?: AutoTagMode.COMPLETE,
                p.getProperty("auto.sources")?.split(',')?.filter(String::isNotEmpty)?.map(LyricSource::valueOf)?.toSet() ?: AUTO_TAG_SOURCES,
                p.getProperty("auto.fields")?.split(',')?.filter(String::isNotEmpty)?.map(MusicTag::valueOf)?.toSet() ?: MusicTag.entries.toSet(),
                p.getProperty("auto.cover", "true").toBooleanStrict(), p.getProperty("auto.concurrency", "32").toInt()),
            p.getProperty("interludeCountdown", "false").toBooleanStrict(),
            CountdownSettings(p.getProperty("countdown.leadMillis", "3000").toLong(), p.getProperty("countdown.gapMillis", "8000").toLong(),
                CountdownAlignment.valueOf(p.getProperty("countdown.alignment", "FOLLOW"))),
            LyricsEntry.valueOf(p.getProperty("entry", "CONTROLS"))
        ).also(::validate)
    }
    @Synchronized fun save(value: LyricsPreferences) {
        validate(value)
        Files.createDirectories(path.parent)
        val p = Properties().apply {
            setProperty("auto.enabled", value.autoTags.enabled.toString()); setProperty("auto.mode", value.autoTags.mode.name)
            setProperty("entry", value.entry.name)
            setProperty("auto.concurrency", value.autoTags.concurrency.toString())
            setProperty("interludeCountdown", value.interludeCountdown.toString())
            setProperty("countdown.leadMillis", value.countdown.leadMillis.toString()); setProperty("countdown.gapMillis", value.countdown.gapMillis.toString())
            setProperty("countdown.alignment", value.countdown.alignment.name)
            setProperty("auto.sources", value.autoTags.sources.joinToString(",") { it.name }); setProperty("auto.fields", value.autoTags.fields.joinToString(",") { it.name }); setProperty("auto.cover", value.autoTags.cover.toString())
            setProperty("format", value.format.name); setProperty("source", value.source?.name ?: "")
            setProperty("enabled", value.enabled.joinToString(",") { it.name })
            setProperty("exportFormat", value.exportFormat.name); setProperty("chinese", value.chinese.name)
            setProperty("translation", value.translation.toString()); setProperty("romanization", value.romanization.toString())
            setProperty("removeEmpty", value.removeEmpty.toString()); setProperty("order", value.order.joinToString(",") { it.name })
            setProperty("coverSources", value.coverSources.joinToString(",") { it.name })
            setProperty("tagFields", value.tagFields.joinToString(",") { it.name }); setProperty("tagCover", value.tagCover.toString()); setProperty("tagLyrics", value.tagLyrics.toString())
        }
        val temporary = Files.createTempFile(path.parent, "preferences-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { p.store(it, "Lyrics preferences") }
            try { Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        saved(value)
    }
    private fun validate(value: LyricsPreferences) {
        require(value.autoTags.sources.size >= 2 && value.autoTags.sources.all { it in AUTO_TAG_SOURCES }) { "后台整理至少保留两个标签来源" }
        require(value.autoTags.fields.isNotEmpty() || value.autoTags.cover) { "至少选择一项整理内容" }
        require(value.coverSources.isNotEmpty()) { "至少保留一个封面来源" }
        require(value.enabled.isNotEmpty()) { "至少保留一个歌词来源" }
        require(value.source == null || value.source in value.enabled) { "默认来源未启用" }
        require(value.order.size == 3 && value.order.toSet() == LyricsPart.entries.toSet()) { "歌词内容顺序无效" }
    }
}
