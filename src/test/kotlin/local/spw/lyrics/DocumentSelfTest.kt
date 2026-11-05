@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package local.spw.lyrics

import java.nio.file.Files
import java.nio.file.Path

const val TTML_FIXTURE = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
<p begin="00:01.001" end="00:03.500" ttm:agent="v1"><span begin="00:01.001" end="00:01.800">頭</span><span begin="00:01.800" end="00:03.500">髮</span><span ttm:role="x-translation">The hair &amp; sky</span><span ttm:role="x-roman">tou fa</span><span ttm:role="x-bg" begin="00:02.000" end="00:03.000"><span begin="00:02.000" end="00:02.500">和</span><span begin="00:02.500" end="00:03.000">聲</span></span></p>
<p begin="00:04.000" end="00:05.000"></p><p begin="00:06.000" end="00:08.000">君の知らない物語<span ttm:role="x-translation">你所不知道的故事</span><span ttm:role="x-roman">kimi no shiranai monogatari</span></p></div></body></tt>"""

fun checkDocuments() {
    check(LyricsCodec.milliseconds("00:01.001") == 1001L)
    check(LyricsCodec.milliseconds("01:01:01.001") == 3_661_001L)
    check(LyricsCodec.milliseconds("1.001s") == 1001L)
    val document = LyricsCodec.parse(TTML_FIXTURE)
    check(document.rows.size == 4 && document.hasWords && document.rows[0].start == 1001L)
    check(document.rows[0].text == "頭髮" && document.rows[0].translation == "The hair & sky" && document.rows[0].romanization == "tou fa")
    check(document.rows[1].background && document.rows[1].text == "和聲")
    val roundTrip = LyricsCodec.parse(LyricsCodec.ttml(document))
    check(roundTrip.rows == document.rows) { "TTML export lost word times, auxiliary tracks, background vocals or agents" }
    for (format in listOf(LyricsFormat.WORD, LyricsFormat.ENHANCED)) {
        val text = LyricsCodec.export(document.copy(rows = listOf(document.rows.first())), format)
        val parsed = LyricsCodec.parse(text)
        check(parsed.rows.first().words == document.rows.first().words) { "Word timestamps changed in $format" }
        check(parsed.rows.first().translation == document.rows.first().translation)
    }
    val line = LyricsCodec.parse(LyricsCodec.export(document.copy(rows = listOf(document.rows.first())), LyricsFormat.LINE))
    check(!line.hasWords && line.rows.first().text == "頭髮")
    check(LyricsCodec.parse("[00:12:23]line").rows.single().start == 12_230L)
    check(LyricsCodec.parse("[00:01.00][00:05.00]repeat").rows.map { it.start } == listOf(1000L, 5000L))
    val yrc = LyricsCodec.parse("[1000,1000](1000,400,0)hello(1400,600,0) world")
    check(yrc.hasWords && yrc.rows.single().words[1] == LyricWord(1400, 2000, " world"))
    val language = java.util.Base64.getEncoder().encodeToString("""{"content":[{"type":0,"lyricContent":[["hello","world"]]},{"type":1,"lyricContent":[["你好世界"]]}]}""".toByteArray())
    val krc = "[language:$language]\n[1000,1000]<0,400,0>hello<400,600,0> world"
    val parsedKrc = LyricsCodec.parse(krc)
    check(parsedKrc.hasWords && parsedKrc.rows.single().words == yrc.rows.single().words)
    check(parsedKrc.rows.single().translation == "你好世界" && parsedKrc.rows.single().romanization == "hello world")
    fun compressedKrc(text: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(output).use { it.write(text.toByteArray()) }
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        return "krc1".toByteArray() + output.toByteArray().mapIndexed { index, byte -> (byte.toInt() xor key[index % key.size]).toByte() }.toByteArray()
    }
    check(WordSources.krc(compressedKrc(krc)) == krc)
    check(runCatching { WordSources.krc(compressedKrc("x".repeat(LyricsStore.MAX_LYRICS_BYTES + 1))) }.isFailure)
    check(runCatching { WordSources.krc("not krc".toByteArray()) }.isFailure)
    val wordDownload = DownloadedLyrics(krc, "test")
    val lineDownload = DownloadedLyrics("[00:01.00]line", "test")
    for (format in listOf(LyricsFormat.WORD, LyricsFormat.ENHANCED)) {
        check(wordDownload.matches(format) && !lineDownload.matches(format)) { "Line-only data passed the word-lyrics filter" }
    }
    check(!wordDownload.matches(LyricsFormat.TTML) && DownloadedLyrics(TTML_FIXTURE, "test").matches(LyricsFormat.TTML))
    check(LyricSource.QQ in LyricSources.available(LyricsFormat.WORD))
    val qrc = """<QrcInfos><LyricInfo><Lyric_1 LyricType="1" LyricContent="[ti:test]
