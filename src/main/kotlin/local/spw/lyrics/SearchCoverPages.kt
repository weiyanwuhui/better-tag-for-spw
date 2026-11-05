package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayInputStream

@Composable fun QueryField(label: String, value: String, modifier: Modifier, change: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NativeUi.Caption(label)
        Field(value, label, Modifier.fillMaxWidth(), change = change)
    }
}

@Composable fun SearchQuery(state: StudioState, search: () -> Unit, label: String = "搜索") {
    NativeUi.Group {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            QueryField("歌曲名称", state.title, Modifier.width(230.dp)) { state.title = it }
            QueryField("歌手", state.artist, Modifier.width(150.dp)) { state.artist = it }
            val lyricSearch = state.page == StudioPage.SEARCH
            QueryField("专辑", if (lyricSearch) state.searchAlbum else state.album, Modifier.weight(1f)) {
                if (lyricSearch) state.searchAlbum = it else state.album = it
            }
            NativeUi.Button(label, !state.busy && !state.tagBusy, primary = true, click = search)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Caption(listOf(state.preferences.format.shortLabel, state.sourceFilter?.label ?: "全部来源", state.media.duration.takeIf { it > 0 }?.let { "参考时长 ${LyricsCodec.time(it).substringBefore('.')}" }).filterNotNull().joinToString(" · "), Modifier.weight(1f))
        }
    }
}

@Composable fun SearchResultTable(candidates: List<Candidate>, target: SearchTarget, selected: Candidate?, pending: Int, modifier: Modifier, select: (Candidate) -> Unit) {
    val palette = NativeUi.palette()
    val ranked = remember(candidates, target) { SearchRanking.rank(candidates, target) }
    val density = LocalDensity.current.density
    var tableWidth by remember { mutableFloatStateOf(1000f) }
    val free = (tableWidth - 270f).coerceAtLeast(370f)
    val titleWidth = (free * 0.39f).coerceAtMost(290f).dp
    val artistWidth = (free * 0.23f).coerceAtMost(170f).dp
    val albumWidth = (free * 0.38f).coerceAtMost(300f).dp
    NativeUi.Group(modifier = modifier.onSizeChanged { tableWidth = it.width / density }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Text("搜索结果", Modifier.weight(1f), size = 13, bold = true)
            NativeUi.Caption(if (pending > 0) "${ranked.size} 首 · 搜索中" else "${ranked.size} 首")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NativeUi.Text("匹配度", Modifier.width(46.dp), size = 12, color = palette.muted, systemFont = true)
            NativeUi.Text("歌曲名称", Modifier.width(titleWidth), size = 12, color = palette.muted, systemFont = true)
            NativeUi.Text("歌手", Modifier.width(artistWidth), size = 12, color = palette.muted, systemFont = true)
            NativeUi.Text("专辑", Modifier.width(albumWidth), size = 12, color = palette.muted, systemFont = true)
            NativeUi.Text("时长", Modifier.width(48.dp), size = 12, color = palette.muted, systemFont = true)
            NativeUi.Text("来源", Modifier.width(68.dp), size = 12, color = palette.muted, systemFont = true)
        }
        Rule()
        if (ranked.isEmpty()) EmptyState(if (pending > 0) "正在搜索…" else "暂无匹配结果", modifier = Modifier.weight(1f))
        else Column(NativeUi.scrollModifier(Modifier.weight(1f).fillMaxWidth())) {
            ranked.forEach { (candidate, score) -> key(candidate.key) {
                Row(Modifier.fillMaxWidth().background(if (selected?.key == candidate.key) palette.accent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(Role.Button) { select(candidate) }.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NativeUi.Text("$score%", Modifier.width(46.dp), size = 12, color = if (score >= 90) palette.accent else palette.muted, singleLine = true, systemFont = true)
                    NativeUi.Text(candidate.title, Modifier.width(titleWidth), size = 14, bold = selected?.key == candidate.key, singleLine = true, systemFont = true)
                    NativeUi.Text(candidate.artist, Modifier.width(artistWidth), size = 14, singleLine = true, systemFont = true)
                    NativeUi.Text(candidate.album.ifBlank { "—" }, Modifier.width(albumWidth), size = 14, color = palette.text, singleLine = true, systemFont = true)
                    NativeUi.Text(if (candidate.duration > 0) LyricsCodec.time(candidate.duration).substringBefore('.') else "—", Modifier.width(48.dp), size = 12, color = palette.muted, singleLine = true, systemFont = true)
                    NativeUi.Text(if (candidate.source == LyricSource.AMLL) "AMLL" else candidate.source.label, Modifier.width(68.dp), size = 12, color = palette.muted, singleLine = true, systemFont = true)
                }
            } }
        }
    }
}

