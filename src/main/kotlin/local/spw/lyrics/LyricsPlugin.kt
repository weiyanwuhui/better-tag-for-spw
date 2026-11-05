@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package local.spw.lyrics

import androidx.compose.runtime.*
import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.*
import java.awt.EventQueue
import kotlinx.coroutines.*

class LyricsPlugin(context: PluginContext) : SpwPlugin(context) {
    val enabled = mutableStateOf(false)
    val entry = mutableStateOf(LyricsEntry.CONTROLS)
    val studio = mutableStateOf<StudioRequest?>(null)
    val media = mutableStateOf<PlaybackExtensionPoint.MediaItem?>(null)
    lateinit var store: LyricsStore
        private set
    lateinit var preferences: LyricsPreferencesStore
        private set
    lateinit var tagBackups: java.nio.file.Path
        private set
    lateinit var autoTagStore: AutoTagStore
        private set
    lateinit var autoTags: AutoTagger
        private set
    private var covers: CoverController? = null
    private var autoWindow: AutoTagsWindow? = null
    private var lyricMigration: Job? = null
    var backgroundPage: (() -> Unit)? = null
    val revision = mutableIntStateOf(0)
    @Volatile var lyricTarget: Pair<String, SearchTarget>? = null
        private set
    fun loaded(media: PlaybackExtensionPoint.MediaItem) { lyricTarget = media.id to SearchTarget(media.title, media.artist, media.album, media.duration); playbackChanged() }
    fun playbackChanged() { if (enabled.value && ::autoTags.isInitialized) autoTags.playbackChanged() }
    @Volatile private var interludeCountdown = false
    val countdownSettings = mutableStateOf(CountdownSettings())
    private val hooks = mutableListOf<HookHandle>()

