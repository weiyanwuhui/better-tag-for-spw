package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyListState

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp

fun timelineLine(row: LyricRow) = if (row.hasWords) row.words.joinToString("") { "<${LyricsCodec.time(it.start)}>${it.text}" } else row.text

@Composable fun LyricTimelineText(document: LyricsDocument?, state: StudioState, modifier: Modifier) {
    if (document == null || document.rows.isEmpty()) { EmptyState("选择一份歌词查看时间轴", modifier = modifier); return }
    val active = activeLyricRow(document, state.position)
    val scroll = remember { LazyListState(0, 0) }
    LaunchedEffect(document, active) { if (active >= 0) scroll.scrollToItem((active - 1).coerceAtLeast(0)) }
    val p = NativeUi.palette()
    NativeUi.LazyRows(modifier.padding(horizontal = 16.dp), scroll, document.rows.size) { index ->
            val row = document.rows[index]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NativeUi.Text("[${LyricsCodec.time(row.start)}]", Modifier.width(92.dp), size = 12, color = p.muted)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val text = buildAnnotatedString {
                        if (row.hasWords) row.words.forEach { word ->
                            pushStyle(SpanStyle(color = p.muted)); append("<${LyricsCodec.time(word.start)}>"); pop()
                            pushStyle(SpanStyle(color = if (index == active && state.position >= word.start) p.accent else p.text)); append(word.text); pop()
                        } else { pushStyle(SpanStyle(color = if (index == active) p.accent else p.text)); append(row.text); pop() }
                    }
                    NativeUi.Text(text, size = 14)
                    if (state.draft.showTranslation && row.translation.isNotBlank()) NativeUi.Caption(row.translation)
                    if (state.draft.showRomanization && row.romanization.isNotBlank()) NativeUi.Text(row.romanization,
                        size = 12, color = p.muted, systemFont = true)
                }
            }
    }
}