[5000,1000]你(5000,400)好 &amp; &quot;他&quot;(5400,600)
[7000,1000]再(7000,300)见(7300,700)"/></LyricInfo></QrcInfos>"""
    val qrcDocument = LyricsCodec.parse(qrc)
    check(qrcDocument.rows.size == 2 && qrcDocument.hasWords && qrcDocument.rows[0].text == "你好 & \"他\"")
    check(qrcDocument.rows[0].words[1].start == 5400L) { "QRC word times were interpreted as relative offsets" }
    val qrcRawCharacters=qrc.replace("好 &amp; &quot;他&quot;", "好 & \"他\"")
    check(LyricsCodec.parse(qrcRawCharacters).rows[0].text == "你好 & \"他\"") { "QQ's raw quotes and ampersands hid otherwise valid word lyrics" }
    check(LyricsCodec.parse(LyricsCodec.export(qrcDocument, LyricsFormat.WORD)).rows[0].words == qrcDocument.rows[0].words)
    check(LyricsCodec.parse(LyricsCodec.ttml(qrcDocument)).rows == qrcDocument.rows)
    val auxiliaryQrc = """<QrcInfos><LyricInfo><Lyric_1 LyricType="1" LyricContent="[00:05.000]translation
[00:07.000]next"/></LyricInfo></QrcInfos>"""
    check(LyricsCodec.withAuxiliary(qrcDocument, auxiliaryQrc, auxiliaryQrc).rows[0].let { it.translation == "translation" && it.romanization == "translation" })
    check(runCatching { LyricsCodec.parse(qrc.replace("<QrcInfos>", "<!DOCTYPE QrcInfos [<!ENTITY x SYSTEM 'file:///nonexistent'>]><QrcInfos>")) }.isFailure)
    val encryptedQrc = Files.readString(Path.of("src/test/fixtures/qq-aicuo.qrc.hex")).trim()
    val decryptedQrc = QrcCrypto.decrypt(encryptedQrc)
    val actualHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(decryptedQrc.toByteArray()))
    val expectedHash = Files.readString(Path.of("src/test/fixtures/qq-aicuo.qrc.sha256")).trim().lowercase()
    check(actualHash == expectedHash) { "QQ wire-cipher fixture did not decode byte-for-byte" }
    check(LyricsCodec.parse(decryptedQrc).rows.first { it.text == "北风毫不留情" }.words.size == 6)
    check(runCatching { QrcCrypto.decrypt("0".repeat(15)) }.isFailure)
    check(runCatching { QrcCrypto.decrypt("G".repeat(16)) }.isFailure)
    check(runCatching { QrcCrypto.decrypt("0".repeat(32)) }.isFailure)
    println("PASS: real QQ encrypted QRC fixture, absolute word timing, attribute newlines/entities, XML safety, auxiliary tracks and word/TTML export.")
    val song = Candidate(LyricSource.KUGOU, "song-hash", "爱错", "王力宏", duration = 238000)
    check(LyricSources.kugouMatches(song, "愛錯", "王力宏", 238863))
    check(!LyricSources.kugouMatches(song, "爱错", "王力宏", 34000)) { "A short clip passed the song-version check" }
    check(!LyricSources.kugouMatches(song, "寂寞的季节", "陶喆", 238000))
    check(runCatching { LyricSources.validateKugouContent(song, "[ti:寂寞的季节]\n[ar:陶喆]\n[00:14]北风毫不留情") }.isFailure)
    check(runCatching { LyricSources.validateKugouContent(song, "[ti:爱错 (DJ版)]\n[ar:王力宏]") }.isFailure)
    LyricSources.validateKugouContent(song, "[ti:愛錯]\n[ar:王力宏]")
    val colors = NativeUi.Palette(androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Black,
        androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.White,
        androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Red)
    val beforeWord = previewWords(document.rows[0], true, 1500, colors)
    val afterWord = previewWords(document.rows[0], true, 1900, colors)
    check(beforeWord.text == document.rows[0].text && beforeWord.spanStyles[1].item.color == colors.text)
    check(afterWord.spanStyles[1].item.color == colors.accent) { "The preview did not follow actual word timestamps" }
    check(activeLyricRow(document, 2200) == 0 && activeLyricRow(document, 6500) == 3) { "Background vocals replaced the active lead line" }
    check(!LyricSources.sourceLabel(LyricSource.QQ, LyricsFormat.WORD).contains("仅逐行"))
    check(LyricSource.LRCLIB !in LyricSources.available(LyricsFormat.WORD) && LyricSource.LRCLIB in LyricSources.available(LyricsFormat.LINE))
    println("PASS: Kugou song identity, duration and content mismatch rejection; actual word-time preview and lead-line tracking.")
    val options = SavedLyrics(TTML_FIXTURE, chinese = ChineseMode.SIMPLIFIED, offset = -2000, showRomanization = false)
    val originalOnly = wordDownload.applyTo(options, LyricsPart.ORIGINAL, LyricsFormat.WORD, "")
    check(originalOnly.offset == -2000L && originalOnly.chinese == ChineseMode.SIMPLIFIED && originalOnly.format == LyricsFormat.WORD)
    check(originalOnly.translation?.contains("The hair & sky") == true)
    check(LyricsCodec.parse(originalOnly.text!!).rows.first().translation.isEmpty())
    val translationOnly = wordDownload.applyTo(options, LyricsPart.TRANSLATION, LyricsFormat.WORD, "")
    check(translationOnly.text == options.text && translationOnly.format == options.format && translationOnly.translation?.contains("你好世界") == true)
    val rendered = LyricsCodec.render(document, options)
    check(rendered.rows.size == 3 && rendered.rows.first().text == "头发")
    check(rendered.rows.first().start == -999L && rendered.rows.first().words[0].end == -200L)
    check(rendered.rows.first().romanization.isEmpty() && document.rows.first().romanization == "tou fa")
    check(ChineseConverter.convert("头发干燥，音乐很好听", ChineseMode.TRADITIONAL) == "頭髮乾燥，音樂很好聽")
    val native = NativeHost.decode("[00:01.00]placeholder")
    NativeHost.applySettings(native, options)
    NativeHost.applySettings(native, options)
    val model = NativeHost.model(native)
    check(model.rows[0].start == -999L && model.rows[0].text == "头发" && model.rows[0].words.size == 2)
    NativeHost.applySettings(native, options.copy(format = LyricsFormat.LINE))
    check(NativeHost.model(native).rows[0].words.size == 1)
    NativeHost.applySettings(native, options.copy(order = listOf(LyricsPart.TRANSLATION, LyricsPart.ORIGINAL, LyricsPart.ROMANIZATION)))
    check(NativeHost.model(native).rows[0].text == "The hair & sky")
    val auxiliary = LyricsCodec.withAuxiliary(line, "[00:01.001]新翻译", "tou fa")
    check(auxiliary.rows[0].translation == "新翻译" && auxiliary.rows[0].romanization == "tou fa")
    var blocked = false
    try { LyricsCodec.parse("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///nonexistent'>]><tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='1s' end='2s'>&x;</p></div></body></tt>") }
    catch (_: Exception) { blocked = true }
    check(blocked)
    val directory = Files.createTempDirectory(Path.of("build"), "v2-store-")
    Files.write(directory.resolve("sample.krc"), compressedKrc(krc))
    check(LyricsFiles.read(directory.resolve("sample.krc")) == krc)
    val store = LyricsStore(directory)
    store.save("track", options)
    store.update("track") { it.copy(coverFile = "a".repeat(64) + ".png", coverSource = "test") }
    store.update("track") { it.copy(showTranslation = false, format = LyricsFormat.WORD) }
    val reloaded = LyricsStore(directory).get("track")
    check(reloaded.text == TTML_FIXTURE && reloaded.coverFile == "a".repeat(64) + ".png" && !reloaded.showTranslation && reloaded.format == LyricsFormat.WORD)
    LyricsFiles.write(directory.resolve("export.ttml"), LyricsCodec.ttml(document))
    check(LyricsCodec.parse(LyricsFiles.read(directory.resolve("export.ttml"))).rows == document.rows)
    println("PASS: TTML/YRC/LRC round trips, real word timing, OpenCC phrases, native render, independent settings, safe XML and atomic export.")
}

