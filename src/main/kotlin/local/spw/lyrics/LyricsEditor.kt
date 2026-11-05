package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

fun editRowTimes(row: LyricRow, start: Long, end: Long, wordIndex: Int, word: LyricWord?, text: String): LyricRow {
    val delta = start - row.start
    var finish = if (end == row.end) end + delta else end
    val words = if (!row.hasWords) listOf(LyricWord(start, finish, text)) else row.words.map { it.copy(start = it.start + delta, end = it.end + delta) }.toMutableList().also { values ->
        if (word != null) {
            val before = row.words[wordIndex]; val moved = values[wordIndex]
            values[wordIndex] = word.copy(start = if (word.start == before.start) moved.start else word.start, end = if (word.end == before.end) moved.end else word.end)
        }
    }
    val begin = if (start == row.start) minOf(start, words.minOf { it.start }) else start
    if (end == row.end) finish = maxOf(finish, words.maxOf { it.end })
    require(begin >= 0 && finish >= begin && words.all { it.start >= begin && it.end in it.start..finish }) { "字词时间须位于本行起止时间内" }
    return row.copy(start = begin, end = finish, words = words)
}

@Composable fun EditorPage(state: StudioState, commit: (Boolean, (SavedLyrics) -> SavedLyrics) -> Unit, import: () -> Unit = {}, export: () -> Unit = {}) {
    var part by remember { mutableStateOf(LyricsPart.ORIGINAL) }
    Column(Modifier.fillMaxSize().timingKeys(state)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("行与字词", "播放打轴", "源文本").forEachIndexed { index, label ->
                NativeUi.Button(label, !state.busy, primary = state.editorTab == index, quiet = state.editorTab != index) {
                    if (state.flushEditor?.invoke() != false) { state.timing.cancel(); state.timingRevision++; state.editorTab = index }
                }
            }
            Spacer(Modifier.weight(1f))
            NativeUi.Caption(state.document?.let { "${it.rows.size} 行 · ${if (it.hasWords) "逐字" else "逐行"}" }.orEmpty())
            NativeUi.Button("导入", !state.busy, quiet = true) { if (state.flushEditor?.invoke() != false) import() }
            NativeUi.Button("导出", !state.busy, quiet = true, click = export)
        }
        if (state.parseError.isNotEmpty()) NativeUi.Caption(state.parseError, Modifier.padding(horizontal = 24.dp))
        if (state.editorTab == 2) {
            NativeUi.Group { SettingChoice("编辑内容", part, LyricsPart.entries, { it.label }) { part = it } }
            val text = when (part) {
                LyricsPart.ORIGINAL -> state.draft.text ?: state.fallback
                LyricsPart.TRANSLATION -> state.draft.translation ?: state.document?.let { auxiliaryText(it, part) }.orEmpty()
                LyricsPart.ROMANIZATION -> state.draft.romanization ?: state.document?.let { auxiliaryText(it, part) }.orEmpty()
            }
            if (part == LyricsPart.ORIGINAL && state.parseError.isNotBlank()) Row(Modifier.padding(horizontal = 24.dp)) {
                NativeUi.Button("从纯文本开始打轴", !state.busy, quiet = true) {
                    try { state.changeDocument(plainTimingDocument(text)); state.parseError = ""; state.editorTab = 1 }
                    catch (failure: Exception) { state.error = failure.message ?: "无法转换纯文本" }
                }
            }
            Column(NativeUi.scrollModifier(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp))) {
                NativeUi.Edit(text, "歌词文本", false, Modifier.fillMaxWidth().heightIn(min = 340.dp)) {
                    state.timing.clear(); state.timingRevision++; state.documentText = null; state.documentDirty = false
                    state.draft = when (part) {
                        LyricsPart.ORIGINAL -> state.draft.copy(text = it, source = "手动编辑")
                        LyricsPart.TRANSLATION -> state.draft.copy(translation = it)
                        LyricsPart.ROMANIZATION -> state.draft.copy(romanization = it)
                    }
                }
            }
        } else TimelineEditor(state, Modifier.weight(1f))
        EditorTransport(state)
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val message = when { state.error.isNotBlank() -> state.error; state.busy -> "正在保存…"; state.hasUnsavedLyrics() -> "未应用的修改"; state.status.isNotBlank() -> state.status; else -> state.draft.source.ifEmpty { "当前歌词" } }
            NativeUi.Text(message, Modifier.weight(1f), size = 12, color = if (state.error.isNotBlank()) NativeUi.palette().error else NativeUi.palette().muted, singleLine = true)
            if (state.editorTab == 2 && part != LyricsPart.ORIGINAL) NativeUi.Button("跟随来源", quiet = true) {
                state.draft = if (part == LyricsPart.TRANSLATION) state.draft.copy(translation = null) else state.draft.copy(romanization = null)
            }
            SaveButton(state, if (state.editorTab == 2 && part != LyricsPart.ORIGINAL) "应用${part.label}" else "应用歌词") {
                if (state.timing.started != null) state.error = "请先结束本次打轴"
                else if (state.flushEditor?.invoke() != false) {
                    val draft = state.draft
                    val selectedPart = if (state.editorTab == 2) part else LyricsPart.ORIGINAL
                    commit(false) { saved ->
                        val next = when (selectedPart) {
                            LyricsPart.TRANSLATION -> saved.copy(translation = draft.translation)
                            LyricsPart.ROMANIZATION -> saved.copy(romanization = draft.romanization)
                            LyricsPart.ORIGINAL -> saved.copy(text = draft.text ?: state.fallback, source = draft.source.ifEmpty { "手动编辑" }, translation = draft.translation, romanization = draft.romanization, offset = draft.offset, format = draft.format)
                        }
                        LyricsCodec.source(next, state.fallback); next
                    }
                }
            }
        }
    }
}