@Composable fun SearchPage(state: StudioState, commit: (Boolean, (SavedLyrics) -> SavedLyrics) -> Unit, search: () -> Unit, preview: (Candidate) -> Unit, export: () -> Unit) {
    var sourceStatus by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchQuery(state, search)
        SearchResultTable(state.results.filter { state.sourceFilter == null || it.source == state.sourceFilter }, state.lyricTarget(), state.selected, state.pending, Modifier.weight(1f).fillMaxWidth(), preview)
        if (state.failures.isNotEmpty() || state.searched) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Caption(if (state.failures.isEmpty()) "" else "暂不可用：" + state.failures.keys.joinToString("、") { it.label }, Modifier.weight(1f))
                NativeUi.Button("来源状态", quiet = true) { sourceStatus = !sourceStatus }
            }
            if (sourceStatus) state.failures.forEach { (source, reason) -> NativeUi.Caption("${source.label}：$reason", Modifier.padding(horizontal = 20.dp)) }
            if (sourceStatus) state.sourceCounts.forEach { (source, count) ->
                NativeUi.Caption("${source.label}：" + if (count == 0) "已查询，无匹配歌词" else "$count 份歌词", Modifier.padding(horizontal = 20.dp))
            }
        }
        if (state.sourceFilter != null && state.sourceFilter !in LyricSources.available(state.preferences.format)) NativeUi.Caption("${state.sourceFilter!!.label} 不参与当前类型的搜索；可在设置中更换来源", Modifier.padding(horizontal = 20.dp))
        NativeUi.Group(modifier = Modifier.height(if (state.previewTiming) 238.dp else 186.dp)) {
            val value = state.previewLyrics()
            var parsed by remember(value) { mutableStateOf<Result<Pair<LyricsDocument, LyricsDocument>?>?>(null) }
            LaunchedEffect(value) {
                parsed = runInterruptible(Dispatchers.IO) { runCatching { value?.let { val source = LyricsCodec.source(it, ""); source to LyricsCodec.render(source, it) } } }
            }
            val document = parsed?.getOrNull()?.second
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Text("歌词时间轴", size = 13, bold = true)
                Spacer(Modifier.width(12.dp))
                NativeUi.Caption(if (document == null) "" else if (document.hasWords) "逐字 · ${document.kind}" else "逐行 · ${document.kind}", Modifier.weight(1f))
                NativeUi.Button(if (state.previewCurrent) "查看搜索结果" else "查看内嵌歌词", state.previewCurrent.not() || state.downloaded != null, quiet = true) { state.previewCurrent = !state.previewCurrent; state.previewOffset = 0 }
            }
            if (state.previewCurrent && state.embeddedLoading || !state.previewCurrent && state.downloading || value != null && parsed == null) EmptyState("正在加载…", modifier = Modifier.weight(1f))
            else if (document == null) EmptyState(parsed?.exceptionOrNull()?.message ?: if (state.previewCurrent) state.embeddedError.ifBlank { "此文件没有内嵌歌词" } else "选择一份歌词", modifier = Modifier.weight(1f))
            else LyricTimelineText(document, state, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Caption(if (state.previewCurrent) "文件内嵌歌词" else state.selected?.source?.label.orEmpty(), Modifier.weight(1f))
                NativeUi.Button("时间校准", quiet = true) { state.previewTiming = !state.previewTiming }
                NativeUi.Button("应用内容", quiet = true) { state.applyOptions = !state.applyOptions }
                NativeUi.Button("导出", document != null, quiet = true, click = export)
                NativeUi.Button("编辑", document != null && !state.busy, quiet = true) { state.editPreview(parsed?.getOrNull()?.first) }
                NativeUi.Button("应用歌词", document != null && !state.busy, primary = true) {
                    if (value?.text != null) {
                        val loaded = DownloadedLyrics(value.text, value.source, value.translation, value.romanization)
                        val part = state.applyPart; val format = value.format; val offset = value.offset
                        val change: (SavedLyrics) -> SavedLyrics = { loaded.applyTo(it.copy(chinese = value.chinese, showTranslation = value.showTranslation, showRomanization = value.showRomanization, removeEmptyLines = value.removeEmptyLines, order = value.order), part, format, state.fallback).let { applied -> if (part == null || part == LyricsPart.ORIGINAL) applied.copy(offset = offset) else applied } }
                        state.draft = change(state.draft); commit(false, change)
                    }
                }
            }
            if (state.previewTiming) {
                val offset = state.previewOffset
                fun change(value: Long) {
                    val bounded = value.coerceIn(-60_000, 60_000)
                    state.previewOffset = bounded
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NativeUi.Caption("时间偏移", Modifier.weight(1f))
                    NativeUi.Button("−100 ms", !state.busy, quiet = true) { change(offset - 100) }
                    var input by remember(offset) { mutableStateOf(offset.toString()) }
                    Field(input, "毫秒", Modifier.width(88.dp)) { input = it }
                    NativeUi.Button("设定", !state.busy, quiet = true) { val value = input.toLongOrNull(); if (value == null || value !in -60_000..60_000) state.error = "偏移范围：−60000 至 60000 毫秒" else change(value) }
                    NativeUi.Button("+100 ms", !state.busy, quiet = true) { change(offset + 100) }
                    NativeUi.Button("重置", !state.busy, quiet = true) { change(0) }
                }
            }
        }
        if (state.applyOptions) NativeUi.Group {
            SettingChoice("应用内容", state.applyPart, listOf(null) + LyricsPart.entries, { it?.label ?: "整份歌词" }) { state.applyPart = it; state.applyOptions = false }
        }
        Spacer(Modifier.height(4.dp))
    }
}
private val thumbnailPermits = Semaphore(3)
@Composable private fun CoverThumbnail(candidate: CoverCandidate) {
    var image by remember(candidate.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(candidate.url) {
        thumbnailPermits.acquire()
        try {
            val thumbnail = if (candidate.url.contains("y.gtimg.cn/")) candidate.url.replace("R800x800", "R150x150")
                else if (candidate.source == CoverSource.NETEASE.label) candidate.url + if (candidate.url.contains('?')) "&param=160y160" else "?param=160y160"
                else if (candidate.source == CoverSource.ITUNES.label) candidate.url.replace("1200x1200bb", "160x160bb") else candidate.url
            val data = runInterruptible(Dispatchers.IO) { Covers.normalize(Network.bytes(java.net.URI.create(thumbnail), 1_048_576), candidate.source, 160) }
            image = loadImageBitmap(ByteArrayInputStream(data.bytes))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { }
        finally { thumbnailPermits.release() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(roundShape(6.dp)).background(NativeUi.palette().surface), contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!, candidate.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else NativeUi.Caption(candidate.title.take(1))
    }
}

@Composable fun CoverPage(state: StudioState, commit: (Boolean, (SavedLyrics) -> SavedLyrics) -> Unit,
    search: () -> Unit, load: (String) -> Unit, import: () -> Unit, save: () -> Unit) {
    LaunchedEffect(state.coverTab, state.preferences.coverSources) { if (state.coverTab == 0) search() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NativeUi.Group {
            SettingChoice("图片来源", state.coverTab, listOf(0, 1, 2), { listOf("在线搜索", "本地图片", "图片地址")[it] }) { state.coverTab = it }
            when (state.coverTab) {
                0 -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                        QueryField("歌曲名称", state.coverQuery, Modifier.weight(1f)) { state.coverQuery = it }
                        QueryField("歌手", state.coverArtist, Modifier.weight(0.65f)) { state.coverArtist = it }
                        NativeUi.Button(if (state.coverSearching) "搜索中…" else "搜索", !state.coverSearching, primary = true, click = search)
                    }
                    NativeUi.Item("搜索来源", state.preferences.coverSources.joinToString("、") { it.label }) { state.coverSourcesExpanded = !state.coverSourcesExpanded }
                    if (state.coverSourcesExpanded) CoverSource.entries.forEach { source ->
                        NativeUi.SettingSwitch(source.label, source in state.preferences.coverSources, !state.coverBusy && !state.coverSearching) { enabled ->
                            val next = if (enabled) state.preferences.coverSources + source else state.preferences.coverSources - source
                            if (next.isNotEmpty()) state.updatePreferences(state.preferences.copy(coverSources = next)) else state.error = "至少保留一个封面来源"
                        }
                    }
                }
                1 -> NativeUi.Item("选择本地图片", "PNG、JPG、WebP", import)
                2 -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(state.coverUrl, "https:// 图片地址", Modifier.weight(1f)) { state.coverUrl = it }
                    NativeUi.Button("加载", !state.coverBusy && state.coverUrl.isNotBlank()) { load(state.coverUrl.trim()) }
                }
            }
        }
        if (state.coverFailures.isNotEmpty()) NativeUi.Caption("连接失败：" + state.coverFailures.joinToString("、") { it.label }, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.coverTab == 0) NativeUi.Group(modifier = Modifier.weight(0.68f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    NativeUi.Text("搜索结果", Modifier.weight(1f), size = 13, bold = true)
                    NativeUi.Caption("${state.covers.size} 张" + if (state.coverSearching) " · 搜索中" else "")
                }
                if (state.covers.isEmpty()) EmptyState(if (state.coverSearching) "正在搜索…" else "暂无匹配封面", modifier = Modifier.weight(1f))
                else {
                    val density = LocalDensity.current.density
                    var width by remember { mutableFloatStateOf(650f) }
                    val columns = (width / 130).toInt().coerceIn(2, 6)
                    val groups = state.covers.chunked(columns)
                    val scroll = remember(state.coverQuery, state.coverArtist) { androidx.compose.foundation.lazy.LazyListState(0, 0) }
                    val palette = NativeUi.palette()
                    NativeUi.LazyRows(Modifier.weight(1f).fillMaxWidth().onSizeChanged { width = it.width / density }.padding(horizontal = 10.dp), scroll, groups.size) { index ->
                        val group = groups[index]
                        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.forEach { candidate -> key(candidate.url) {
                            val selected = state.selectedCoverUrl == candidate.url
                            val outline = if (selected) NativeUi.outline(Modifier, palette.accent, 6.dp) else Modifier
                            Column(outline.weight(1f).clickable(Role.Button) { load(candidate.url) }.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CoverThumbnail(candidate)
                                NativeUi.Text(candidate.title, size = 13, bold = selected, singleLine = true, systemFont = true)
                                NativeUi.Text(candidate.artist, size = 12, singleLine = true, color = palette.muted, systemFont = true)
                                NativeUi.Text(candidate.album.ifBlank { "—" }, size = 12, singleLine = true, color = palette.muted, systemFont = true)
                                NativeUi.Text(candidate.source, size = 11, color = if (selected) palette.accent else palette.muted, singleLine = true, systemFont = true)
                            }
                        } }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    } }
                }
            }
            Panel("封面预览", Modifier.weight(if (state.coverTab == 0) 0.32f else 1f).fillMaxHeight()) {
                val picture = state.picture
                val bitmap = if (picture == null) state.existingCover else remember(picture) { loadImageBitmap(ByteArrayInputStream(picture.bytes)) }
                Column(NativeUi.scrollModifier(Modifier.weight(1f).fillMaxWidth().padding(16.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.widthIn(max = 200.dp).fillMaxWidth().aspectRatio(1f).align(Alignment.CenterHorizontally).clip(roundShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (state.coverBusy) NativeUi.Caption("正在加载…")
                        else if (state.selectedCoverUrl.isNotEmpty() && picture == null) NativeUi.Caption("封面加载失败")
                        else if (bitmap != null) Image(bitmap, "封面预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else NativeUi.Caption("封面预览")
                    }
                    val selected = state.covers.firstOrNull { it.url == state.selectedCoverUrl }
                    if (selected != null) {
                        NativeUi.Text(selected.title, size = 16, bold = true, systemFont = true)
                        NativeUi.Text(selected.artist, size = 13, systemFont = true)
                        NativeUi.Caption(selected.album)
                        NativeUi.Caption(selected.source)
                    } else if (state.coverLabel.isNotEmpty()) NativeUi.Caption(state.coverLabel.take(70))
                    if (picture != null) NativeUi.Caption("${picture.width} × ${picture.height}")
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    NativeUi.Button("恢复原封面", state.saved.coverFile != null && !state.busy, quiet = true) {
                        state.draft = state.draft.copy(coverFile = null, coverSource = ""); state.picture = null; state.coverLabel = ""
                        commit(true) { it.copy(coverFile = null, coverSource = "") }
                    }
                    Spacer(Modifier.weight(1f))
                    NativeUi.Button(if (state.coverBusy) "加载中…" else "应用封面", picture != null && !state.busy && !state.coverBusy, primary = true, click = save)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
