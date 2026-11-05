package local.spw.lyrics

import java.nio.file.Files
import java.nio.file.Path

fun checkLyricsPersistence() {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "lyrics-persistence-")
    val external = listOf("lrc", "spl").associate { extension ->
        root.resolve("song.$extension") to "[00:00.000]外置歌词保持原样 $extension".toByteArray()
    }
    external.forEach { (path, bytes) -> Files.write(path, bytes) }
    val original = "[00:00.000]原来的歌词"
    val words = "[00:01.000]<00:01.000>新的<00:01.500>歌词<00:02.000>"
    val applied = SavedLyrics(words, "QQ 音乐", offset = 250, translation = "[00:01.000]translation", romanization = "[00:01.000]xin de ge ci")
    val expected = LyricsCodec.export(LyricsCodec.render(LyricsCodec.source(applied, ""), applied), LyricsFormat.AUTO)
    val parserType = Class.forName("com.xuncorp.voxzen.tag.TagParser")
    val parser = parserType.getField("INSTANCE").get(null)
    val vriType = Class.forName("com.xuncorp.spc.v.Vri")
    fun hostLyrics(path: Path): Any? {
        val vri = NativeHost.localVri.invoke(HostNames.resolver.receiver(NativeHost.localVri), path.toString())
        return parserType.getMethod("readLyrics-IoAF18A", vriType).invoke(parser, vri)
    }
    for (extension in listOf("flac", "mp3", "m4a", "mp4", "ogg", "wav", "opus", "aiff")) {
        val path = root.resolve("song.$extension")
        Files.copy(Path.of("src/test/resources/audio/tone.$extension"), path)
        TagFiles.write(path, emptyMap(), null, original, null)
        val before = Files.readAllBytes(path)
        val store = LyricsStore(root.resolve("cache-$extension"))
        // Old versions have only the applied override; the same save path migrates it into the file.
        store.save(extension, applied)
        check(LyricsPersistence.needsMigration(store.get(extension)))
        val saved = LyricsPersistence.apply(store, extension, path, root.resolve("backups-$extension"), { "" }, { it })
        check(saved.lyricsInFile && saved.originalText == original && !LyricsPersistence.needsMigration(saved))
        check(TagFiles.readLyrics(path) == expected)
        external.forEach { (sidecar, bytes) -> check(Files.readAllBytes(sidecar).contentEquals(bytes)) { "Applying lyrics changed an external lyric file" } }
        check(!expected.contains('●') && "translation" in expected && "xin de ge ci" in expected)
        check(LyricsCodec.parse(expected).rows.any { it.hasWords && it.words.first().start == 1250L && it.words.last().end == 2250L })
        check(LyricsStore(root.resolve("cache-$extension")).get(extension) == saved)
        check(Files.list(root.resolve("backups-$extension")).use { files -> files.filter { it.fileName.toString().endsWith(".$extension") }.anyMatch { Files.readAllBytes(it).contentEquals(before) } })
        val copied = root.resolve("other-device-$extension").also(Files::createDirectories).resolve("copy.$extension")
        Files.copy(path, copied)
        check(LyricsPlugin.active == null && LyricsPlayback().onBeforeLoadLyrics(com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem("曲", "歌手", "", "", copied.toString(), id = extension)) == null)
        check(hostLyrics(copied) == expected) { "SPW without the plugin did not read the copied file's embedded lyrics ($extension): ${hostLyrics(copied)}" }
        val unchanged = Files.readAllBytes(path)
        store.save(extension, saved.copy(lyricsInFile = false))
        check(LyricsPersistence.needsMigration(store.get(extension)))
        LyricsPersistence.apply(store, extension, path, root.resolve("backups-$extension"), { "" }, { it }, replace = { _, _ -> error("Unchanged lyrics were rewritten") })
        check(Files.readAllBytes(path).contentEquals(unchanged) && store.get(extension).lyricsInFile) { "Interrupted migration did not resume without rewriting the file" }
        LyricsPersistence.apply(store, extension, path, root.resolve("backups-$extension"), { "" }, {
            it.copy(text = null, translation = null, romanization = null, source = "", offset = 0)
        })
        check(TagFiles.readLyrics(path) == original) { "Restore original lyrics stopped working in $extension" }
    }
    val path = root.resolve("fail.flac")
    Files.copy(Path.of("src/test/resources/audio/tone.flac"), path)
    val before = Files.readAllBytes(path)
    val store = LyricsStore(root.resolve("failure-cache"))
    check(runCatching { LyricsPersistence.apply(store, "first", path, root.resolve("failure-backups"), { "" }, { applied }, replace = { _, _ -> error("locked") }) }.isFailure)
    check(!store.hasSaved("first") && store.get("first") == SavedLyrics() && Files.readAllBytes(path).contentEquals(before)) { "Failed first write left a successful-looking override" }
    store.save("existing", applied)
    var checks = 0
    check(runCatching { LyricsPersistence.apply(store, "existing", path, root.resolve("failure-backups"), { "" }, { it }, { checks++; check(checks < 2) { "song switched" } }) }.isFailure)
    check(store.get("existing") == applied && Files.readAllBytes(path).contentEquals(before)) { "Late permission/track rejection changed the cache or file" }
    var permitted = false
    check(runCatching { LyricsPersistence.apply(store, "denied", path, root.resolve("failure-backups"), { "" }, { applied }, { check(permitted) }) }.isFailure)
    check(!store.hasSaved("denied") && Files.readAllBytes(path).contentEquals(before))
    val calibrated = LyricsPersistence.apply(store, "offset", path, root.resolve("offset-backups"), { words }, { it.copy(offset = 100) })
    check(calibrated.text == words && calibrated.offset == 100L)
    val next = LyricsPersistence.apply(store, "offset", path, root.resolve("offset-backups"), { "wrong fallback" }, { it.copy(offset = 200) })
    check(LyricsCodec.parse(TagFiles.readLyrics(path)).rows.first().start == 1200L && next.text == words) { "Repeated calibration double-applied the embedded offset" }
    check(!LyricsPersistence.needsMigration(SavedLyrics(coverFile = "a".repeat(64) + ".png")))
    println("PASS: applied/legacy lyrics persist in 8 audio formats; copied files are read by real SPW TagParser with no active plugin; word times/auxiliary text, original restore, backups, no-op saves, offset once and file/cache rollback guards.")
}