@Composable private fun TimelineEditor(state: StudioState, modifier: Modifier) {
    val document = state.document
    if (document == null || document.rows.isEmpty()) { EmptyState("暂无可编辑歌词", "搜索或导入一份歌词", modifier); return }
    val selected = state.line.coerceIn(document.rows.indices)
    val row = document.rows[selected]
    val list = remember { LazyListState(0, 0) }
    LaunchedEffect(selected) { if (selected !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.scrollToItem(selected) }
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("歌词", Modifier.weight(0.56f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Caption("${selected + 1} / ${document.rows.size}", Modifier.weight(1f))
                NativeUi.Caption("开始 → 结束")
            }
            Rule()
            NativeUi.LazyRows(Modifier.weight(1f).fillMaxWidth(), list, document.rows.size) { index ->
                val item = document.rows[index]
                val colors = NativeUi.palette()
                Column(Modifier.fillMaxWidth().background(if (index == selected) colors.accent.copy(alpha = 0.09f) else colors.surface)
                    .clickable(Role.Button) { state.selectEditorRow(index) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NativeUi.Caption((index + 1).toString(), Modifier.width(28.dp))
                        NativeUi.Text(item.text.ifBlank { "空行" }, Modifier.weight(1f), size = 17, color = if (index == selected) colors.accent else colors.text, singleLine = true)
                    }
                    Row(Modifier.padding(start = 38.dp)) { NativeUi.Caption("${LyricsCodec.time(item.start)} → ${LyricsCodec.time(item.end)}") }
                }
            }
        }
        Panel("第 ${selected + 1} 行", Modifier.weight(0.44f).fillMaxHeight()) {
            if (state.editorTab == 1) TimingCapture(state, row) else RowEditor(state, document, row, selected)
        }
    }
}

@Composable private fun RowEditor(state: StudioState, document: LyricsDocument, row: LyricRow, selected: Int) {
    var start by remember(row) { mutableStateOf(LyricsCodec.time(row.start)) }
    var end by remember(row) { mutableStateOf(LyricsCodec.time(row.end)) }
    var main by remember(row) { mutableStateOf(row.text) }
    var translation by remember(row) { mutableStateOf(row.translation) }
    var roman by remember(row) { mutableStateOf(row.romanization) }
    var wordIndex by remember(selected) { mutableIntStateOf(0) }
    val wi = wordIndex.coerceIn(0, row.words.lastIndex.coerceAtLeast(0))
    val word = row.words.getOrNull(wi)
    var wordStart by remember(word) { mutableStateOf(word?.let { LyricsCodec.time(it.start) }.orEmpty()) }
    var wordEnd by remember(word) { mutableStateOf(word?.let { LyricsCodec.time(it.end) }.orEmpty()) }
    var wordText by remember(word) { mutableStateOf(word?.text.orEmpty()) }
    val dirty = start != LyricsCodec.time(row.start) || end != LyricsCodec.time(row.end) || main != row.text || translation != row.translation || roman != row.romanization ||
        (row.hasWords && word != null && (wordStart != LyricsCodec.time(word.start) || wordEnd != LyricsCodec.time(word.end) || wordText != word.text))
    fun flush(): Boolean {
        if (!dirty) return true
        return try {
            val editedWord = word?.takeIf { row.hasWords }?.let { LyricWord(LyricsCodec.milliseconds(wordStart), LyricsCodec.milliseconds(wordEnd), wordText) }
            val changed = editRowTimes(row, LyricsCodec.milliseconds(start), LyricsCodec.milliseconds(end), wi, editedWord, main)
            val rows = document.rows.toMutableList().also { it[selected] = changed.copy(translation = translation, romanization = roman) }
            state.timing.clear(); state.timingRevision++; state.changeDocument(document.copy(rows = rows)); state.error = ""; true
        } catch (failure: Exception) { state.error = failure.message ?: "时间格式无效"; false }
    }
    SideEffect { state.flushEditor = ::flush; state.editorDirty = dirty }
    DisposableEffect(Unit) { onDispose { state.flushEditor = null; state.editorDirty = false } }
    Column(Modifier.fillMaxHeight()) {
        Column(NativeUi.scrollModifier(Modifier.weight(1f).fillMaxWidth())) {
            EditSetting("开始", start) { start = it }
            EditSetting("结束", end) { end = it }
            if (row.hasWords) {
                SettingChoice("字词", wi, row.words.indices.toList(), { "${it + 1}. ${row.words[it].text.ifBlank { "空格" }}" }) { if (flush()) wordIndex = it }
                EditSetting("文字", wordText) { wordText = it }
                EditSetting("字开始", wordStart) { wordStart = it }
                EditSetting("字结束", wordEnd) { wordEnd = it }
            } else EditSetting("原文", main) { main = it }
            EditSetting("翻译", translation) { translation = it }
            EditSetting("罗马音", roman) { roman = it }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            NativeUi.Button("回听本行", state.timingAvailable, quiet = true) { state.replayTimingRow() }
            NativeUi.Button("删除此行", document.rows.size > 1 && !state.busy, quiet = true) {
                if (flush()) {
                    val current = state.document ?: return@Button
                    state.changeDocument(current.copy(rows = current.rows.filterIndexed { i, _ -> i != selected }), selected.coerceAtMost(current.rows.lastIndex - 1))
                    state.timing.clear(); state.timingRevision++
                }
            }
        }
    }
}

@Composable private fun EditSetting(title: String, value: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NativeUi.Caption(title, Modifier.width(52.dp))
        Field(value, title, Modifier.weight(1f), change = change)
    }
}