fun sampleCover(): CoverPicture {
    val surface = checkNotNull(org.jetbrains.skia.Surface.makeRasterN32Premul(300, 300))
    try {
        surface.canvas.clear(0xff284c68.toInt())
        val paint = org.jetbrains.skia.Paint()
        try { paint.color = 0xfff3cf88.toInt(); surface.canvas.drawCircle(150f, 150f, 87f, paint) } finally { paint.close() }
        val image = surface.makeImageSnapshot()
        try { val data = checkNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)); try { return Covers.normalize(data.bytes, "测试封面") } finally { data.close() } }
        finally { image.close() }
    } finally { surface.close() }
}

@OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
fun checkCovers() {
    val picture = sampleCover()
    check(picture.width == 300 && picture.height == 300)
    val thumbnail = Covers.normalize(picture.bytes, "thumbnail", 160)
    check(thumbnail.width == 160 && thumbnail.height == 160)
    val directory = Files.createTempDirectory(Path.of("build"), "covers-")
    val file = Covers.persist(directory, picture)
    check(file.matches(Regex("[a-f0-9]{64}\\.png")) && Files.exists(directory.resolve(file)))
    check(Covers.persist(directory, picture) == file && Covers.file(directory.resolve(file)).width == 300)
    var rejected = false
    try { Covers.normalize("invalid image".toByteArray(), "bad") } catch (_: Exception) { rejected = true }
    check(rejected)
    val trackStore = LyricsStore(directory.resolve("tracks"))
    val stored = Covers.persist(trackStore.coversDirectory, picture)
    trackStore.save("track", SavedLyrics(coverFile = stored))
    val plugin = LyricsPlugin(com.xuncorp.spw.workshop.api.PluginContext("local.spw.lyrics", "test", ".", "1.19.0", com.xuncorp.spw.workshop.api.Channel.Steam))
    LyricsPlugin::class.java.getDeclaredField("store").apply { isAccessible = true }.set(plugin, trackStore)
    val controller = CoverController(plugin)
    var currentId = "track"
    val hook = controller.publicationHook { currentId }
    val monitor = Class.forName("com.xuncorp.voxzen.service.PlaybackMonitor").getField("INSTANCE").get(null)
    val publisher = Class.forName("com.xuncorp.voxzen.service.PlaybackMonitorKt").getMethod("updateImageBitmap", monitor.javaClass, com.xuncorp.voxzen.image.RefCountedImageBitmap::class.java)
    val flow = Class.forName("com.xuncorp.voxzen.service.PlaybackMonitorKt").getMethod("getImageBitmap", monitor.javaClass).invoke(null, monitor) as kotlinx.coroutines.flow.SharedFlow<*>
    fun publishOriginal(original: Any?) {
        val call = object : com.xuncorp.spw.workshop.api.hook.MethodHookParam() {
            override val method = publisher
            override val thisObject: Any? = null
            override val args = arrayOf(monitor, original)
            override val result: Any? = null
            override val throwable: Throwable? = null
            override val isOriginalSkipped = false
            override fun setResult(result: Any?) { error("Cover hook must not replace a discarded result") }
            override fun setThrowable(throwable: Throwable) { throw throwable }
            override fun invokeOriginal(): Any? = publisher.invoke(null, monitor, original)
        }
        hook.before(call)
        // Match the installed Advice: the original arguments survive, even if call.args was replaced.
        publisher.invoke(null, monitor, original)
        hook.after(call)
    }
    try {
        val owner = controller.override("track", null) as com.xuncorp.voxzen.image.RefCountedImageBitmap
        val reference = checkNotNull(owner.acquire())
        try {
            publishOriginal(null)
            check(flow.replayCache.last() === owner)
            publishOriginal(null)
            check(flow.replayCache.last() === owner) { "Native cover refresh overwrote the custom cover" }
            trackStore.update("track") { it.copy(coverFile = null) }
            publishOriginal(null)
            check(flow.replayCache.last() == null && reference.bitmap.width == 300)
            trackStore.update("track") { it.copy(coverFile = stored) }
            publishOriginal(null)
            check(flow.replayCache.last() != null)
            currentId = "other-track"; publishOriginal(null)
            check(flow.replayCache.last() == null) { "A different song inherited the previous custom cover" }
        } finally { reference.close() }
    } finally { controller.close() }
    println("PASS: cover validation/persistence; after-hook refresh with discarded argument replacements; song isolation and safe renderer references.")
}
