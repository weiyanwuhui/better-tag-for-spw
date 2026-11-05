package local.spw.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path

fun checkTagFiles() {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "tag-check-")
    val encoder = Path.of(System.getProperty("spw.tools.path", "../开发资料/tools"), "ffmpeg-test", "ffmpeg.exe").toAbsolutePath()
    fun pcm(path: Path): String {
        val process = ProcessBuilder(encoder.toString(), "-v", "error", "-i", path.toString(), "-map", "0:a:0", "-f", "hash", "-hash", "sha256", "-").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }; return output.trim()
    }
    val values = mapOf(MusicTag.TITLE to "测试歌曲", MusicTag.ARTIST to "歌手甲 / 歌手乙", MusicTag.ALBUM to "测试专辑", MusicTag.YEAR to "2026", MusicTag.TRACK to "3", MusicTag.COMPOSER to "作曲者")
    val lyrics = "[00:00.000]你<00:00.500>好"
    for (extension in listOf("flac", "mp3", "m4a", "mp4", "ogg", "wav", "opus", "aiff")) {
        val original = Path.of("src/test/resources/audio/tone.$extension")
        check(Files.exists(original)) { "Missing generated audio fixture: $extension" }
        val target = root.resolve("测试.$extension"); Files.copy(original, target)
        val created = java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000L + extension.length * 1000)
        val modified = java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000L)
        Files.getFileAttributeView(target, java.nio.file.attribute.BasicFileAttributeView::class.java).setTimes(modified, null, created)
        val before = Files.readAllBytes(target); val originalAudio = pcm(target)
        val backup = checkNotNull(TagFiles.write(target, values, sampleCover(), lyrics, root.resolve("backups")))
        check(Files.readAllBytes(backup).contentEquals(before)) { "Backup is not the original $extension" }
        val audio = AudioFileIO.read(target.toFile())
        check(Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java).creationTime() == created) { "Added time changed in $extension" }
        check(Files.getLastModifiedTime(target) != modified) { "Modified time was incorrectly frozen in $extension" }
        val vri = NativeHost.localVri.invoke(HostNames.resolver.receiver(NativeHost.localVri), target.toString())
        val readerType = Class.forName("com.xuncorp.voxzen.tag.reader.JAudioTaggerTagReader")
        val readByHost = readerType.getMethod("getAudioTag-IoAF18A", Class.forName("com.xuncorp.spc.v.Vri")).invoke(readerType.getConstructor().newInstance(), vri)
        check(readByHost.javaClass.getMethod("getAddedTime").invoke(readByHost) == created.toMillis()) { "SPW reread changed addedTime in $extension" }
        val tag = (audio.tag as? org.jaudiotagger.tag.wav.WavTag)?.getID3Tag() ?: audio.tag
        values.forEach { (field, value) -> check(tag.getFirst(field.field) == value) }
        check(tag.getFirst(FieldKey.GENRE) == "TestGenre") { "Unselected genre changed in $extension" }
        check(tag.getFirst(FieldKey.LYRICS) == lyrics)
        check(TagFiles.readLyrics(target) == lyrics) { "Embedded preview reader lost lyrics in $extension" }
        check(pcm(target) == originalAudio) { "Audio samples changed in $extension" }
        val written = Files.readAllBytes(target)
        check(runCatching { TagFiles.write(target, mapOf(MusicTag.YEAR to "invalid"), null, null, root.resolve("backups")) }.isFailure)
        check(Files.readAllBytes(target).contentEquals(written))
        java.nio.channels.FileChannel.open(target).use {
            check(runCatching { TagFiles.write(target, mapOf(MusicTag.TITLE to "locked"), null, null, root.resolve("backups")) }.isFailure) { "Locked target was overwritten" }
            check(Files.readAllBytes(target).contentEquals(written))
        }
    }
    val noisy = java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val random = java.util.Random(1)
    for (y in 0 until 256) for (x in 0 until 256) noisy.setRGB(x, y, random.nextInt(0x1000000))
    val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(noisy, "png", it) }.toByteArray()
    val largeCover = Covers.normalize(png, "large Opus cover")
    check(largeCover.bytes.size > 65_025)
    for (fixture in listOf("tone.opus", "tone-short.opus")) {
        val original = Path.of("src/test/resources/audio/$fixture")
        val target = root.resolve("large-$fixture"); Files.copy(original, target)
        val before = pcm(target)
        TagFiles.write(target, values, largeCover, lyrics, root.resolve("backups"))
        check(TagFiles.read(target).cover?.bytes?.contentEquals(largeCover.bytes) == true)
        check(pcm(target) == before) { "Opus audio changed when tags spanned multiple pages" }
    }
    check(runCatching { TagFiles.localPath("https://example.com/song.mp3") }.isFailure)
    check(runCatching { TagFiles.localPath("relative.mp3") }.isFailure)
    val parser = TagSources()
    val ne = parser.netease(Json.parseToJsonElement("""{"songs":[{"name":"歌名","artists":[{"name":"歌手"}],"album":{"name":"专辑"},"position":2,"publishTime":1767225600000}]}"""))
    check(ne.values[MusicTag.TITLE] == "歌名" && ne.values[MusicTag.YEAR] == "2026" && ne.values[MusicTag.TRACK] == "2")
    val qq = parser.qq(Json.parseToJsonElement("""{"songinfo":{"data":{"track_info":{"title":"歌名","singer":[{"name":"歌手"}],"album":{"name":"专辑"},"time_public":"2025-01-02","index_album":3,"index_cd":1}}}}"""))
    check(qq.values[MusicTag.YEAR] == "2025" && qq.values[MusicTag.TRACK] == "3" && qq.values[MusicTag.DISC] == "1")
    val kg = parser.kugou(Json.parseToJsonElement("""{"data":{"song_name":"歌名","author_name":"歌手","album_name":"专辑","publish_date":"2024-02-03"}}"""))
    check(kg.values[MusicTag.YEAR] == "2024")
    var downloads = 0
    val picture = sampleCover()
    val cached = TagSources(readPicture = { downloads++; picture })
    fun coverSong(id: Int) = Json.parseToJsonElement("""{"songs":[{"name":"歌曲$id","album":{"name":"专辑","picUrl":"http://example.com/$id.png"}}]}""")
    check(cached.netease(coverSong(0)).cover === picture)
    check(cached.netease(coverSong(0)).cover === picture && downloads == 1) { "Shared album artwork downloaded twice" }
    for (id in 1..18) cached.netease(coverSong(id))
    cached.netease(coverSong(18)); check(downloads == 19)
    cached.netease(coverSong(0)); check(downloads == 20) { "Artwork cache did not evict old entries" }
    var largeDownloads = 0
    val large = TagSources(readPicture = { largeDownloads++; picture.copy(bytes = ByteArray(11 * 1024 * 1024)) })
    large.netease(coverSong(1)); large.netease(coverSong(2)); large.netease(coverSong(1))
    check(largeDownloads == 3) { "Artwork cache exceeded its 20 MiB bound" }
    println("PASS: FLAC/MP3/M4A/MP4/OGG/WAV/Opus/AIFF tag+cover+lyrics writes, byte-exact backups, untouched fields, unchanged decoded PCM, invalid/locked-file safety and platform tag parsers.")
}

fun checkTagNetwork() = runBlocking {
    for (source in listOf(LyricSource.NETEASE, LyricSource.QQ, LyricSource.KUGOU)) {
        try {
            val candidates = LyricSources().search(source, "有谁还没睡", "周传雄")
            val candidate = SearchRanking.rank(candidates, SearchTarget("有谁还没睡", "周传雄", "蓝色土耳其", 283000)).first().candidate
            val tags = TagSources().fetch(candidate)
            println("${source.name}: ${candidate.title} / ${candidate.artist}; fields=${tags.values.keys}; cover=${tags.cover != null}; ${tags.notice}")
            check(tags.values[MusicTag.TITLE]?.isNotBlank() == true)
        } catch (failure: Exception) { println("${source.name}: ${failure.javaClass.simpleName}: ${failure.message}") }
    }
}
