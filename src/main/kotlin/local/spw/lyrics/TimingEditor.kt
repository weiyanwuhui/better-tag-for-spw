package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.awt.event.KeyEvent as AwtKey

fun StudioState.timingAction(action: (TimingPlayback) -> Unit) {
    try {
        val playback = checkNotNull(timingPlayback) { "播放控制尚未就绪" }
        if (!playback.current()) { timing.cancel(); timingRevision++; error("歌曲已切换，编辑内容已保留，请切回本曲再打轴") }
        action(playback); timingClock = playback.clock(); error = ""
        if (!timingClock.playing && timing.started != null) { timing.cancel(); timingRevision++ }
    } catch (failure: Exception) { error = failure.message ?: "播放控制失败" }
}
fun StudioState.captureTiming(release: Boolean, continuous: Boolean = false) {
    if (busy || editorTab != 1) return
    timingAction { playback ->
        val clock = playback.clock()
        if (!clock.playing) { timing.cancel(); timingRevision++; error("请先播放歌曲，再记录时间") }
        val document = checkNotNull(document) { "暂无可打轴歌词" }
        require(document.rows[line].text.isNotBlank()) { "请选择有歌词的行" }
        if (timing.started?.let { clock.position < it } == true) {
            timing.cancel(); timingRevision++; error("播放位置已回退，请重新开始打点")
        }
        if (!release) { if (timing.started == null) { timingLoop = false; timingStopAt = null; timing.begin(clock.position, continuous) } }
        else if (timing.started != null) {
            val previousLine = line
            val (changed, nextLine) = timing.finish(document, line, clock.position, draft.offset, draft, documentDirty, continuous)
            changeDocument(changed, nextLine, encode = false)
            draft = draft.copy(offset = 0, format = if (timing.mode == TimingMode.WORD && draft.format == LyricsFormat.LINE) LyricsFormat.WORD else draft.format)
            status = if (nextLine == previousLine && timing.word == 0 && timing.started == null) "本曲打轴完成 · 可回听后应用" else "已记录，待应用"
        }
        timingRevision++
    }
}
fun StudioState.undoTiming() {
    timing.undo()?.let { edit ->
        changeDocument(edit.document, edit.line, encode = false)
        draft = edit.original ?: draft.copy(offset = edit.offset); documentText = draft.text
        documentDirty = edit.pending
    }
    timingRevision++; status = "已撤销上一次打轴"
}
fun StudioState.tapTiming() {
    captureTiming(timing.started != null, continuous = true)
}
fun StudioState.groupTiming(groups: List<String>) {
    try {
        val before = checkNotNull(document)
        val changed = timing.regroup(before, line, groups, draft.offset, draft, documentDirty)
        if (changed != before) changeDocument(changed, encode = false)
        timingRevision++; error = ""
    } catch (failure: Exception) { error = failure.message ?: "分组失败" }
}

/** Scoped to this editor window; text fields keep their typing keys. */
class TimingWindowKeys(private val window: java.awt.Window?, private val state: StudioState) : java.awt.KeyEventDispatcher {
    private val pressed = mutableSetOf<Int>()
    override fun dispatchKeyEvent(event: AwtKey): Boolean {
        if (window == null || !window.isActive || event.component !== window && event.component?.let { javax.swing.SwingUtilities.getWindowAncestor(it) } !== window) { pressed.clear(); return false }
        return handle(event)
    }
    fun handle(event: AwtKey): Boolean {
        if (state.page != StudioPage.EDIT || state.editorTab == 2 || state.busy || event.isAltDown || event.isControlDown || event.isMetaDown) { pressed.clear(); return false }
        val code = event.keyCode
        if (code !in setOf(AwtKey.VK_SPACE, AwtKey.VK_ENTER, AwtKey.VK_BACK_SPACE, AwtKey.VK_F5, AwtKey.VK_F6, AwtKey.VK_F7, AwtKey.VK_F8)) return false
        if (state.editorTab != 1 && code !in setOf(AwtKey.VK_F5, AwtKey.VK_F6)) { pressed.clear(); return false }
        if (event.id == AwtKey.KEY_PRESSED && pressed.add(code)) when (code) {
            AwtKey.VK_SPACE -> state.tapTiming()
            AwtKey.VK_ENTER -> if (state.timing.started != null) state.captureTiming(true)
            AwtKey.VK_BACK_SPACE, AwtKey.VK_F7 -> state.undoTiming()
            AwtKey.VK_F5 -> state.timingAction { if (it.clock().playing) it.pause() else it.play() }
            AwtKey.VK_F6 -> state.replayTimingRow()
            AwtKey.VK_F8 -> state.captureTiming(false)
        }
        if (event.id == AwtKey.KEY_RELEASED) { val held = pressed.remove(code); if (held && code == AwtKey.VK_F8) state.captureTiming(true) }
        event.consume(); return true
    }
}
fun StudioState.replayTimingRow() {
    if (flushEditor?.invoke() == false) return
    val row = document?.rows?.getOrNull(line) ?: return
    timing.cancel(); timingRevision++
    timingAction { it.seek((row.start + draft.offset - 3000).coerceAtLeast(0)); it.play(); timingStopAt = row.end + draft.offset + 300 }
}
fun StudioState.selectEditorRow(index: Int) {
    if (flushEditor?.invoke() == false) return
    val row = document?.rows?.getOrNull(index) ?: return
    timing.cancel(); timing.word = 0; timingStopAt = null; timingRevision++; line = index
    if (editorTab == 1) timingAction { it.seek((row.start + draft.offset - 3000).coerceIn(0, media.duration.coerceAtLeast(1))) }
}
@Composable fun Modifier.timingKeys(state: StudioState): Modifier {
    val focused = LocalWindowInfo.current.isWindowFocused
    val keys = remember(state, focused, state.editorTab) { TimingWindowKeys(null, state) }
    return onPreviewKeyEvent { event ->
        (event.nativeKeyEvent as? AwtKey)?.let(keys::handle) ?: false
    }
}

