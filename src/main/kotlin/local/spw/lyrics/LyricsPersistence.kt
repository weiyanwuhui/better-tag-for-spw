package local.spw.lyrics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

/** Applied lyrics belong to the audio file; the store retains editing data and the original lyrics. */
object LyricsPersistence {
    private fun content(value: SavedLyrics) = value.copy(source = "", coverFile = null, coverSource = "", originalText = null, lyricsInFile = false)
    fun needsMigration(value: SavedLyrics) = !value.lyricsInFile && content(value) != SavedLyrics()

    fun apply(store: LyricsStore, id: String, path: Path, backups: Path, fallback: () -> String,
        change: (SavedLyrics) -> SavedLyrics, checkWrite: () -> Unit = {},
        replace: (Path, Path) -> Unit = { stage, target -> Files.move(stage, target, ATOMIC_MOVE, REPLACE_EXISTING); Unit }): SavedLyrics = synchronized(TagFiles) {
        val existed = store.hasSaved(id)
        val before = store.get(id)
        val requested = change(before)
        LyricsStore.validate(requested)
        if (content(before) == content(requested) && !needsMigration(requested)) {
            store.save(id, requested)
            return@synchronized requested
        }
        checkWrite()
        val embedded = TagFiles.readLyrics(path)
        val original = before.originalText ?: embedded
        val restoring = before.text != null && requested.text == null && requested.source.isEmpty()
        val source = if (restoring) original else requested.text ?: embedded.ifBlank(fallback)
        val next = requested.copy(text = source.takeIf(String::isNotBlank), originalText = original, lyricsInFile = true)
        val lyrics = if (restoring) original else LyricsCodec.export(LyricsCodec.render(LyricsCodec.source(next, source), next), LyricsFormat.AUTO, next.order)
        // Save editing data first; a rejected file write rolls it back instead of reporting a cache-only success.
        store.save(id, next.copy(lyricsInFile = false))
        try {
            if (lyrics != embedded) TagFiles.write(path, emptyMap(), null, lyrics, backups) { stage, target -> checkWrite(); replace(stage, target) }
        } catch (failure: Throwable) {
            try { if (existed) store.save(id, before) else store.remove(id) }
            catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
        // A crash before file replacement must leave the record eligible for migration on restart.
        try { store.save(id, next) }
        catch (failure: Exception) { throw IllegalStateException("歌词已写入音频文件，但编辑设置保存失败，重新启用插件可继续同步", failure) }
        next
    }
}
