package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp

@Composable fun TagsPage(state: StudioState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchQuery(state, state.searchTags, "获取标签")
        if (state.tagChoicesExpanded && state.tagResults.isNotEmpty()) SearchResultTable(state.tagResults, state.target(), state.tagSelected, state.tagPending, Modifier.weight(0.38f).fillMaxWidth(), state.fetchTags)
        else if (state.tagSelected != null) NativeUi.Group {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Text(state.tagSelected!!.title, Modifier.weight(1f), size = 14, singleLine = true)
                NativeUi.Caption("${state.tagSelected!!.source.label} · ${SearchRanking.score(state.tagSelected!!, state.target())}%")
                NativeUi.Button("更换匹配", quiet = true) { state.tagChoicesExpanded = true }
            }
        }
        NativeUi.Group(modifier = Modifier.weight(if (state.tagChoicesExpanded && state.tagResults.isNotEmpty()) 0.62f else 1f).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Text("待写入标签", Modifier.weight(1f), size = 13, bold = true)
                NativeUi.Caption(if (state.tagBusy) "处理中…" else state.tagSelected?.source?.label ?: "本地文件")
                NativeUi.Button("后台整理", quiet = true, click = state.openAuto)
            }
            Row(Modifier.fillMaxWidth().padding(start = 162.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeUi.Caption("本地内容", Modifier.weight(1f))
                NativeUi.Caption("写入内容", Modifier.weight(1f))
            }
            Rule()
            Column(NativeUi.scrollModifier(Modifier.weight(1f).fillMaxWidth())) {
                MusicTag.entries.forEach { field ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(146.dp)) { NativeUi.Check(field.label, field in state.tagFields, !state.tagBusy) {
                            val next = if (field in state.tagFields) state.tagFields - field else state.tagFields + field
                            state.updatePreferences(state.preferences.copy(tagFields = next)); state.tagFields = next
                        } }
                        NativeUi.Text(state.tagOriginal?.values?.get(field)?.ifBlank { "—" } ?: "—", Modifier.weight(1f), size = 13, color = NativeUi.palette().muted, singleLine = true)
                        Field(state.tagValues[field].orEmpty(), field.label, Modifier.weight(1f), enabled = !state.tagBusy) { text ->
                            state.tagValues = state.tagValues + (field to text); state.tagFields = state.tagFields + field
                            state.tagDirty = true
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(102.dp)) { NativeUi.SettingSwitch("封面", state.preferences.tagCover, !state.tagBusy) { state.updatePreferences(state.preferences.copy(tagCover = it)) } }
                    val cover = state.tagCover
                    if (cover != null) {
                        val bitmap = remember(cover) { loadImageBitmap(cover.bytes.inputStream()) }
                        Image(bitmap, "待写入封面", Modifier.size(72.dp), ContentScale.Fit)
                        NativeUi.Caption("${cover.width} × ${cover.height}", Modifier.weight(1f))
                    } else NativeUi.Caption("没有新封面，保留原封面", Modifier.weight(1f))
                    NativeUi.Button("选择封面", quiet = true) { state.navigate(StudioPage.COVER) }
                }
                NativeUi.SettingSwitch("内嵌已应用歌词", state.preferences.tagLyrics, !state.tagBusy) { state.updatePreferences(state.preferences.copy(tagLyrics = it)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Caption(if (state.tagFailures.isNotEmpty()) state.tagFailures.joinToString("；") else "写入前自动备份原文件", Modifier.weight(1f))
                NativeUi.Button("一键写入", !state.tagBusy && !state.busy && state.tagOriginal != null, primary = true, click = state.writeTags)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
