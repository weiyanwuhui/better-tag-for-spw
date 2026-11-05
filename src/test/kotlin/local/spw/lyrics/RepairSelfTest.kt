@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.spw.lyrics

import androidx.compose.runtime.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import javax.swing.SwingUtilities

fun checkTagPlayback() {
    val root = Files.createTempDirectory(Path.of("build"), "playback-write-")
    fun exercise(locked: Boolean, stale: Boolean) {
        val original = root.resolve("song.flac"); val stage = root.resolve("stage.flac")
        Files.writeString(original, "original"); Files.writeString(stage, "updated")
        var channel: FileChannel? = if (locked) FileChannel.open(original) else null
        var released = 0; var restored = 0
        val driver = object : TagPlayback {
            override fun release(): Boolean { released++; channel?.close(); channel = null; return true }
            override fun isCurrent() = !stale
            override fun restore() { restored++ }
        }
        try {
            val result = runCatching { replaceTagFile(stage, original, driver) }
            check(result.isSuccess == (!stale || !locked))
            check(Files.readString(original) == if (stale && locked) "original" else "updated")
            check(released == if (locked) 1 else 0)
            check(restored == released)
        } finally { channel?.close() }
    }
    exercise(false, false); exercise(true, false); exercise(true, true)
    val shifted = LyricsCodec.render(LyricsCodec.parse("[00:01.000]a<00:01.500>b<00:02.000>"), SavedLyrics(offset = 200))
    check(shifted.rows.first().start == 1200L && shifted.rows.first().words[1].start == 1700L)
    println("PASS: active-file unlock, atomic replacement, playback restoration, stale-track cancellation and joint line/word calibration.")
}

fun checkCoverParsers() {
    val fixtures = mapOf(
        CoverSource.KUGOU to """{"status":1,"data":{"info":[{"albumname":"专辑","singername":"歌手","imgurl":"http://imge.kugou.com/{size}/cover.jpg"}]}}""",
        CoverSource.ITUNES to """{"results":[{"collectionName":"专辑","artistName":"歌手","artworkUrl100":"https://example.com/100x100bb.jpg"}]}""",
    )
    fixtures.forEach { (source, raw) ->
        val candidate = Covers.parse(source, Json.parseToJsonElement(raw)).single()
        check(candidate.title == "专辑" && candidate.artist == "歌手" && candidate.source == source.label && candidate.url.startsWith("https://"))
    }
    check(runCatching { Covers.parse(CoverSource.KUGOU, Json.parseToJsonElement("""{"status":0}""")) }.isFailure)
    val songs = mapOf(
        CoverSource.NETEASE to """{"songs":[{"name":"歌名","artists":[{"name":"歌手"}],"album":{"name":"专辑","picUrl":"http://example.com/ne.jpg"},"duration":1000}]}""",
        CoverSource.QQ to """{"search":{"code":0,"data":{"body":{"song":{"list":[{"title":"歌名","singer":[{"name":"歌手"}],"album":{"name":"专辑","mid":"ABC123"},"interval":1}]}}}}}""",
        CoverSource.KUGOU to """{"status":1,"data":{"info":[{"songname":"歌名","singername":"歌手","album_name":"专辑","duration":1,"trans_param":{"union_cover":"http://example.com/{size}/kg.jpg"}}]}}""",
        CoverSource.ITUNES to """{"results":[{"trackName":"歌名","artistName":"歌手","collectionName":"专辑","trackTimeMillis":1000,"artworkUrl100":"https://example.com/100x100bb.jpg"}]}"""
    )
    val parsed = songs.map { (source, raw) -> Covers.parseSongs(source, Json.parseToJsonElement(raw)).single().also {
        check(it.title == "歌名" && it.artist == "歌手" && it.album == "专辑" && it.duration == 1000L && it.url.startsWith("https://"))
    } }
    val target = SearchTarget("歌名", "歌手", "专辑", 1000)
    check(Covers.rank(parsed + parsed.first().copy(title = "其他歌", url = "https://example.com/other.jpg"), target).take(4).all { it.title == "歌名" })
    val shared = parsed.first().copy(title = "其他歌")
    check(Covers.rank(listOf(shared, parsed.first()), target).first().title == "歌名") { "URL deduplication kept a less relevant song for the same cover" }
    check(Covers.rank(listOf(parsed.first().copy(title = "歌名", album = "另一专辑"), parsed[1].copy(title = "其他歌")), target).first().title == "歌名")
    println("PASS: additional cover sources retain provider identity, artist, album and HTTPS artwork; source errors stay visible.")
}

