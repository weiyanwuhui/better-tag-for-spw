@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.xuncorp.spw.workshop.api.hook.*

/** Native rows remain the scroll anchors; only generated cue rows receive a custom painter. */
object CountdownRenderer {
    private var cues by mutableStateOf<Map<Triple<Long, Long, String>, LyricRow>>(emptyMap())
    // Equal documents can retain the host's old row instances after a reload.
    fun publish(value: Map<Any, LyricRow>) { cues = value.entries.associate { NativeHost.lineKey(it.key) to it.value } }
    fun clear() { cues = emptyMap() }
    fun cue(line: Any?) = line?.let { cues[NativeHost.lineKey(it)] }
    val native by lazy { HostNames.resolver.composable("LyricsLineKaraokeText", 21, Boolean::class.javaPrimitiveType) }
    val caller by lazy { HostNames.resolver.callerIn(native, HostNames.resolver.facade("LyricsLineItem")) }
    val parameters get() = native.parameterTypes.takeWhile { it != Composer::class.java }.map { it.name }
    fun alignment(value: CountdownAlignment, inherited: Int) = when (value) {
        CountdownAlignment.FOLLOW -> inherited
        CountdownAlignment.LEFT -> 1
        CountdownAlignment.CENTER -> 0
        CountdownAlignment.RIGHT -> 2
    }
    @Composable fun Content(arguments: List<Any?>, settings: CountdownSettings, receiver: Any? = null) {
        val row = cue(arguments[2])
        if (row == null || arguments[0] == true) return
        @Suppress("UNCHECKED_CAST") val onTime = arguments[1] as () -> Long
        val font = TextUnit(arguments[7] as Long).value.takeIf { it.isFinite() && it > 0 } ?: NativeHost.fontSize().toFloat()
        // The native invisible row supplies the scroll/measure anchor. Paint over it without another row.
        val overlay = (arguments[4] as Modifier).layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
        }
        Paint(row, onTime, settings, Color((arguments[6] as Long).toULong()), font, overlay)
    }
    @Composable fun Paint(row: LyricRow, onTime: () -> Long, settings: CountdownSettings, color: Color, font: Float, modifier: Modifier = Modifier) {
        val position = alignment(settings.alignment, NativeHost.alignment())
        val countdownStart = maxOf(row.start, row.end - settings.leadMillis)
        val height = with(LocalDensity.current) { (font * 1.3f).sp.toDp() }
        Spacer(modifier.fillMaxWidth().height(height).drawBehind {
            // Read the same snapshot clock as native karaoke during drawing, without recomposing per tick.
            val time = onTime()
            // Like normal lyrics, visible cue rows exist before and after their active interval.
            val progress = ((time - countdownStart).toDouble() / (row.end - countdownStart).coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
            // Match a filled-circle glyph at the native lyric's sp size, including font scaling.
            val diameter = minOf(font.sp.toPx() * 0.65f, size.width / 4.2f)
            val width = diameter * 4.2f
            val x = when (position) { 1 -> 0f; 2 -> size.width - width; else -> (size.width - width) / 2 }
            val y = size.height * 0.5f
            repeat(3) { index ->
                val left = x + diameter * index * 1.6f
                val center = Offset(left + diameter * 0.5f, y)
                val fill = (progress * 3 - index).coerceIn(0f, 1f)
                drawCircle(color.copy(alpha = color.alpha * 0.24f), diameter * 0.5f, center)
                if (time < row.end && fill > 0f) drawCircle(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)),
                    startX = left + diameter * (fill * 1.24f - 0.24f), endX = left + diameter * fill * 1.24f), diameter * 0.5f, center)
            }
        })
    }
}

class CountdownLineHook(private val settings: State<CountdownSettings>) : ComposableHook() {
    @Composable override fun Content(call: UiHookCall) { CountdownRenderer.Content(call.arguments, settings.value, call.thisObject) }
}