    override fun start() {
        if (hooks.isNotEmpty()) stop()
        for (permission in listOf(PluginPermission.CLASS_TRANSFORM, PluginPermission.LIBRARY_READ))
            check(WorkshopApi.manager.isPermissionGranted(permission)) { "Better Tag for SPW需要授权：$permission" }
        try {
            NativeUi.validate()
            val configDirectory = WorkshopApi.manager.createConfigManager().getConfig().getConfigPath().parent
            AmllCatalog.shared = AmllCatalog(configDirectory.resolve("lyrics-cache"))
            store = LyricsStore(configDirectory.resolve("tracks"))
            tagBackups = configDirectory.resolve("tag-backups")
            autoTagStore = AutoTagStore(configDirectory.resolve("auto-tags"))
            var legacyImported = false
            autoTags = AutoTagger(autoTagStore, tracks = {
                if (!legacyImported) { autoTagStore.importLegacy(tagBackups); legacyImported = true }
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                    WorkshopApi.library.getAllTracks().toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS)
                }
            }, protected = { store.hasSaved(it.id) }, current = NativeHost::isCurrentTrack,
                refresh = { tracks -> NativeHost.refreshTagsQuietly(tracks.map { it.path }) },
                libraryChanges = NativeHost::libraryChanges,
                canWrite = { enabled.value && WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_WRITE) })
            preferences = LyricsPreferencesStore(configDirectory.resolve("lyrics-preferences.properties")) {
                EventQueue.invokeLater { entry.value = it.entry }
                autoTags.configure(it.autoTags)
                if (interludeCountdown != it.interludeCountdown || countdownSettings.value != it.countdown) {
                    val rebuild = interludeCountdown != it.interludeCountdown || countdownSettings.value.leadMillis != it.countdown.leadMillis || countdownSettings.value.gapMillis != it.countdown.gapMillis
                    interludeCountdown = it.interludeCountdown
                    countdownSettings.value = it.countdown
                    if (rebuild) EventQueue.invokeLater { if (enabled.value) NativeHost.currentId()?.let(NativeHost::reload) }
                }
            }
            autoWindow = AutoTagsWindow(this)
            covers = CoverController(this)
            active = this
            enabled.value = true
            NativeUi.validate()
            NativePlayerAccess.validate()
            hooks += WorkshopApi.hookRegistrar.replaceComposable(NativeHost.PLAYER, NativeHost.originalPlayer.name, NativeHost.PLAYER_CALLER,
                listOf("androidx.compose.ui.Modifier", "boolean"), 50, LyricsHook(this))
            hooks += WorkshopApi.hookRegistrar.replaceComposable(LyricsControlsHook.OWNER, LyricsControlsHook.original.name, LyricsControlsHook.CALLER,
                listOf("androidx.compose.ui.Modifier"), 50, LyricsControlsHook(this))
            hooks += WorkshopApi.hookRegistrar.hookMethod(LyricsMoreMenuHook.original.declaringClass.name, LyricsMoreMenuHook.original.name,
                LyricsMoreMenuHook.parameters, LyricsMoreMenuHook(this))
            hooks += WorkshopApi.hookRegistrar.afterComposable(CountdownRenderer.native.declaringClass.name, CountdownRenderer.native.name, CountdownRenderer.caller,
                CountdownRenderer.parameters, 50, CountdownLineHook(countdownSettings))
            hooks += WorkshopApi.hookRegistrar.hookMethod(NativeHost.timelineCompanion.name, NativeHost.timelineMethod.name,
                listOf(NativeHost.documentType.name), object : MethodHook() {
                    override fun before(call: MethodHookParam) {
                        if (!enabled.value) return
                        val id = NativeHost.currentId() ?: return
                        NativeHost.applySettings(call.args[0]!!, store.get(id), interludeCountdown, lyricTarget?.takeIf { it.first == id }?.second, countdownSettings.value)
                    }
                })
            hooks += WorkshopApi.hookRegistrar.hookMethod(NativeHost.coverPublisher.declaringClass.name, NativeHost.coverPublisher.name,
                NativeHost.coverPublisher.parameterTypes.map { it.name }, covers!!.publicationHook {
                    if (enabled.value) NativeHost.currentId() else null
                })
            val autoSettings = try { preferences.load().also { entry.value = it.entry; interludeCountdown = it.interludeCountdown; countdownSettings.value = it.countdown }.autoTags } catch (_: Exception) {
                notify("设置读取失败，后台标签整理未开启", true)
                AutoTagSettings()
            }
            NativeHost.currentId()?.let(NativeHost::reload)
            NativeHost.publishCover(NativeHost.currentCoverOwner())
            autoTags.configure(autoSettings)
            migrateAppliedLyrics()
        } catch (failure: Throwable) {
            try { stop() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw IllegalStateException("Better Tag for SPW注册失败", failure)
        }
    }

    override fun stop() {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                if (failure !is Exception && failure !is LinkageError) throw failure
                failure.printStackTrace(); failures += failure
            }
        }
        enabled.value = false
        studio.value = null
        attempt { autoWindow?.close(); autoWindow = null }
        backgroundPage = null
        lyricMigration?.cancel(); lyricMigration = null
        if (::autoTags.isInitialized) attempt { autoTags.close() }
        if (active === this) active = null
        val iterator = hooks.listIterator(hooks.size)
        while (iterator.hasPrevious()) {
            val hook = iterator.previous()
            attempt { hook.unhook(); iterator.remove() }
        }
        lyricTarget = null
        CountdownRenderer.clear()
        runCatching { NativeHost.publishCover(NativeHost.currentCoverOwner()) }
        attempt { covers?.close(); covers = null }
        runCatching { NativeHost.currentId()?.let(NativeHost::reload) }
        media.value = null
        if (failures.isNotEmpty()) throw IllegalStateException("Better Tag for SPW清理未全部完成，请查看日志或重试停用。").apply { failures.forEach(::addSuppressed) }
    }

    fun save(id: String, value: SavedLyrics) {
        update(id) { value }
    }

    fun update(id: String, coverOnly: Boolean = false, change: (SavedLyrics) -> SavedLyrics): SavedLyrics {
        check(enabled.value && NativeHost.currentId() == id) { "歌曲已切换，请重新打开编辑器" }
        val track = WorkshopApi.playback.getCurrentMediaItem()?.takeIf { it.id == id } ?: media.value?.takeIf { it.id == id } ?: error("无法定位当前音频文件")
        val updated = if (coverOnly) synchronized(TagFiles) {
            store.update(id, change).also { autoTagStore.protect(id, runCatching { TagFiles.localPath(track.path) }.getOrNull()) }
        } else persistLyrics(track, true, change)
        try { if (coverOnly) NativeHost.publishCover(NativeHost.currentCoverOwner()) else NativeHost.reload(id) }
        catch (_: Exception) { notify("已保存，播放界面暂未刷新", true) }
        EventQueue.invokeLater { revision.intValue++ }
        return updated
    }

    private fun persistLyrics(track: PlaybackExtensionPoint.MediaItem, currentOnly: Boolean, change: (SavedLyrics) -> SavedLyrics): SavedLyrics {
        fun checkWrite() {
            check(enabled.value && WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_WRITE)) { "请在插件管理中授权媒体库写入，歌词尚未写入文件" }
            check(!currentOnly || NativeHost.currentId() == track.id) { "歌曲已切换，已取消歌词写入" }
        }
        checkWrite()
        val path = TagFiles.localPath(track.path)
        return LyricsPersistence.apply(store, track.id, path, tagBackups,
            { if (NativeHost.currentId() == track.id) NativeHost.currentRaw() else "" }, change, ::checkWrite) { stage, target ->
            autoTagStore.protectWrite(track.id, target) { replaceTagFile(stage, target, NativeTagPlayback(track.id)) }
        }.also { autoTagStore.protect(track.id, path) }
    }

    private fun migrateAppliedLyrics() {
        lyricMigration = CoroutineScope(Dispatchers.IO).launch {
            try {
                val tracks = runInterruptible { WorkshopApi.library.getAllTracks().toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS) }
                    .filter { store.hasSaved(it.id) && LyricsPersistence.needsMigration(store.get(it.id)) }
                if (tracks.isEmpty()) return@launch
                check(WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_WRITE)) { "旧版已应用歌词尚未写入文件，请授权媒体库写入后重新启用插件" }
                var written = 0; val failures = mutableListOf<String>()
                for (track in tracks) {
                    ensureActive()
                    try { persistLyrics(track, false) { it }; written++ }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { failures += "${track.title}：${failure.message}" }
                }
                EventQueue.invokeLater {
                    if (written > 0) notify("已将 $written 首旧版已应用歌词写入音频文件")
                    if (failures.isNotEmpty()) notify("${failures.size} 首旧版歌词未写入：${failures.first()}", true)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { EventQueue.invokeLater { notify(failure.message ?: "旧版歌词写入失败", true) } }
        }
    }

    fun notify(message: String, failure: Boolean = false) {
        if (enabled.value) WorkshopApi.ui.toast(message, if (failure) WorkshopApi.Ui.ToastType.Error else WorkshopApi.Ui.ToastType.Success)
    }

    fun openAutoTags() {
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater { openAutoTags() }; return }
        if (enabled.value) backgroundPage?.invoke() ?: autoWindow?.open()
    }
    fun openStudio(page: StudioPage, owner: LyricsEntry = entry.value) {
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater { openStudio(page, owner) }; return }
        if (!enabled.value || studio.value != null) return
        val current = WorkshopApi.playback.getCurrentMediaItem() ?: media.value
        if (current == null) { notify("请先播放一首歌曲", true); return }
        studio.value = StudioRequest(current, page, owner)
    }

    companion object { @Volatile var active: LyricsPlugin? = null }
}

/** Registered in META-INF/extensions.idx; the host supplies its original lyrics when no override exists. */
class LyricsPlayback : PlaybackExtensionPoint {
    override fun onStateChanged(state: PlaybackExtensionPoint.State) { LyricsPlugin.active?.playbackChanged() }
    override fun onIsPlayingChanged(isPlaying: Boolean) { LyricsPlugin.active?.playbackChanged() }
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        val plugin = LyricsPlugin.active?.takeIf { it.enabled.value } ?: return null
        plugin.loaded(mediaItem)
        EventQueue.invokeLater {
            if (plugin.enabled.value && NativeHost.currentId() == mediaItem.id) plugin.media.value = mediaItem
        }
        return try {
            val saved = plugin.store.get(mediaItem.id)
            saved.text?.let { LyricsCodec.export(LyricsCodec.source(saved, ""), LyricsFormat.AUTO) }
        }
        catch (_: Exception) {
            EventQueue.invokeLater { plugin.notify("本曲的歌词配置读取失败，暂用原歌词", true) }
            null
        }
    }
}
