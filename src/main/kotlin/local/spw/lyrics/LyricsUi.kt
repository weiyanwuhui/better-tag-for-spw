@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import com.xuncorp.voxzen.util.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

enum class StudioPage(val label: String) { SEARCH("搜索歌词"), EDIT("编辑歌词"), DISPLAY("设置"), COVER("更换封面"), TAGS("音乐标签"), AUTO("后台整理") }
class StudioState(media: MediaItem, initial: SavedLyrics, val fallback: String, page: StudioPage, font: Int = 32,
    preferences: LyricsPreferences = LyricsPreferences(format = initial.format),
    private val persistPreferences: (LyricsPreferences) -> Unit = {}) {
    var media by mutableStateOf(media)
    var preferences by mutableStateOf(preferences.copy(source = preferences.source?.takeIf { it in LyricSources.available(preferences.format) }))
        private set
    fun updatePreferences(value: LyricsPreferences) {
        try {
            val next = value.copy(source = value.source?.takeIf { it in LyricSources.available(value.format) })
            persistPreferences(next); preferences = next; sourceFilter = next.source; exportFormat = next.exportFormat
        } catch (failure: Exception) { error = failure.message ?: "设置保存失败" }
    }
    var page by mutableStateOf(page)
    private val history = mutableListOf<StudioPage>()
    fun navigate(destination: StudioPage) {
        if (destination == page || flushEditor?.invoke() == false) return
        if (destination in history) {
            while (history.last() != destination) history.removeAt(history.lastIndex)
            history.removeAt(history.lastIndex)
        } else history.add(page)
        page = destination
    }
    fun back(): Boolean {
        if (flushEditor?.invoke() == false) return true
        if (history.isEmpty()) return false
        page = history.removeAt(history.lastIndex); return true
    }
    var applyOptions by mutableStateOf(false)
    fun target() = SearchTarget(title, artist, album, media.duration)
    fun lyricTarget() = SearchTarget(title, artist, searchAlbum, media.duration)
    var tagResults by mutableStateOf(listOf<Candidate>())
    var tagPending by mutableIntStateOf(0)
    var tagSelected by mutableStateOf<Candidate?>(null)
    var tagOriginal by mutableStateOf<MusicTags?>(null)
    var tagValues by mutableStateOf(mapOf<MusicTag, String>())
    var tagFields by mutableStateOf(setOf<MusicTag>())
    var tagCover by mutableStateOf<CoverPicture?>(null)
    var tagBusy by mutableStateOf(false)
    var tagWriting by mutableStateOf(false)
    var tagDirty by mutableStateOf(false)
    var tagChoicesExpanded by mutableStateOf(true)
    var tagFailures by mutableStateOf(listOf<String>())
    var searchTags: () -> Unit = {}
    var fetchTags: (Candidate) -> Unit = {}
    var writeTags: () -> Unit = {}
    var autoProgress by mutableStateOf(AutoTagProgress())
    var scanAuto: () -> Unit = {}
    var pauseAuto: () -> Unit = {}
    var openAuto: () -> Unit = {}
    fun enterAuto() = navigate(StudioPage.AUTO)
    fun syncAutoSettings(value: AutoTagSettings) { preferences = preferences.copy(autoTags = value) }
    fun syncEntry(value: LyricsEntry) { preferences = preferences.copy(entry = value) }
    var saved by mutableStateOf(initial)
    var draft by mutableStateOf(initial)
    private var editorBaseline = initial
    private var editorPreviewKey: String? = null
    var embedded by mutableStateOf<SavedLyrics?>(null)
    var embeddedLoading by mutableStateOf(true)
    var embeddedError by mutableStateOf("")
    private fun lyricContent(value: SavedLyrics) = listOf(value.text, value.translation, value.romanization)
    fun hasUnsavedLyrics() = editorDirty || documentDirty || lyricContent(draft) != lyricContent(editorBaseline)
    fun committed(value: SavedLyrics, change: (SavedLyrics) -> SavedLyrics) {
        saved = value; editorBaseline = change(editorBaseline)
    }
    fun previewKey() = if (previewCurrent) "embedded" else selected?.key
    fun previewLyrics(): SavedLyrics? {
        val key = previewKey() ?: return null
        if (editorPreviewKey == key) return draft.copy(offset = previewOffset)
        return if (previewCurrent) embedded?.copy(offset = previewOffset) else downloaded?.takeIf { it.matches(preferences.format) }?.let {
            preferences.applyTo(saved).copy(text = it.text, source = it.source, translation = it.translation, romanization = it.romanization, offset = previewOffset)
        }
    }
    fun lyricsForExport() = if (page == StudioPage.SEARCH) previewLyrics() ?: error("暂无可导出的歌词") else draft
    fun editPreview(sourceDocument: LyricsDocument? = null) {
        val value = previewLyrics() ?: return
        if (editorPreviewKey != previewKey()) {
            if (hasUnsavedLyrics()) { error = "请先应用当前编辑的歌词"; return }
            draft = value; editorBaseline = value; editorPreviewKey = previewKey()
            timing.clear(); timingRevision++; documentText = null; documentDirty = false
            document = sourceDocument ?: LyricsCodec.source(value, ""); line = 0; editorTab = 0
            timing.mode = if (document?.hasWords == true) TimingMode.WORD else TimingMode.LINE
        }
        navigate(StudioPage.EDIT)
    }
    var document by mutableStateOf<LyricsDocument?>(null)
    var documentText: String? = null
    var documentDirty by mutableStateOf(false)
    val timing = LyricsTiming()
    var timingRevision by mutableIntStateOf(0)
    var timingPlayback: TimingPlayback? = null
    var timingClock by mutableStateOf(TimingClock())
    var timingAvailable by mutableStateOf(false)
    var timingLoop by mutableStateOf(false)
    var timingStopAt by mutableStateOf<Long?>(null)
    fun changeDocument(value: LyricsDocument, selected: Int = line, encode: Boolean = true) {
        LyricsCodec.validate(value)
        if (encode) {
            val text = LyricsCodec.ttml(value)
            documentText = text; draft = draft.copy(text = text, source = "手动编辑", translation = null, romanization = null)
        }
        document = value; documentDirty = !encode
        line = selected.coerceIn(0, value.rows.lastIndex.coerceAtLeast(0)); editorDirty = false
    }
    var parseError by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf("")
    var font by mutableIntStateOf(font)
    var committedFont by mutableIntStateOf(font)
    var title by mutableStateOf(media.title)
    var artist by mutableStateOf(media.artist)
    var album by mutableStateOf("")
    var searchAlbum by mutableStateOf("")
    var sourceFilter by mutableStateOf(preferences.source?.takeIf { it in LyricSources.available(preferences.format) })
    var typography by mutableStateOf(false)
    var fontWeight by mutableIntStateOf(NativeHost.fontWeight())
    var alignment by mutableIntStateOf(NativeHost.alignment())
    var results by mutableStateOf(listOf<Candidate>())
    var sourceCounts by mutableStateOf<Map<LyricSource, Int>>(emptyMap())
    var pending by mutableIntStateOf(0)
    var failures by mutableStateOf(mapOf<LyricSource, String>())
    var selected by mutableStateOf<Candidate?>(null)
    var downloaded by mutableStateOf<DownloadedLyrics?>(null)
    var downloading by mutableStateOf(false)
    var editorTab by mutableIntStateOf(0)
    var editorDirty by mutableStateOf(false)
    var flushEditor: (() -> Boolean)? = null
    var exportFormat by mutableStateOf(preferences.exportFormat)
    var applyPart by mutableStateOf<LyricsPart?>(null)
    var searched by mutableStateOf(false)
    var searchFormat by mutableStateOf(preferences.format)
    var searchSource: LyricSource? = null
    var searchEnabled = preferences.enabled
    var previewTiming by mutableStateOf(false)
    var previewOffset by mutableLongStateOf(0)
    var previewCurrent by mutableStateOf(true)
    var position by mutableLongStateOf(0)
    var line by mutableIntStateOf(0)
    var coverQuery by mutableStateOf(media.title)
    var coverArtist by mutableStateOf(media.artist)
    var coverSourcesExpanded by mutableStateOf(false)
    var coverFailures by mutableStateOf(setOf<CoverSource>())
    var covers by mutableStateOf(listOf<CoverCandidate>())
    var picture by mutableStateOf<CoverPicture?>(null)
    var coverUrl by mutableStateOf("")
    var coverBusy by mutableStateOf(false)
    var coverSearching by mutableStateOf(false)
    var selectedCoverUrl by mutableStateOf("")
    var coverLabel by mutableStateOf("")
    var coverTab by mutableIntStateOf(0)
    var existingCover by mutableStateOf<ImageBitmap?>(null)
}