fun checkNativeLyricLayout() {
    SwingUtilities.invokeAndWait {
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(600, 400))
        var checked = false
        try {
            scene.setContent {
                val measurer = rememberTextMeasurer()
                val style = TextStyle(fontSize = 24.sp)
                SideEffect {
                    val cell = HostNames.type("com.xuncorp.spc.lyrics.LyricsCell").constructors.single()
                    val line = HostNames.type("com.xuncorp.spc.lyrics.LyricsLine").constructors.single()
                    val layout = HostNames.type("com.xuncorp.spc.lyrics.LyricsLineLayoutResult").declaredConstructors.single { it.parameterCount == 11 }.apply { isAccessible = true }
                    fun measure(nativeLine: Any, text: String) {
                        val measured = measurer.measure(AnnotatedString(text), style)
                        layout.newInstance(measured, nativeLine, Density(1f), measurer, 0f, 0f, 0f, Color.Black.value.toLong(), style, Color.Black.value.toLong(), null)
                    }
                    val broken = line.newInstance(1000L, 2000L, listOf(cell.newInstance(1000L, 2000L, "abcdefgh"), cell.newInstance(2000L, 2000L, "")), null)
                    val cause = runCatching { measure(broken, "abcdefgh") }.exceptionOrNull()
                    check(cause is java.lang.reflect.InvocationTargetException && cause.cause?.message?.contains("offset(8)") == true) { "The exact native offset crash was not reproduced: $cause" }
                    for (text in listOf("[00:01.000]abcdefgh<00:02.000>", "[00:01.000]你<00:01.500>好<00:02.000>", "[00:01.000] ")) {
                        val native = NativeHost.decode(text)
                        NativeHost.applySettings(native, SavedLyrics(text = text, removeEmptyLines = false))
                        NativeHost.lines(native).forEach { row ->
                            val value = HostNames.resolver.getter(row.javaClass, "pureMainText").invoke(row) as String
                            check(value.isNotEmpty()); measure(row, value)
                        }
                    }
                    checked = true
                }
            }
            frame.performFrame(16_000_000); scene.measureAndLayout()
            check(checked)
        } finally { scene.close(); frame.close() }
    }
    println("PASS: exact offset(8) native glyph-layout crash reproduced; trailing empty word, Chinese words and blank-line preservation render after sanitization.")
}

fun checkLongTimeline() {
    SwingUtilities.invokeAndWait {
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(600, 240))
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(600, 240)
        var composed = 0
        try {
            scene.setContent {
                val scroll = remember { LazyListState(0, 0) }
                NativeUi.LazyRows(Modifier.fillMaxSize(), scroll, 20_000) { index ->
                    SideEffect { composed++ }
                    NativeUi.Text("[00:01.000]第 $index 行歌词", size = 14)
                }
            }
            repeat(5) { frame.performFrame((it + 1L) * 16_000_000); scene.measureAndLayout(); scene.draw(surface.canvas.asComposeCanvas()) }
            check(composed in 1..100) { "All $composed rows were eagerly composed" }
        } finally { scene.close(); frame.close(); surface.close() }
    }
    println("PASS: a 20,000-row timeline composes only visible rows; no full-document rendering on playback ticks.")
}
