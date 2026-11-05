package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

@Composable fun DisplayPage(state: StudioState, commit: (Boolean, (SavedLyrics) -> SavedLyrics) -> Unit, saveFont: () -> Unit, import: () -> Unit, export: () -> Unit) {
    fun save(value: LyricsPreferences) {
        if (state.busy) return
        state.updatePreferences(value)
        if (state.preferences != value) return
        state.draft = value.applyContentTo(state.draft)
        commit(false) { value.applyContentTo(it) }
    }
    var manageSources by remember { mutableStateOf(false) }
    val content = state.preferences.copy(chinese = state.draft.chinese, translation = state.draft.showTranslation,
        romanization = state.draft.showRomanization, removeEmpty = state.draft.removeEmptyLines, order = state.draft.order)
    Column(NativeUi.scrollModifier(Modifier.fillMaxSize())) {
        NativeUi.Group("功能入口") {
            SettingChoice("打开方式", state.preferences.entry, LyricsEntry.entries, { it.label }) { state.updatePreferences(state.preferences.copy(entry = it)) }
        }
        NativeUi.Group("后台整理") {
            NativeUi.Item("自动整理曲库", autoTagStatus(state.preferences.autoTags, state.autoProgress), state.openAuto)
        }
        NativeUi.Group("搜索") {
            SettingChoice("默认歌词类型", state.preferences.format, LyricsFormat.entries, { it.label }) { format -> state.updatePreferences(state.preferences.copy(format = format, source = state.preferences.source?.takeIf { it in LyricSources.available(format) })) }
            val sources = LyricSources.available(state.preferences.format).filter { it in state.preferences.enabled }
            SettingChoice("默认来源", state.preferences.source?.takeIf { it in sources }, listOf(null) + sources, { it?.label ?: "全部来源" }) { state.updatePreferences(state.preferences.copy(source = it)) }
            NativeUi.Item("来源管理", state.preferences.enabled.joinToString("、") { it.label }) { manageSources = !manageSources }
            if (manageSources) LyricSource.entries.forEach { source ->
                NativeUi.SettingSwitch(source.label, source in state.preferences.enabled) { enabled ->
                    val next = if (enabled) state.preferences.enabled + source else state.preferences.enabled - source
                    if (next.isEmpty()) state.error = "至少保留一个歌词来源"
                    else state.updatePreferences(state.preferences.copy(enabled = next, source = state.preferences.source?.takeIf { it in next }))
                }
            }
        }
        NativeUi.Group("歌词内容", Modifier.padding(top = 8.dp)) {
            val contents = (if (content.translation) 1 else 0) + (if (content.romanization) 2 else 0)
            SettingChoice("包含内容", contents, listOf(0, 1, 2, 3), { listOf("仅原词", "原词与译文", "原词与罗马音", "原词、译文与罗马音")[it] }) { save(content.copy(translation = it and 1 != 0, romanization = it and 2 != 0)) }
            SettingChoice("繁简转换", content.chinese, ChineseMode.entries, { it.label }) { save(content.copy(chinese = it)) }
            SettingChoice("空行处理", content.removeEmpty, listOf(true, false), { if (it) "删除空行" else "保留空行" }) { save(content.copy(removeEmpty = it)) }
            val orders = LyricsPart.entries.flatMap { first -> LyricsPart.entries.filter { it != first }.map { second -> listOf(first, second, LyricsPart.entries.single { it != first && it != second }) } }
            SettingChoice("输出顺序", content.order, orders, { parts -> parts.joinToString(" → ") { it.label } }) { save(content.copy(order = it)) }
        }
        NativeUi.Group("歌词倒计时", Modifier.padding(top = 8.dp)) {
            NativeUi.SettingSwitch("启用倒计时", state.preferences.interludeCountdown) { state.updatePreferences(state.preferences.copy(interludeCountdown = it)) }
            if (state.preferences.interludeCountdown) {
                val settings = state.preferences.countdown
                CountdownSecondsSetting("倒数提前时间", settings.leadMillis, 30, { state.error = it }) { state.updatePreferences(state.preferences.copy(countdown = settings.copy(leadMillis = it))) }
                CountdownSecondsSetting("最短间奏", settings.gapMillis, 120, { state.error = it }) { state.updatePreferences(state.preferences.copy(countdown = settings.copy(gapMillis = it))) }
                SettingChoice("显示位置", settings.alignment, CountdownAlignment.entries, { it.label }) { state.updatePreferences(state.preferences.copy(countdown = settings.copy(alignment = it))) }
            }
        }
        NativeUi.Group("标签写入", Modifier.padding(top = 8.dp)) {
            NativeUi.SettingSwitch("包含封面", state.preferences.tagCover) { state.updatePreferences(state.preferences.copy(tagCover = it)) }
            NativeUi.SettingSwitch("内嵌已应用歌词", state.preferences.tagLyrics) { state.updatePreferences(state.preferences.copy(tagLyrics = it)) }
            NativeUi.Value("原文件备份", "自动保留")
            NativeUi.Value("支持格式", "FLAC、MP3、M4A、MP4、OGG、WAV、Opus、AIFF")
        }
        NativeUi.Group("歌词文件", Modifier.padding(top = 8.dp)) {
            NativeUi.Value("保存位置", "播放器配置")
            SettingChoice("导出格式", state.preferences.exportFormat, LyricsFormat.entries, { it.label }) { state.updatePreferences(state.preferences.copy(exportFormat = it)) }
            NativeUi.Item("导出歌词文件…", null, export)
            NativeUi.Item("导入歌词…", "LRC、YRC、KRC、TTML、TXT", import)
        }
        NativeUi.Group("时间校准", Modifier.padding(top = 8.dp)) {
            var offset by remember(state.saved.offset) { mutableStateOf(state.draft.offset.toString()) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeUi.Text("偏移", Modifier.weight(1f))
                fun change(value: Long) {
                    state.draft = state.draft.copy(offset = value); offset = value.toString()
                    commit(false) { it.copy(offset = value) }
                }
                NativeUi.Button("−100", !state.busy) { change((state.draft.offset - 100).coerceAtLeast(-60_000)) }
                Field(offset, "毫秒", Modifier.width(100.dp)) { offset = it }
                NativeUi.Text("ms")
                NativeUi.Button("+100", !state.busy) { change((state.draft.offset + 100).coerceAtMost(60_000)) }
                NativeUi.Button("应用", !state.busy) {
                    val value = offset.toLongOrNull()
                    if (value == null || value !in -60_000..60_000) state.error = "偏移范围：−60000 至 60000 毫秒" else change(value)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun CountdownSecondsSetting(title: String, millis: Long, maximum: Int, failure: (String) -> Unit, save: (Long) -> Unit) {
    var input by remember(millis) { mutableStateOf(java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()) }
    val latestSave by rememberUpdatedState(save)
    fun commit() {
        try { val value = countdownSeconds(input, maximum); if (value != millis) save(value) }
        catch (error: Exception) { failure(error.message ?: "请输入有效秒数") }
    }
    LaunchedEffect(input) {
        kotlinx.coroutines.delay(500)
        val value = runCatching { countdownSeconds(input, maximum) }.getOrNull()
        if (value != null && value != millis) latestSave(value)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NativeUi.Text(title, Modifier.weight(1f))
        Field(input, "1–$maximum", Modifier.width(88.dp).onFocusChanged { if (!it.isFocused) commit() }) { input = it.take(8) }
        NativeUi.Caption("秒")
    }
}