class LyricsHook(private val plugin: LyricsPlugin, private val native: java.lang.reflect.Method = NativeHost.originalPlayer) : ComposableHook() {
    @Composable override fun Content(call: UiHookCall) {
        if (!plugin.enabled.value) { original(call, call.arguments[0] as Modifier); return }
        val media = plugin.media.value
        var menu by remember { mutableStateOf<Offset?>(null) }
        LaunchedEffect(media?.id, plugin.entry.value) { menu = null }
        val modifier = (call.arguments[0] as Modifier).onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (plugin.entry.value == LyricsEntry.CONTEXT && event.buttons.isSecondaryPressed) {
                if (media != null && NativeHost.currentId() == media.id) menu = event.changes.firstOrNull()?.position
                else plugin.notify("请先播放歌曲并等待歌词加载", true)
                event.changes.forEach { it.consume() }
            }
        }
        Box(modifier, propagateMinConstraints = true) {
            original(call, Modifier.fillMaxSize())
            val point = menu
            if (media != null && point != null) LyricsEntryMenu(plugin, remember(point) { CursorMenuPosition(point) }, { menu = null }, LyricsEntry.CONTEXT)
            val request = plugin.studio.value
            if (request?.owner == LyricsEntry.CONTEXT) key(request) { StudioWindow(plugin, request.media, request.page) { plugin.studio.value = null } }
        }
    }
    @Composable private fun original(call: UiHookCall, modifier: Modifier) {
        NativeHost.invoke(native, call.thisObject, modifier, call.arguments[1], currentComposer, 0, 0)
    }
}
class CursorMenuPosition(private val point: Offset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset((anchorBounds.left + point.x.roundToInt()).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top + point.y.roundToInt()).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
}
@Composable fun StudioWindow(plugin: LyricsPlugin, media: MediaItem, initialPage: StudioPage, dismiss: () -> Unit) {
    val originalCover = remember { (NativeHost.currentCoverOwner() as? com.xuncorp.voxzen.image.RefCountedImageBitmap)?.acquire() }
    val state = remember {
        val preferences = runCatching { plugin.preferences.load() }.getOrElse {
            plugin.notify("歌词设置读取失败，暂用默认设置", true); LyricsPreferences()
        }
        StudioState(media, plugin.store.get(media.id), NativeHost.currentRaw().takeIf { NativeHost.currentId() == media.id }.orEmpty(), initialPage, NativeHost.fontSize(),
            preferences, plugin.preferences::save).also { it.existingCover = originalCover?.bitmap }
    }
    val scope = rememberCoroutineScope()
    val sources = remember { LyricSources() }
    state.scanAuto = plugin.autoTags::scanNow
    state.pauseAuto = plugin.autoTags::togglePause
    state.openAuto = { if (state.flushEditor?.invoke() != false) state.enterAuto() }
    DisposableEffect(plugin, state) {
        val open = state.openAuto
        plugin.backgroundPage = open
        onDispose { if (plugin.backgroundPage === open) plugin.backgroundPage = null }
    }
    LaunchedEffect(plugin) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        (plugin.autoTags.status as kotlinx.coroutines.flow.Flow<AutoTagProgress>).sample(150).collect { state.autoProgress = it }
    }
    LaunchedEffect(plugin) { (plugin.autoTags.configuration as kotlinx.coroutines.flow.Flow<AutoTagSettings>).collect { state.syncAutoSettings(it) } }
    LaunchedEffect(plugin.entry.value) { state.syncEntry(plugin.entry.value) }
    LaunchedEffect(media.id) {
        try {
            val text = runInterruptible(Dispatchers.IO) { TagFiles.readLyrics(TagFiles.localPath(media.path)) }
            state.embedded = text.takeIf(String::isNotBlank)?.let { SavedLyrics(text = it, source = "文件内嵌歌词", removeEmptyLines = false) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { state.embeddedError = failure.message ?: "内嵌歌词读取失败" }
        finally { state.embeddedLoading = false }
    }
    LaunchedEffect(media.id) {
        try {
            val full = runInterruptible(Dispatchers.IO) {
                com.xuncorp.spw.workshop.api.WorkshopApi.library.getTrackById(media.id).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS)
            }
            if (full != null && full.id == media.id) state.media = full
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { }
    }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var tagSearchJob by remember { mutableStateOf<Job?>(null) }
    var coverLoadJob by remember { mutableStateOf<Job?>(null) }
    var tagFetchJob by remember { mutableStateOf<Job?>(null) }
    fun fetchTags(candidate: Candidate) {
        if (state.tagWriting || state.busy) return
        tagFetchJob?.cancel()
        state.tagSelected = candidate; state.tagBusy = true; state.error = ""
        tagFetchJob = scope.launch {
            try {
                val fetched = runInterruptible(Dispatchers.IO) { TagSources().fetch(candidate) }
                ensureActive()
                if (state.tagSelected?.key == candidate.key) {
                    state.tagValues = state.tagOriginal?.values.orEmpty() + fetched.values
                    state.tagDirty = false
                    state.tagChoicesExpanded = false
                    state.tagFields = fetched.values.keys.intersect(state.preferences.tagFields)
                    state.tagCover = fetched.cover; state.status = fetched.notice.ifBlank { "已获取标签" }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { state.error = failure.message ?: "标签获取失败" }
            finally { if (isActive) state.tagBusy = false }
        }
    }
    fun searchTags() {
        if (state.tagBusy || state.busy) return
        if (state.title.isBlank()) { state.error = "请输入歌名"; return }
        tagSearchJob?.cancel(); tagFetchJob?.cancel()
        val target = state.target(); val preference = state.preferences
        val available = preference.enabled.filter { preference.source == null || preference.source == it }
        state.tagResults = emptyList(); state.tagSelected = null; state.tagFailures = emptyList(); state.tagPending = available.size; state.tagChoicesExpanded = true
        tagSearchJob = scope.launch {
            supervisorScope { available.forEach { source -> launch {
                try {
                    val found = runInterruptible(Dispatchers.IO) { sources.search(source, target.title, target.artist) }
                    ensureActive(); state.tagResults = (state.tagResults + found).distinctBy { it.key }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { state.tagFailures = state.tagFailures + "${source.label}连接失败" }
                finally { if (isActive) state.tagPending-- }
            } } }
            if (state.tagSelected == null) SearchRanking.rank(state.tagResults, target).firstOrNull()?.let { fetchTags(it.candidate) }
        }
    }
    state.searchTags = ::searchTags
    state.fetchTags = ::fetchTags
    state.writeTags = {
        if (!state.tagBusy && !state.busy) scope.launch {
            state.tagBusy = true; state.tagWriting = true; state.error = ""; state.status = ""
            try {
                check(com.xuncorp.spw.workshop.api.WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.LIBRARY_WRITE)) { "请在插件管理中授权媒体库写入" }
                check(NativeHost.currentId() == media.id) { "歌曲已切换，请重新打开音乐标签" }
                val values = state.tagValues.filterKeys { it in state.tagFields }
                val picture = if (state.preferences.tagCover) state.tagCover else null
                val applied = state.saved
                val format = state.preferences.exportFormat
                val includeLyrics = state.preferences.tagLyrics
                val backup = withContext(NonCancellable + Dispatchers.IO) {
                    val lyrics = if (includeLyrics && !(applied.text ?: state.fallback).isBlank()) LyricsCodec.export(LyricsCodec.render(LyricsCodec.source(applied, state.fallback), applied), format, applied.order) else null
                    checkNotNull(TagFiles.write(TagFiles.localPath(media.path), values, picture, lyrics, plugin.tagBackups) { stage, target -> plugin.autoTagStore.protectWrite(media.id, target) { replaceTagFile(stage, target, NativeTagPlayback(media.id)) } })
                }
                state.tagOriginal = runInterruptible(Dispatchers.IO) { TagFiles.read(TagFiles.localPath(media.path)) }
                if (includeLyrics) {
                    val embedded = runInterruptible(Dispatchers.IO) { TagFiles.readLyrics(TagFiles.localPath(media.path)) }
                    state.embedded = embedded.takeIf(String::isNotBlank)?.let { SavedLyrics(text = it, source = "文件内嵌歌词", removeEmptyLines = false) }
                }
                state.tagFields = emptySet()
                state.tagDirty = false
                state.status = "标签已写入 · 原文件已备份"
                try {
                    NativeHost.refreshWrittenTags(state.media, values)
                    if (picture != null && NativeHost.currentId() == media.id) {
                        val coverFile = runInterruptible(Dispatchers.IO) { Covers.persist(plugin.store.coversDirectory, picture) }
                        runInterruptible(Dispatchers.IO) { plugin.update(media.id, true) { it.copy(coverFile = coverFile, coverSource = picture.source) } }
                        state.saved = plugin.store.get(media.id); state.draft = state.draft.copy(coverFile = coverFile, coverSource = picture.source)
                    }
                } catch (_: Exception) { state.status = "标签已写入，媒体库暂未刷新 · 备份：${backup.fileName}" }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                state.error = failure.message ?: "标签写入失败，原文件已保留"
                withContext(Dispatchers.IO) { runCatching {
                    java.nio.file.Files.createDirectories(plugin.tagBackups)
                    java.nio.file.Files.writeString(plugin.tagBackups.resolve("write-errors.log"), "${java.time.Instant.now()}\n${failure.stackTraceToString()}\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                } }
            }
            finally { state.tagBusy = false; state.tagWriting = false }
        }
    }
    LaunchedEffect(state.page) {
        if (state.page == StudioPage.TAGS && state.tagOriginal == null) {
            try {
                val original = runInterruptible(Dispatchers.IO) { TagFiles.read(TagFiles.localPath(media.path)) }
                state.tagOriginal = original
                if (state.tagSelected == null) { state.tagValues = original.values; state.tagCover = original.cover }
            } catch (failure: Exception) { state.error = failure.message ?: "无法读取本地音频标签" }
        }
    }
    var closing by remember { mutableStateOf(false) }
    fun close() { if (!state.busy && !state.tagWriting) { if (state.hasUnsavedLyrics() || state.font != state.committedFont || state.tagDirty) closing = true else dismiss() } }
    fun commit(cover: Boolean, change: (SavedLyrics) -> SavedLyrics) {
        if (state.busy) return
        scope.launch {
            state.busy = true; state.error = ""; state.status = ""
            try {
                val updated = withContext(NonCancellable + Dispatchers.IO) { plugin.update(media.id, cover, change) }
                state.committed(updated, change)
                if (!cover && updated.lyricsInFile) {
                    val text = runInterruptible(Dispatchers.IO) { TagFiles.readLyrics(TagFiles.localPath(media.path)) }
                    state.embedded = text.takeIf(String::isNotBlank)?.let { SavedLyrics(text = it, source = "文件内嵌歌词", removeEmptyLines = false) }
                    state.embeddedError = ""
                }
                state.status = if (!cover && updated.lyricsInFile) "歌词已写入文件并刷新" else "已保存并刷新"
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { state.error = failure.message ?: "保存失败" }
            finally { state.busy = false }
        }
    }
    fun search() {
        if (state.title.isBlank()) { state.error = "请输入歌名"; return }
        searchJob?.cancel(); previewJob?.cancel()
        val title = state.title; val artist = state.artist; val format = state.preferences.format
        state.searchFormat = format; state.searched = true
        state.results = emptyList(); state.sourceCounts = emptyMap(); state.failures = emptyMap(); state.selected = null; state.downloaded = null; state.previewCurrent = true
        state.searchSource = state.sourceFilter
        val available = LyricSources.available(format).filter { it in state.preferences.enabled && (state.sourceFilter == null || it == state.sourceFilter) }
        state.downloading = false; state.pending = available.size; state.error = ""
        searchJob = scope.launch { supervisorScope { available.forEach { source -> launch {
            try {
                val found = sources.searchTyped(source, title, artist, format)
                ensureActive()
                if (state.preferences.format == format) { state.results = (state.results + found).distinctBy { it.key }; state.sourceCounts = state.sourceCounts + (source to found.size) }
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { state.failures = state.failures + (source to (failure.message ?: "请求失败")) }
            finally { if (isActive) state.pending-- }
        } } } }
    }
    fun preview(candidate: Candidate) {
        val format = state.preferences.format
        previewJob?.cancel(); state.previewOffset = 0; state.selected = candidate; state.downloaded = null; state.downloading = true; state.error = ""; state.previewCurrent = false
        previewJob = scope.launch {
            try {
                val loaded = runInterruptible(Dispatchers.IO) { sources.download(candidate, format) }
                ensureActive()
                require(loaded.matches(format)) { "此结果没有${format.shortLabel}数据" }
                if (state.preferences.format == format && state.selected?.key == candidate.key) state.downloaded = loaded
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { state.error = failure.message ?: "获取歌词失败" }
            finally { if (isActive) state.downloading = false }
        }
    }
    fun coverSearch() {
        if (state.coverSearching) return
        if (state.coverQuery.isBlank()) { state.error = "请输入歌名"; return }
        val query = listOf(state.coverQuery.trim(), state.coverArtist.trim()).filter(String::isNotBlank).joinToString(" ")
        val target = SearchTarget(state.coverQuery, state.coverArtist, state.media.album, state.media.duration)
        val enabled = state.preferences.coverSources
        state.covers = emptyList(); state.coverFailures = emptySet(); state.coverSearching = true
        scope.launch {
            state.error = ""
            try { supervisorScope { enabled.forEach { source -> launch {
                try {
                    val found = runInterruptible(Dispatchers.IO) { Covers.search(source, query) }
                    ensureActive()
                    state.covers = Covers.rank(state.covers + found, target)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { state.coverFailures = state.coverFailures + source }
            } } } } finally { state.coverSearching = false }
        }
    }

    fun loadCover(url: String) {
        coverLoadJob?.cancel()
        coverLoadJob = scope.launch {
            state.coverBusy = true; state.error = ""; state.selectedCoverUrl = url; state.picture = null
            state.coverLabel = state.covers.firstOrNull { it.url == url }?.let { listOf(it.title, it.artist, it.album, it.source).filter(String::isNotBlank).joinToString(" · ") } ?: "图片地址"
            try {
                val picture = runInterruptible(Dispatchers.IO) { Covers.normalize(Network.bytes(java.net.URI.create(url)), "在线封面 · $url") }
                ensureActive(); state.picture = picture
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { state.error = failure.message ?: "封面下载失败" }
            finally { if (isActive) state.coverBusy = false }
        }
    }
    LaunchedEffect(state.page, state.preferences.format, state.preferences.enabled, state.sourceFilter) {
        if (state.searchFormat != state.preferences.format || state.searchSource != state.sourceFilter || state.page == StudioPage.SEARCH && state.preferences.enabled != state.searchEnabled) {
            searchJob?.cancel(); previewJob?.cancel(); state.pending = 0; state.downloading = false
            state.results = emptyList(); state.selected = null; state.downloaded = null; state.searched = false
            state.searchFormat = state.preferences.format
            state.searchEnabled = state.preferences.enabled
        }
        if (state.page == StudioPage.SEARCH && !state.searched) search()
    }
    LaunchedEffect(state.draft.text, state.draft.translation, state.draft.romanization) {
        if (state.documentDirty) return@LaunchedEffect
        if (state.documentText != null && state.draft.text == state.documentText && state.draft.translation == null && state.draft.romanization == null) return@LaunchedEffect
        delay(180)
        try {
            val draft = state.draft
            state.document = runInterruptible(Dispatchers.IO) { LyricsCodec.source(draft, state.fallback) }; state.parseError = ""
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { state.document = null; state.parseError = failure.message ?: "歌词解析失败" }
    }
    LaunchedEffect(state.saved.coverFile) {
        val file = state.saved.coverFile
        if (file != null && state.picture == null) {
            try {
                state.picture = runInterruptible(Dispatchers.IO) { Covers.file(plugin.store.coversDirectory.resolve(file)) }
                state.coverLabel = "当前封面"
            } catch (_: Exception) { }
        }
    }
    DisposableEffect(Unit) { onDispose { AppConfig.updateLyricsFontSize(state.committedFont); originalCover?.close() } }
    LaunchedEffect(state) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        NativeHost.lyricsTime().sample(100).collect { state.position = it }
    }
    LaunchedEffect(state) { state.timingPlayback = NativeTimingPlayback(media.id) }
    DialogWindow(onCloseRequest = ::close, title = "Better Tag for SPW",
        state = rememberDialogState(width = 1120.dp, height = 800.dp), resizable = true) {
        SideEffect { window.minimumSize = java.awt.Dimension(800, 640) }
        DisposableEffect(window, state) {
            val keys = TimingWindowKeys(window, state)
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
            onDispose { java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys) }
        }
        NativeUi.AppTheme {
        StudioBody(state, ::commit, ::search, ::preview, ::coverSearch, ::loadCover,
            importLyrics = { scope.launch {
                val path = LyricsFiles.choose(false, "导入歌词", "", listOf("lrc", "spl", "yrc", "krc", "ttml", "txt")) ?: return@launch
                try {
                    val (raw, document) = runInterruptible(Dispatchers.IO) {
                        val raw = LyricsFiles.read(path)
                        val document = try { LyricsCodec.parse(raw) } catch (failure: Exception) {
                            if (path.fileName.toString().endsWith(".txt", true)) plainTimingDocument(raw) else throw failure
                        }
                        raw to document
                    }
                    val plain = document.kind == "待打轴文本"
                    val text = if (plain) LyricsCodec.ttml(document) else raw
                    state.timing.clear(); state.timingRevision++; state.documentText = null; state.documentDirty = false
                    state.draft = state.draft.copy(text = text, source = "本地文件 · ${path.fileName}", translation = null, romanization = null, offset = if (plain) 0 else state.draft.offset)
                    state.document = document; state.documentText = text; state.parseError = ""
                    state.timing.mode = if (document.hasWords || plain && state.preferences.format != LyricsFormat.LINE) TimingMode.WORD else TimingMode.LINE
                    state.navigate(StudioPage.EDIT); state.editorTab = if (plain) 1 else 0; state.status = "已导入，待应用"
                } catch (failure: Exception) { state.error = failure.message ?: "文件读取失败" }
            } }, exportLyrics = { scope.launch {
                try {
                    if (state.flushEditor?.invoke() == false) return@launch
                    val value = state.lyricsForExport()
                    val text = runInterruptible(Dispatchers.IO) { LyricsCodec.export(LyricsCodec.render(LyricsCodec.source(value, state.fallback), value.copy(format = state.exportFormat)), state.exportFormat, value.order) }
                    val extension = state.exportFormat.extension
                    val name = media.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".$extension"
                    val path = LyricsFiles.choose(true, "导出歌词", name, listOf(extension)) ?: return@launch
                    runInterruptible(Dispatchers.IO) { LyricsFiles.write(path, text) }; state.status = "已导出 ${path.fileName}"
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { state.error = failure.message ?: "导出失败" }
            } }, importCover = { scope.launch {
                val path = LyricsFiles.choose(false, "选择封面", "", listOf("png", "jpg", "jpeg", "webp", "bmp", "gif")) ?: return@launch
                try { state.picture = runInterruptible(Dispatchers.IO) { Covers.file(path) }; state.coverLabel = path.fileName.toString(); state.selectedCoverUrl = "" }
                catch (failure: Exception) { state.error = failure.message ?: "图片读取失败" }
            } }, saveCover = { val picture = state.picture
                if (picture != null && !state.busy) scope.launch {
                    try {
                        val file = runInterruptible(Dispatchers.IO) { Covers.persist(plugin.store.coversDirectory, picture) }
                        state.tagCover = picture
                        state.draft = state.draft.copy(coverFile = file, coverSource = picture.source)
                        commit(true) { it.copy(coverFile = file, coverSource = picture.source) }
                    } catch (failure: Exception) { state.error = failure.message ?: "封面保存失败" }
                }
            }, saveFont = {
                try { NativeHost.fontSize(state.font); state.committedFont = state.font; state.status = "字号已保存" }
                catch (failure: Exception) { state.font = state.committedFont; state.error = failure.message ?: "字号保存失败" }
            }, close = ::close)
        if (closing) NativeUi.Dialog("放弃更改？", "当前内容有未保存的编辑。", "放弃更改", { closing = false }, dismiss) { }
        }
    }
}

@Composable fun StudioBody(state: StudioState, commit: (Boolean, (SavedLyrics) -> SavedLyrics) -> Unit,
    search: () -> Unit, preview: (Candidate) -> Unit, coverSearch: () -> Unit, loadCover: (String) -> Unit,
    importLyrics: () -> Unit, exportLyrics: () -> Unit, importCover: () -> Unit, saveCover: () -> Unit,
    saveFont: () -> Unit, close: () -> Unit) {
    fun back() {
        if (!state.back()) close()
    }
    NativeUi.Screen(state.page.label, if (state.page == StudioPage.AUTO) "曲库" else state.media.title, ::back, tools = {
        if (state.page != StudioPage.DISPLAY) NativeUi.Button("设置", quiet = true) { state.navigate(StudioPage.DISPLAY) }
        if (state.page != StudioPage.TAGS) NativeUi.Button("音乐标签", quiet = true) { state.navigate(StudioPage.TAGS) }
        if (state.page != StudioPage.SEARCH) NativeUi.Button("搜索歌词", quiet = true) { state.navigate(StudioPage.SEARCH) }
        if (state.page != StudioPage.COVER) NativeUi.Button("封面", quiet = true) { state.navigate(StudioPage.COVER) }
    }) {
        Column(Modifier.fillMaxSize().background(NativeUi.palette().background)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.page) {
                    StudioPage.SEARCH -> SearchPage(state, commit, search, preview, exportLyrics)
                    StudioPage.EDIT -> EditorPage(state, commit, importLyrics, exportLyrics)
                    StudioPage.DISPLAY -> DisplayPage(state, commit, saveFont, importLyrics, exportLyrics)
                    StudioPage.COVER -> CoverPage(state, commit, coverSearch, loadCover, importCover, saveCover)
                    StudioPage.TAGS -> TagsPage(state)
                    StudioPage.AUTO -> AutoTagsContent(state.preferences.autoTags, state.autoProgress, { state.updatePreferences(state.preferences.copy(autoTags = it)) }, state.scanAuto, state.pauseAuto)

                }
            }
            val message = when { state.error.isNotEmpty() -> state.error; state.busy -> "正在保存…"; else -> state.status }
            if (message.isNotBlank() && state.page != StudioPage.EDIT) NativeUi.Caption(message, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            if (state.page != StudioPage.AUTO) AutoTagStatusStrip(state.preferences.autoTags, state.autoProgress, state.openAuto)
        }
    }
}

@Composable fun Rule() { Box(Modifier.fillMaxWidth().height(1.dp).background(NativeUi.palette().stroke)) }
val LyricsFormat.shortLabel: String get() = when (this) {
    LyricsFormat.AUTO -> "自动"; LyricsFormat.LINE -> "逐行"; LyricsFormat.WORD -> "逐字"; LyricsFormat.ENHANCED -> "增强逐字"; LyricsFormat.TTML -> "TTML"
}
@Composable fun Field(value: String, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true, enabled: Boolean = true, change: (String) -> Unit) {
    NativeUi.Edit(value, hint, singleLine, modifier, enabled, change)
}
@Composable fun EmptyState(title: String, detail: String = "", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeUi.Caption(title)
            if (detail.isNotEmpty()) NativeUi.Caption(detail)
        }
    }
}
@Composable fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    NativeUi.Group(title, modifier) { content() }
}
@Composable fun <T> SettingChoice(title: String, value: T, values: List<T>, label: (T) -> String, change: (T) -> Unit) {
    NativeUi.Select(title, value, values, label, change)
}
@Composable fun SaveButton(state: StudioState, label: String, save: () -> Unit) { NativeUi.Button(if (state.busy) "保存中…" else label, !state.busy, primary = true, click = save) }
@Composable fun LyricsPreview(document: LyricsDocument?, modifier: Modifier = Modifier, order: List<LyricsPart> = LyricsPart.entries, font: Int = 19, position: Long = 0) {
    if (document == null) { EmptyState("暂无歌词", modifier = modifier); return }
    var follow by remember(document) { mutableStateOf(true) }
    var manualTime by remember(document) { mutableLongStateOf(0) }
    var timeline by remember { mutableStateOf(false) }
    val now = if (follow) position else manualTime
    val active = activeLyricRow(document, now)
    val rowOffsets = remember(document) { mutableStateMapOf<Int, Int>() }
    val palette = NativeUi.palette()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NativeUi.Caption("${document.kind.replace(" 逐字", "").replace("逐字 ", "").replace("逐行 ", "")} · ${if (document.hasWords) "逐字" else "逐行"}", Modifier.weight(1f))
            NativeUi.Button(if (timeline) "歌词" else "逐字时间", document.hasWords, quiet = true) { timeline = !timeline }
            NativeUi.Button("跟随播放", quiet = !follow) { follow = !follow; manualTime = position }
        }
        Column(NativeUi.scrollModifier(Modifier.weight(1f).fillMaxWidth(), rowOffsets[(active - 1).coerceAtLeast(0)]).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            document.rows.indices.forEach { index ->
                val row = document.rows[index]
                Column(Modifier.fillMaxWidth().onGloballyPositioned { rowOffsets[index] = it.positionInParent().y.roundToInt() }.clickable(Role.Button) { manualTime = row.start; follow = false }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (timeline) NativeUi.Caption(LyricsCodec.time(row.start) + " — " + LyricsCodec.time(row.end))
                    LyricsCodec.parts(row, order).forEach { (part, text) ->
                        if (part == LyricsPart.ORIGINAL) {
                            NativeUi.Text(previewWords(row, index == active, now, palette), size = font)
                        } else NativeUi.Caption(text)
                    }
                    if (timeline && row.hasWords) NativeUi.Caption(row.words.joinToString("  ") { "${it.text} ${LyricsCodec.time(it.start)}" })
                }
            }
        }
        NativeUi.TimeSlider(now, document.rows.maxOfOrNull { it.end } ?: 1) { manualTime = it; follow = false }
    }
}
fun activeLyricRow(document: LyricsDocument, position: Long): Int = document.rows.indexOfLast { !it.background && it.start <= position }.coerceAtLeast(0)
fun previewWords(row: LyricRow, active: Boolean, position: Long, palette: NativeUi.Palette) = androidx.compose.ui.text.buildAnnotatedString {
    row.words.forEach { word ->
        val color = if (active && position >= word.start) palette.accent else if (active) palette.text else palette.muted
        pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
        append(word.text); pop()
    }
}
fun auxiliaryText(document: LyricsDocument, part: LyricsPart): String = document.rows.joinToString("\n") {
    "[${LyricsCodec.time(it.start)}]" + if (part == LyricsPart.TRANSLATION) it.translation else it.romanization
}

