package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

fun autoTagStatus(config: AutoTagSettings, progress: AutoTagProgress): String = when {
    !config.enabled -> "未开启"
    progress.paused -> "已暂停"
    progress.phase == "未开启" -> "等待扫描"
    else -> progress.phase
}

@Composable fun AutoTagStatusStrip(config: AutoTagSettings, progress: AutoTagProgress, open: () -> Unit) {
    Rule()
    Row(NativeUi.clickable(Modifier.fillMaxWidth().height(32.dp), Role.Button, open).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NativeUi.Text("后台整理", size = 12, color = NativeUi.palette().accent, systemFont = true)
        NativeUi.Text(autoTagStatus(config, progress), size = 12, color = NativeUi.palette().muted, systemFont = true)
        if (config.enabled && progress.total > 0) NativeUi.Text("${progress.processed} / ${progress.total} · 已整理 ${progress.updated}", size = 12, systemFont = true)
        Spacer(Modifier.weight(1f))
        NativeUi.Text("查看 ›", size = 12, color = NativeUi.palette().muted, systemFont = true)
    }
}

@Composable fun AutoTagsScreen(config: AutoTagSettings, progress: AutoTagProgress, save: (AutoTagSettings) -> Unit,
    scan: () -> Unit, pause: () -> Unit, close: () -> Unit) {
    NativeUi.Screen("后台整理", "曲库", close) {
        AutoTagsContent(config, progress, save, scan, pause)
    }
}

@Composable fun AutoTagsContent(config: AutoTagSettings, progress: AutoTagProgress, save: (AutoTagSettings) -> Unit, scan: () -> Unit, pause: () -> Unit) {
        Column(NativeUi.scrollModifier(Modifier.fillMaxSize()).padding(horizontal = 16.dp)) {
            NativeUi.Group {
                NativeUi.SettingSwitch("自动整理曲库", config.enabled) { save(config.copy(enabled = it)) }
            }
            NativeUi.Group("扫描状态", Modifier.padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        NativeUi.Text(autoTagStatus(config, progress), Modifier.weight(1f), size = 18, bold = true, systemFont = true)
                        NativeUi.Button(if (progress.paused) "继续" else "暂停", config.enabled && (progress.running || progress.paused), quiet = true, click = pause)
                        NativeUi.Button("立即扫描", config.enabled && !progress.running && !progress.paused, primary = true, click = scan)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NativeUi.Caption("已检查 ${progress.processed} / ${progress.total} 首" + if (progress.active > 1) " · 正在处理 ${progress.active} 首" else "")
                        NativeUi.Caption(if (progress.total > 0) "${(progress.processed.toLong() * 100 / progress.total).coerceIn(0, 100)}%" else "—")
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).background(NativeUi.palette().stroke)) {
                        if (progress.total > 0) Box(Modifier.fillMaxWidth((progress.processed.toFloat() / progress.total).coerceIn(0f, 1f)).height(4.dp).background(NativeUi.palette().accent))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf("已整理" to progress.updated, "已保护" to progress.protected, "已跳过" to progress.skipped, "失败" to progress.failed).forEach { (label, count) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                NativeUi.Text(count.toString(), size = 22, systemFont = true)
                                NativeUi.Caption(label)
                            }
                        }
                    }
                    if (progress.song.isNotBlank()) {
                        NativeUi.Text(progress.song, size = 14, singleLine = true, systemFont = true)
                        NativeUi.Text(progress.path, size = 12, color = NativeUi.palette().muted, singleLine = true, systemFont = true)
                    }
                    if (progress.message.isNotBlank()) NativeUi.Text(progress.message, size = 13, color = NativeUi.palette().error, systemFont = true)
                    val times = listOfNotNull(progress.completedAt.takeIf { it > 0 }?.let { "上次完成 ${scanTime(it)}" })
                    if (times.isNotEmpty()) NativeUi.Caption(times.joinToString(" · "))
                }
            }
            AutoTagsSettings(config, save)
            Spacer(Modifier.height(24.dp))
        }
}

private fun scanTime(time: Long) = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable private fun AutoTagsSettings(config: AutoTagSettings, save: (AutoTagSettings) -> Unit) {
    var fields by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    NativeUi.Group("整理设置", Modifier.padding(top = 8.dp)) {
        SettingChoice("处理方式", config.mode, AutoTagMode.entries, { it.label }) { save(config.copy(mode = it)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeUi.Text("搜索并发", Modifier.weight(1f))
            var input by remember(config.concurrency) { mutableStateOf(config.concurrency.toString()) }
            Field(input, "1–64", Modifier.width(80.dp)) { input = it.filter(Char::isDigit).take(2) }
            NativeUi.Button("应用", input.toIntOrNull() in 1..64, quiet = true) { input.toIntOrNull()?.takeIf { it in 1..64 }?.let { save(config.copy(concurrency = it)) } }
        }
        NativeUi.Item("整理内容", config.fields.joinToString("、") { it.label } + if (config.cover) "、封面" else "") { fields = !fields }
        if (fields) {
            MusicTag.entries.forEach { field -> NativeUi.Check(field.label, field in config.fields) {
                save(config.copy(fields = if (field in config.fields) config.fields - field else config.fields + field))
            } }
            NativeUi.SettingSwitch("封面", config.cover) { save(config.copy(cover = it)) }
        }
        NativeUi.Item("搜索来源", config.sources.joinToString("、") { it.label }) { sources = !sources }
        if (sources) AUTO_TAG_SOURCES.forEach { source -> NativeUi.SettingSwitch(source.label, source in config.sources) { selected ->
            save(config.copy(sources = if (selected) config.sources + source else config.sources - source))
        } }
        NativeUi.Value("已由插件修改的歌曲", "自动保护")
    }
}