@Composable fun TimingCapture(state: StudioState, row: LyricRow) {
    state.timingRevision
    val timing = state.timing
    val tokens = timingTokens(row)
    val palette = NativeUi.palette()
    val list = remember { androidx.compose.foundation.lazy.LazyListState(0, 0) }
    LaunchedEffect(timing.word, state.line) { if (timing.word !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.scrollToItem(timing.word) }
    SideEffect { state.flushEditor = {
        try {
            if (state.documentDirty) state.changeDocument(checkNotNull(state.document))
            true
        } catch (failure: Exception) { state.error = failure.message ?: "歌词整理失败"; false }
    } }
    DisposableEffect(Unit) { onDispose { state.flushEditor = null } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        key(timing.mode) {
            SettingChoice("打轴方式", timing.mode, TimingMode.entries, { it.label }) {
                timing.cancel(); timing.mode = it; timing.word = 0; state.timingRevision++
            }
        }
        if (timing.mode == TimingMode.WORD) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NativeUi.Caption("字词", Modifier.weight(1f))
                NativeUi.Caption("开始", Modifier.width(78.dp))
                NativeUi.Caption("结束", Modifier.width(78.dp))
            }
            NativeUi.LazyRows(Modifier.weight(1f).fillMaxWidth(), list, tokens.size) { index ->
                val selected = timing.word == index
                val word = row.words.getOrNull(index)?.takeIf { row.words.size == tokens.size && it.end > it.start }
                val begin = if (selected && timing.started != null) timing.started else word?.start?.plus(state.draft.offset)
                val end = if (selected && timing.started != null) state.timingClock.position else word?.end?.plus(state.draft.offset)
                Row(Modifier.fillMaxWidth().background(if (selected) palette.accent.copy(alpha = 0.08f) else palette.surface)
                    .clickable(androidx.compose.ui.semantics.Role.Button) { timing.cancel(); timing.word = index; state.timingRevision++ }
                    .padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NativeUi.Text(tokens[index], Modifier.weight(1f), size = 17, color = if (selected) palette.accent else palette.text, singleLine = true)
                    NativeUi.Text(begin?.let(LyricsCodec::time) ?: "—", Modifier.width(78.dp), size = 12, color = palette.muted, systemFont = true)
                    NativeUi.Text(end?.let(LyricsCodec::time) ?: "—", Modifier.width(78.dp), size = 12, color = palette.muted, systemFont = true)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Caption("${timing.word + 1} / ${tokens.size}", Modifier.weight(1f))
                val selected = tokens.getOrNull(timing.word).orEmpty()
                val split = timingTokens(LyricRow(row.start, row.end, listOf(LyricWord(row.start, row.end, selected))))
                NativeUi.Button("拆分", split.size > 1, quiet = true) { state.groupTiming(tokens.take(timing.word) + split + tokens.drop(timing.word + 1)) }
                NativeUi.Button("合并后字", timing.word < tokens.lastIndex, quiet = true) {
                    state.groupTiming(tokens.take(timing.word) + listOf(tokens[timing.word] + tokens[timing.word + 1]) + tokens.drop(timing.word + 2))
                }
            }
        } else Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
            NativeUi.Text(row.text.ifBlank { "空行" }, size = 24, bold = true)
            Spacer(Modifier.height(12.dp))
            val begin = timing.started ?: row.start + state.draft.offset
            val end = if (timing.started != null) state.timingClock.position else row.end + state.draft.offset
            NativeUi.Text("${LyricsCodec.time(begin)} → ${LyricsCodec.time(end)}", size = 14, color = palette.muted, systemFont = true)
        }
        val last = timing.lastRecorded
        NativeUi.Text(if (timing.started != null) "记录中 · ${LyricsCodec.time(timing.started!!)} · Enter 收尾" else last?.let { "等待开始 · 上次 ${LyricsCodec.time(it.start)} → ${LyricsCodec.time(it.end)}" } ?: "空格连续打点 · 停顿前 Enter 收尾",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp), size = 12, color = palette.muted, singleLine = true, systemFont = true)
        val lastLine = state.document?.rows?.indexOfLast { it.text.isNotBlank() } == state.line
        val lineEnd = timing.mode == TimingMode.LINE || timing.word == tokens.lastIndex
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Button(if (timing.started == null) "开始  空格" else if (lineEnd && lastLine) "完成  空格" else if (lineEnd) "下一行  空格" else "下一字  空格", state.timingAvailable && !state.busy && row.text.isNotBlank(), primary = true, modifier = Modifier.weight(1f)) { state.tapTiming() }
            NativeUi.Button("收尾 Enter", state.timingAvailable && !state.busy && timing.started != null, quiet = true) { state.captureTiming(true) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            NativeUi.Button("撤销", timing.canUndo && !state.busy, quiet = true) { state.undoTiming() }
            NativeUi.Button("取消本次", timing.started != null, quiet = true) { timing.cancel(); state.timingRevision++ }
            Spacer(Modifier.weight(1f))
            NativeUi.Button("回听 F6", state.timingAvailable, quiet = true) { state.replayTimingRow() }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable fun EditorTransport(state: StudioState) {
    var initialSpeed by remember { mutableStateOf<Float?>(null) }
    var setSpeed by remember { mutableStateOf<Float?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (!focused) { state.timing.cancel(); state.timingRevision++ } }
    LaunchedEffect(state.timingPlayback) {
        val playback = state.timingPlayback ?: return@LaunchedEffect
        while (true) {
            try {
                state.timingAvailable = playback.current()
                if (state.timingAvailable) {
                    val clock = playback.clock(); state.timingClock = clock
                    if (initialSpeed == null) initialSpeed = clock.speed
                    val row = state.document?.rows?.getOrNull(state.line)
                    if (state.timingLoop && clock.playing && state.timing.started == null && row != null && row.end > row.start && clock.position >= row.end + state.draft.offset) playback.seek((row.start + state.draft.offset - 3000).coerceAtLeast(0))
                    if (!state.timingLoop && state.timingStopAt?.let { clock.position >= it } == true && clock.playing) { playback.pause(); state.timingStopAt = null }
                    if (!clock.playing && state.timing.started != null) { state.timing.cancel(); state.timingRevision++ }
                } else { state.timing.cancel(); state.timingLoop = false; state.timingStopAt = null; state.timingRevision++ }
            } catch (failure: Exception) { state.timingAvailable = false; state.error = failure.message ?: "无法读取播放位置" }
            delay(40)
        }
    }
    DisposableEffect(state.timingPlayback) {
        val playback = state.timingPlayback
        onDispose {
            state.timing.cancel(); state.timingRevision++
            state.timingLoop = false; state.timingStopAt = null
            runCatching { if (playback?.current() == true && setSpeed != null && playback.clock().speed == setSpeed) initialSpeed?.let(playback::speed) }
        }
    }
    fun seek(value: Long) { state.timing.cancel(); state.timingStopAt = null; state.timingRevision++; state.timingAction { it.seek(value.coerceIn(0, state.media.duration.coerceAtLeast(1))) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        NativeUi.TimeSlider(state.timingClock.position, state.media.duration) { seek(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val playLabel = (if (state.timingClock.playing) "暂停" else "播放") + if (state.editorTab == 2) "" else "  F5"
            NativeUi.Button(playLabel, state.timingAvailable, primary = true) {
                state.timingAction { if (it.clock().playing) it.pause() else it.play() }
            }
            NativeUi.Button("−3 秒", state.timingAvailable, quiet = true) { seek(state.timingClock.position - 3000) }
            NativeUi.Button("+3 秒", state.timingAvailable, quiet = true) { seek(state.timingClock.position + 3000) }
            NativeUi.Button("单行循环", state.timingAvailable, quiet = !state.timingLoop) {
                state.timing.cancel(); state.timingRevision++; state.timingLoop = !state.timingLoop
                if (state.timingLoop) state.replayTimingRow() else state.timingStopAt = null
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(130.dp)) {
                SettingChoice("速度", state.timingClock.speed, (listOf(0.5f, 0.75f, 1f, 1.25f) + state.timingClock.speed).distinct().sorted(), { "${it}×" }) { value ->
                    state.timingAction { it.speed(value); setSpeed = value }
                }
            }
            NativeUi.Caption("${LyricsCodec.time(state.timingClock.position)} / ${LyricsCodec.time(state.media.duration)}")
        }
        if (!state.timingAvailable) NativeUi.Caption("${if (state.timingPlayback == null) "播放控制加载中" else "请切回本曲播放，编辑内容已保留"}")
    }
}
