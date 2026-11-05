@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

fun checkCountdownRenderer() {
    SwingUtilities.invokeAndWait {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val frame = FrameRecomposer(Dispatchers.Unconfined + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors.add(e) })
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(600, 400))
        val surface = Surface.makeRasterN32Premul(600, 400)
        val output = Path.of("build/previews/countdown").also(Files::createDirectories)
        val row = LyricRow(4000, 10000, listOf(LyricWord(4000, 10000, "● ● ●")))
        var time by mutableLongStateOf(8200)
        var hostTime by mutableLongStateOf(3500)
        var settings by mutableStateOf(CountdownSettings())
        var font by mutableFloatStateOf(48f)
        var fontScale by mutableFloatStateOf(1f)
        var compositions = 0
        var tick = 0L
        fun draw(): ByteArray {
            repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                frame.performFrame(++tick * 16_000_000); scene.measureAndLayout()
                surface.canvas.clear(org.jetbrains.skia.Color.WHITE)
                scene.draw(surface.canvas.asComposeCanvas())
            }
            check(errors.isEmpty()) { "Countdown renderer failed: ${errors.firstOrNull()}" }
            return surface.makeImageSnapshot().use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { it.bytes } }
        }
        fun bounds(bytes: ByteArray): IntRange? {
            val image = javax.imageio.ImageIO.read(bytes.inputStream())
            val columns = (0 until image.width).filter { x -> (0 until image.height).any { y ->
                val color = java.awt.Color(image.getRGB(x, y)); color.blue > color.red + 40 && color.blue > color.green + 20
            } }
            return if (columns.isEmpty()) null else columns.first()..columns.last()
        }
        fun inkHeight(bytes: ByteArray): Int {
            val image = javax.imageio.ImageIO.read(bytes.inputStream())
            val rows = (0 until image.height).filter { y -> (0 until image.width).any { x ->
                val color = java.awt.Color(image.getRGB(x, y)); color.blue > color.red + 40 && color.blue > color.green + 20
            } }
            return rows.last() - rows.first() + 1
        }
        fun inkContrast(bytes: ByteArray): Int {
            val image = javax.imageio.ImageIO.read(bytes.inputStream())
            return (0 until image.width).maxOf { x -> (0 until image.height).maxOf { y ->
                val color = java.awt.Color(image.getRGB(x, y)); color.blue - maxOf(color.red, color.green)
            } }
        }
        try {
            scene.setContent { StandaloneLyricsTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale)) {
                    SideEffect { compositions++ }
                    CountdownRenderer.Paint(row, { time }, settings, Color.Blue, font, Modifier.fillMaxWidth())
                }
            } }
            for (alignment in listOf(CountdownAlignment.LEFT, CountdownAlignment.CENTER, CountdownAlignment.RIGHT)) {
                settings = CountdownSettings(alignment = alignment); time = 8200
                val bytes = draw(); val range = checkNotNull(bounds(bytes)) { "$alignment was invisible" }
                when (alignment) {
                    CountdownAlignment.LEFT -> check(range.first < 10)
                    CountdownAlignment.CENTER -> check(kotlin.math.abs((range.first + range.last) / 2 - 300) < 15)
                    CountdownAlignment.RIGHT -> check(range.last > 580)
                    else -> Unit
                }
                Files.write(output.resolve("DOTS-${alignment.name}.png"), bytes)
                check(draw().contentEquals(bytes)) { "Paused cue kept animating" }
                time = 0; val future = draw()
                check(bounds(future) != null) { "Upcoming cue row is blank before its interval" }
                time = 3999; check(draw().contentEquals(future)) { "Upcoming cue changed before its interval" }
                time = 9999; check(!draw().contentEquals(future)) { "Cue lost its highlight before the countdown finished" }
                time = 10000; val past = draw()
                check(bounds(past) != null && past.contentEquals(future)) { "Completed cue stayed bright instead of returning to dim dots" }
                time = 20000; check(draw().contentEquals(past)) { "Completed cue did not remain dim" }
                Files.write(output.resolve("DOTS-completed-${alignment.name}.png"), past)
            }
            time = 4000; settings = CountdownSettings(alignment = CountdownAlignment.CENTER)
            val early = draw(); check(bounds(early) != null) { "Dots were hidden until the final seconds" }
            time = 6900; check(draw().contentEquals(early)) { "Dots started counting before the configured lead" }
            time = 8200; check(!draw().contentEquals(early)) { "Dots did not count down near vocal entry" }
            settings = settings.copy(leadMillis = 1000)
            check(draw().contentEquals(early)) { "Changing countdown lead did not update dot progression" }
            settings = settings.copy(leadMillis = 3000)
            draw()
            val beforeTicks = compositions
            val gradual = listOf(7100L, 7250L, 7500L, 7750L, 7900L).map { position -> time = position; draw() }
            fun highlight(bytes: ByteArray): Long {
                val image = javax.imageio.ImageIO.read(bytes.inputStream())
                return (0 until image.width).sumOf { x -> (0 until image.height).sumOf { y -> (255 - java.awt.Color(image.getRGB(x, y)).red).toLong() } }
            }
            val amounts = gradual.map(::highlight)
            check(amounts.zipWithNext().all { (before, after) -> after > before }) { "Dot highlight still jumps instead of filling continuously: $amounts" }
            check(compositions == beforeTicks) { "Playback ticks recompose the countdown instead of invalidating its drawing" }
            time = 7500; check(draw().contentEquals(gradual[2])) { "Seeking did not restore the exact highlight position" }
            Files.write(output.resolve("DOTS-half-filled.png"), gradual[2])
            val half = javax.imageio.ImageIO.read(gradual[2].inputStream())
            check(java.awt.Color(half.getRGB(240, 31)).red < java.awt.Color(half.getRGB(260, 31)).red - 50) { "The active dot faded as a whole instead of filling left to right" }
            time = 4000
            val small = inkHeight(draw()); check(small in 30..33) { "48sp dots are too small: $small" }
            font = 96f
            val large = inkHeight(draw()); check(large in 61..64 && large >= small * 1.9)
            fontScale = 1.5f
            val scaled = draw(); check(inkHeight(scaled) in 92..95) { "Countdown ignored native font scaling" }
            Files.write(output.resolve("DOTS-96sp-fontScale-1.5.png"), scaled)
            val source = "[00:00.000]上一句<00:04.000>\n[00:10.000]本行<00:12.000>"
            val document = NativeHost.decode(source)
            val nativeSettings = CountdownSettings(gapMillis = 5000)
            NativeHost.applySettings(document, SavedLyrics(text = source), countdown = true, countdownSettings = nativeSettings)
            val line = NativeHost.lines(document).single { CountdownRenderer.cue(it)?.start == 4000L }
            check(NativeHost.lineKey(line).first == row.end - nativeSettings.leadMillis) { "Clicking the native cue would seek to the preceding vocal ending" }
            val types = CountdownRenderer.native.parameterTypes
            val properties = types[16].declaredConstructors.single { it.parameterCount == 8 && it.parameterTypes.last() == Int::class.javaPrimitiveType }.apply { isAccessible = true }
                .newInstance(false, 0f, 0f, 0L, 0f, false, 0L, 127)
            val base = listOf<Any?>(false, { hostTime }, line, types[3].enumConstants[1], Modifier.fillMaxWidth(), false,
                Color.Blue.value.toLong(), 24.sp.packedValue, null, null, null, TextUnit.Unspecified.packedValue, null, null, TextUnit.Unspecified.packedValue, androidx.compose.ui.text.TextStyle.Default, properties)
            CountdownRenderer.clear()
            scene.setContent { StandaloneLyricsTheme { OverlayFixture(base, CountdownSettings()) } }
            draw()
            CountdownRenderer.publish(java.util.IdentityHashMap<Any, LyricRow>().apply { put(line, row) })
            scene.setContent { StandaloneLyricsTheme { OverlayFixture(base.toMutableList().apply { this[0] = true }, CountdownSettings()) } }
            draw()
            check(CountdownRenderer.cue(line) == row)
            val another = NativeHost.decode(source)
            NativeHost.applySettings(another, SavedLyrics(text = source), countdown = true, countdownSettings = nativeSettings)
            val retained = NativeHost.lines(another).single { CountdownRenderer.cue(it)?.start == 4000L }
            check(retained !== line && NativeHost.lineKey(retained) == NativeHost.lineKey(line))
            CountdownRenderer.publish(mapOf(retained to row))
            check(CountdownRenderer.cue(line) == row) { "Equal-document reload orphaned the visible countdown row" }
            CountdownRenderer.publish(mapOf(retained to row))
            settings = CountdownSettings(alignment = CountdownAlignment.LEFT)
            scene.setContent { StandaloneLyricsTheme { OverlayFixture(base, settings) } }
            val left = draw(); check(checkNotNull(bounds(left)).first < 10)
            hostTime = 7500
            val following = draw(); check(!following.contentEquals(left)) { "Native playback clock did not drive the cue highlight" }
            check(draw().contentEquals(following)) { "Paused native playback clock kept advancing" }
            hostTime = 3500; check(draw().contentEquals(left)) { "Native seek left a stale highlight" }
            settings = settings.copy(alignment = CountdownAlignment.RIGHT)
            val right = draw(); check(checkNotNull(bounds(right)).last > 580 && !right.contentEquals(left))
            val public = transformedCountdownEntry()
            CountdownHookFixture.calls = 0
            CountdownHookFixture.enabled = false
            var nativeHeight = 0
            var seeked: Long? = null
            scene.size = IntSize(600, 400)
            CountdownHookFixture.settings = CountdownSettings(alignment = CountdownAlignment.LEFT)
            scene.setContent { StandaloneLyricsTheme {
                NativeHost.invoke(public, null, false, types[3].enumConstants[1], { hostTime }, line, { seeked = NativeHost.lineKey(line).first }, Modifier.fillMaxWidth().onSizeChanged { nativeHeight = it.height }, null, true,
                    null, null, currentComposer, 0, 960)
            } }
            draw()
            val anchorHeight = nativeHeight
            check(anchorHeight > 0)
            CountdownHookFixture.enabled = true
            val nativeLeft = draw()
            check(CountdownHookFixture.calls > 0 && bounds(nativeLeft) != null) { "Host default arguments bypassed the registered countdown hook" }
            check(hostTime < row.start) { "Native upcoming-row test accidentally ran during the cue" }
            Files.write(output.resolve("DOTS-native-upcoming.png"), nativeLeft)
            hostTime = 0
            check(bounds(draw()) != null) { "Native upcoming cue stayed blank while the preceding lyric played" }
            check(nativeHeight == anchorHeight) { "Countdown overlay changed the host's measured row height" }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(100f, nativeHeight / 2f), buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(100f, nativeHeight / 2f), buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            draw()
            check(seeked == 7000L) { "Clicking the real native countdown row sought to $seeked instead of its countdown start" }
            hostTime = 7500
            val active = draw()
            check(inkContrast(active) > inkContrast(nativeLeft) + 50) { "Transformed host call ignored its playback clock" }
            hostTime = 12000
            val completed = draw()
            check(bounds(completed) != null && inkContrast(completed) <= inkContrast(nativeLeft) + 10) { "Native completed cue did not return to its unhighlighted appearance" }
            Files.write(output.resolve("DOTS-native-completed.png"), completed)
            CountdownHookFixture.settings = CountdownHookFixture.settings.copy(alignment = CountdownAlignment.RIGHT)
            check(checkNotNull(bounds(draw())).last > checkNotNull(bounds(nativeLeft)).last + 100)
        } finally { CountdownRenderer.clear(); scene.close(); frame.close(); surface.close() }
    }
    println("PASS: native cue stays visible before/after countdown and returns to dim dots at the exact end; continuous fill until then, native mouse seek, draw-only clock, pause/seek, alignment/lead, font scaling and unchanged native anchor height.")
}

@Composable private fun OverlayFixture(arguments: List<Any?>, settings: CountdownSettings) {
    Column { Spacer(Modifier.height(70.dp)); CountdownRenderer.Content(arguments, settings) }
}
