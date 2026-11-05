package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun QuickTypographyControls(failure: (String) -> Unit) {
    var size by remember { mutableIntStateOf(NativeHost.fontSize()) }
    var weight by remember { mutableIntStateOf(NativeHost.fontWeight()) }
    var alignment by remember { mutableIntStateOf(NativeHost.alignment()) }
    fun apply(action: () -> Unit) { try { action() } catch (error: Exception) { failure(error.message ?: "排版保存失败") } }
    Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Text("字号", Modifier.weight(1f))
            NativeUi.Button("−", size > 12) { apply { NativeHost.fontSize(size - 1); size-- } }
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { NativeUi.Text("$size") }
            NativeUi.Button("+", size < 80) { apply { NativeHost.fontSize(size + 1); size++ } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Text("字重", Modifier.weight(1f))
            NativeUi.Button("−", weight > 100) { apply { val next = (weight - 100).coerceAtLeast(100); NativeHost.fontWeight(next); weight = next } }
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { NativeUi.Text("$weight") }
            NativeUi.Button("+", weight < 900) { apply { val next = (weight + 100).coerceAtMost(900); NativeHost.fontWeight(next); weight = next } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Text("对齐", Modifier.weight(1f))
            listOf(1 to "左", 0 to "中", 2 to "右").forEach { (value, label) ->
                NativeUi.Button(label, quiet = alignment != value) { apply { NativeHost.alignment(value); alignment = value } }
            }
        }
    }
}

fun timingValue(value: String): Long = value.toLongOrNull()?.takeIf { it in -60_000..60_000 }
    ?: throw IllegalArgumentException("时间偏移范围：−60000 至 60000 毫秒")

@Composable fun QuickTimingControls(offset: Long, update: ((SavedLyrics) -> SavedLyrics) -> Unit, failure: (String) -> Unit) {
    var input by remember(offset) { mutableStateOf(offset.toString()) }
    fun commit() {
        val value = input.toLongOrNull()?.takeIf { it in -60_000..60_000 } ?: return
        if (value != offset) try { update { it.copy(offset = value) } }
        catch (error: Exception) { failure(error.message ?: "时间校准失败") }
    }
    LaunchedEffect(input) {
        kotlinx.coroutines.delay(350)
        commit()
    }
    fun change(delta: Int) {
        try {
            val value = ((input.toLongOrNull()?.takeIf { it in -60_000..60_000 } ?: offset) + 100L * delta).coerceIn(-60_000, 60_000)
            input = value.toString()
            update { it.copy(offset = value) }
        } catch (error: Exception) { failure(error.message ?: "时间校准失败") }
    }
    Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NativeUi.Text("偏移", Modifier.weight(1f))
            NativeUi.Button("−", offset > -60_000) { change(-1) }
            UiTypography.CenteredInput { Field(input, "", Modifier.width(84.dp).onFocusChanged { if (!it.isFocused) commit() }) { input = it.take(7) } }
            NativeUi.Button("+", offset < 60_000) { change(1) }
        }
    }
}

