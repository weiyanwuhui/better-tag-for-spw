@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

data class AutoTagProgress(val phase: String = "未开启", val running: Boolean = false, val paused: Boolean = false,
    val total: Int = 0, val processed: Int = 0, val updated: Int = 0, val protected: Int = 0, val skipped: Int = 0,
    val failed: Int = 0, val song: String = "", val message: String = "", val path: String = "",
    val completedAt: Long = 0, val active: Int = 0)

/** Bound network preparation separately from disk reads and the single validated file commit. */
class AutoTagger(private val ledger: AutoTagStore,
    private val tracks: suspend () -> List<MediaItem>,
    private val protected: (MediaItem) -> Boolean,
    private val current: (MediaItem) -> Boolean,
    private val search: (LyricSource, SearchTarget) -> List<Candidate> = { source, target -> LyricSources().search(source, target.title, target.artist) },
    fetch: ((Candidate, Boolean) -> MusicTags)? = null,
    private val refresh: suspend (List<MediaItem>) -> Unit = { },
    private val canWrite: () -> Boolean = { true }, private val requestDelayMillis: Long = 0,
    private val libraryChanges: (() -> Flow<List<MediaItem>>)? = null) : AutoCloseable {
    private val progress = MutableStateFlow(AutoTagProgress())
    private val fetch: (Candidate, Boolean) -> MusicTags = fetch ?: TagSources()::fetch
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        publish { it.copy(phase = "后台整理已停止", running = false, message = failure.message ?: "后台任务异常") }
    })
    private val settings = MutableStateFlow(AutoTagSettings())
    private val paused = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val manualScan = java.util.concurrent.atomic.AtomicBoolean()
    private val libraryPending = java.util.concurrent.atomic.AtomicBoolean()
    private val deferred = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()
    val status: StateFlow<AutoTagProgress> = progress.asStateFlow()
    val configuration: StateFlow<AutoTagSettings> = settings.asStateFlow()
    private var permits = Semaphore(32)
    private var sourcePermits = AUTO_TAG_SOURCES.associateWith { Semaphore(16) }
    private val reads = Semaphore(2)
    private val sourceFailures = AUTO_TAG_SOURCES.associateWith { java.util.concurrent.atomic.AtomicInteger() }
    private val sourceRetryAt = java.util.concurrent.ConcurrentHashMap<LyricSource, Long>()
    private val updates = java.util.concurrent.ConcurrentLinkedQueue<MediaItem>()
    private val refreshLock = Mutex()
    @Volatile private var lastRefresh = System.nanoTime()
    private val active = java.util.concurrent.ConcurrentHashMap<String, Pair<MediaItem, String>>()

    init {
        scope.launch {
            val configFlow: Flow<AutoTagSettings> = settings
            configFlow.collectLatest { config ->
                if (!config.enabled) { publish { it.copy(phase = "未开启", running = false, song = "", path = "", message = "") }; return@collectLatest }
                coroutineScope {
                libraryPending.set(true)
                val latest = java.util.concurrent.atomic.AtomicReference<List<MediaItem>?>()
                val observer = libraryChanges?.let { changes -> launch {
                    changes().collect { library ->
                        latest.set(library)
                        if (deferred.isNotEmpty()) deferred.keys.retainAll(library.map { it.path }.toSet())
                        libraryPending.set(true); wake.trySend(Unit)
                    }
                } }
                wake.trySend(Unit)
                try { for (ignored in wake) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    try {
                        waitForResume()
                        check(canWrite()) { "请在插件管理中授权媒体库写入" }
                        val manual = manualScan.getAndSet(false)
                        val readLibrary = libraryPending.getAndSet(false)
                        if (manual) { sourceRetryAt.clear(); sourceFailures.values.forEach { it.set(0) } }
                        val library = (if (manual) tracks() else if (readLibrary) latest.get() ?: tracks() else deferred.values.toList())
                            .distinctBy { it.path.lowercase(java.util.Locale.ROOT) }
                        val pending = (if (manual) library else library.filterNot { ledger.wasSeen(it.path) })
                            .filter { manual || !deferred.containsKey(it.path) || !current(it) }
                        if (pending.isNotEmpty() || progress.value.completedAt == 0L || manual) scan(config, pending, manual)
                        else publish { it.copy(phase = "已完成 · 等待新歌曲", running = false) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { publish { it.copy(phase = "扫描未完成", running = false, song = "", path = "", message = failure.message ?: "后台扫描失败") } }
                } } finally { observer?.cancel() }
                }
            }
        }
    }
    @Synchronized private fun publish(change: (AutoTagProgress) -> AutoTagProgress) { progress.value = change(progress.value).copy(paused = paused.value) }
    @Synchronized fun configure(value: AutoTagSettings) {
        if (!value.enabled) paused.value = false
        settings.value = value
        publish { it }
    }
    fun scanNow() { manualScan.set(true); wake.trySend(Unit) }
    fun playbackChanged() { if (deferred.isNotEmpty()) wake.trySend(Unit) }
    private fun defer(track: MediaItem) { ledger.defer(track.path); deferred[track.path] = track; playbackChanged() }
    @Synchronized fun togglePause() { paused.value = !paused.value; libraryPending.set(true); publish { it }; wake.trySend(Unit) }
    private suspend fun waitForResume() {
        if (paused.value) { val changes: Flow<Boolean> = paused; changes.first { !it } }
        kotlin.coroutines.coroutineContext.ensureActive()
    }
    override fun close() { settings.value = settings.value.copy(enabled = false); scope.cancel(); wake.close() }

    private suspend fun scan(config: AutoTagSettings, library: List<MediaItem>, manual: Boolean) {
        permits = Semaphore(config.concurrency)
        sourcePermits = AUTO_TAG_SOURCES.associateWith { Semaphore(((config.concurrency + 1) / 2).coerceAtLeast(1)) }
        publish { AutoTagProgress("正在检查文件", running = true, total = library.size, completedAt = it.completedAt) }
        try {
            coroutineScope {
                val cache = java.util.concurrent.ConcurrentHashMap<String, List<Candidate>>()
                val workers = config.concurrency.coerceAtLeast(2)
                val queue = Channel<MediaItem>(workers)
                launch { try { library.forEach { queue.send(it) } } finally { queue.close() } }
                List(workers) { launch { for (track in queue) {
                    process(track, config, cache, manual)
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (!paused.value && canWrite() && !deferred.containsKey(track.path)) ledger.remember(track.path)
                } } }.joinAll()
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { flushUpdates() } }
        publish { it.copy(phase = "已完成 · 等待新歌曲", running = false, song = "", path = "", completedAt = System.currentTimeMillis()) }
    }
    private suspend fun <T> request(source: LyricSource, call: () -> T): T {
        val perSource = sourcePermits.getValue(source)
        perSource.acquire()
        try {
            permits.acquire()
            try {
                check(System.currentTimeMillis() >= (sourceRetryAt[source] ?: 0)) { "${source.label}暂不可用，稍后重试" }
                try {
                    val result = withTimeout(12_000) { delay(requestDelayMillis); runInterruptible(Dispatchers.IO) { call() } }
                    sourceFailures.getValue(source).set(0)
                    sourceRetryAt.remove(source)
                    return result
                } catch (failure: Exception) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                    if (sourceFailures.getValue(source).incrementAndGet() >= 3) sourceRetryAt[source] = System.currentTimeMillis() + 30_000
                    if (failure is TimeoutCancellationException) throw java.net.SocketTimeoutException("${source.label}请求超时")
                    throw failure
                }
            }
            finally { permits.release() }
        } finally { perSource.release() }
    }

    private suspend fun flushUpdates() {
        refreshLock.lock()
        try {
            while (updates.isNotEmpty()) {
                val batch = buildList { repeat(50) { updates.poll()?.let { add(it) } } }
                if (batch.isEmpty()) break
                try { refresh(batch); lastRefresh = System.nanoTime() }
                catch (failure: Exception) {
                    updates.addAll(batch)
                    publish { it.copy(message = "文件已保存，曲库更新失败，可点击立即扫描重试") }
                    break
                }
            }
        } finally { refreshLock.unlock() }
    }

    private suspend fun process(track: MediaItem, config: AutoTagSettings, cache: java.util.concurrent.ConcurrentHashMap<String, List<Candidate>>, manual: Boolean) {
        waitForResume()
        deferred.remove(track.path)
        stage(track, "正在检查文件")
        var usedNetwork = false
        try {
            val path = try { TagFiles.localPath(track.path) } catch (_: IllegalArgumentException) {
                publish { it.copy(skipped = it.skipped + 1) }; return
            }
            if (protected(track) || ledger.isProtected(track.id, path)) {
                publish { it.copy(protected = it.protected + 1) }; return
            }
            if (current(track)) { defer(track); publish { it.copy(skipped = it.skipped + 1) }; return }
            val stamp = ledger.stamp(path)
            if (!manual && !ledger.isDeferred(track.path) && !ledger.due(path, stamp, config.policy, System.currentTimeMillis())) {
                publish { it.copy(skipped = it.skipped + 1) }; return
            }
            reads.acquire()
            val original = try { runInterruptible(Dispatchers.IO) { TagFiles.read(path, decodeCover = false) } } finally { reads.release() }
            val clues = original.values.toMutableMap()
            mapOf(MusicTag.TITLE to track.title, MusicTag.ARTIST to track.artist, MusicTag.ALBUM to track.album).forEach { (field, value) ->
                if (!AutoTagMatching.meaningful(clues[field].orEmpty()) && AutoTagMatching.meaningful(value)) clues[field] = value
            }
            val targets = AutoTagMatching.targets(path, original.copy(values = clues), original.duration.takeIf { it > 0 } ?: track.duration)
            if (targets.isEmpty()) {
                ledger.checked(path, stamp, config.policy, Long.MAX_VALUE)
                publish { it.copy(skipped = it.skipped + 1) }; return
            }
            usedNetwork = true
            stage(track, "正在搜索")
            var errors = 0
            var attempts = 0
            val matches = mutableListOf<Candidate>()
            for (target in targets) {
                val candidates = supervisorScope {
                    val incoming = Channel<Pair<List<Candidate>, Boolean>>(config.sources.size)
                    val found = mutableListOf<Candidate>()
                    val jobs = config.sources.map { source -> launch {
                        val key = "$source:${SearchRanking.key(target.title)}:${SearchRanking.artists(target.artist)}:${SearchRanking.version(target.title)}"
                        val result = try { (cache[key] ?: request(source) { search(source, target) }.also { cache[key] = it }) to false }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { emptyList<Candidate>() to true }
                        incoming.send(result)
                    } }
                    try {
                        repeat(config.sources.size) { received ->
                            val (result, failed) = incoming.receive()
                            found.addAll(result); attempts++; if (failed) errors++
                            val combined = matches + found
                            val confirmed = AutoTagMatching.choose(combined, targets)
                            val settled = targets.size == 1 || confirmed != null && targets.all {
                                it.album.isBlank() || SearchRanking.key(it.album) == SearchRanking.key(confirmed.album)
                            }
                            if (settled && AutoTagMatching.searchComplete(combined, target, config.sources.size - received - 1)) return@supervisorScope found.toList()
                        }
                        found.toList()
                    } finally { jobs.forEach { it.cancel() }; incoming.close() }
                }
                matches.addAll(candidates)
            }
            val candidate = AutoTagMatching.choose(matches, targets)
            if (candidate == null) {
                val allFailed = errors == attempts
                ledger.checked(path, stamp, config.policy, System.currentTimeMillis() + if (errors > 0) 1_800_000 else 604_800_000)
                publish { if (allFailed) it.copy(failed = it.failed + 1, message = "来源暂不可用，可点击立即扫描重试") else it.copy(skipped = it.skipped + 1) }
                return
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            val replaceCover = config.cover && (original.cover == null || config.mode == AutoTagMode.COMPLETE &&
                SearchRanking.key(original.values[MusicTag.ALBUM].orEmpty()) != SearchRanking.key(candidate.album))
            stage(track, "正在获取标签")
            val base = mapOf(MusicTag.TITLE to candidate.title, MusicTag.ARTIST to candidate.artist, MusicTag.ALBUM to candidate.album)
            val fetched = if (!replaceCover && config.fields.all { it in base }) MusicTags(base)
                else request(candidate.source) { fetch(candidate, replaceCover) }
            val values = AutoTagMatching.changes(original, fetched, candidate, config)
            val picture = if (replaceCover) fetched.cover else null
            if (values.isEmpty() && picture == null) {
                ledger.checked(path, stamp, config.policy, if (replaceCover) System.currentTimeMillis() + 604_800_000 else Long.MAX_VALUE)
                publish { it.copy(skipped = it.skipped + 1) }; return
            }
            val task = kotlin.coroutines.coroutineContext[Job]!!
            val written = withContext(NonCancellable + Dispatchers.IO) {
                synchronized(TagFiles) {
                    task.ensureActive()
                    if (current(track)) { defer(track); false }
                    else if (paused.value || settings.value != config || !canWrite() || protected(track) || ledger.isProtected(track.id, path) || ledger.stamp(path) != stamp) false
                    else {
                        val reread = TagFiles.read(path, decodeCover = false)
                        check(reread.values == original.values && (reread.cover?.bytes?.contentEquals(original.cover?.bytes ?: byteArrayOf()) ?: (original.cover == null))) { "文件标签已变化，取消本次自动写入" }
                        stage(track, "正在写入")
                        TagFiles.write(path, values, picture, null, null) { stage, target ->
                            task.ensureActive()
                            if (current(track)) { defer(track); error("歌曲正在播放，稍后自动继续整理") }
                            check(!paused.value && settings.value == config && canWrite() && !protected(track) && !ledger.isProtected(track.id, target)) { "歌曲状态已变化，取消本次自动写入" }
                            ledger.protectWrite(track.id, target) { Files.move(stage, target, ATOMIC_MOVE, REPLACE_EXISTING) }
                        }
                        true
                    }
                }
            }
            if (written) {
                publish { it.copy(updated = it.updated + 1) }
                updates.add(track)
                if (updates.size >= 25 || System.nanoTime() - lastRefresh >= 10_000_000_000L) flushUpdates()
            } else publish { it.copy(skipped = it.skipped + 1) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            publish { it.copy(failed = it.failed + 1, message = failure.message ?: "文件处理失败") }
        } finally { active.remove(track.path); showActive(); publish { it.copy(processed = it.processed + 1) } }
        if (usedNetwork) yield()
    }

    private fun stage(track: MediaItem, phase: String) { active[track.path] = track to phase; showActive() }
    private fun showActive() {
        val item = active.values.maxByOrNull { when (it.second) { "正在写入" -> 3; "正在获取标签" -> 2; "正在搜索" -> 1; else -> 0 } }
        publish { it.copy(active = active.size, phase = item?.second ?: it.phase,
            song = item?.first?.let { track -> listOf(track.title.ifBlank { track.path.substringAfterLast('\\') }, track.artist).filter(String::isNotBlank).joinToString(" · ") }.orEmpty(),
            path = item?.first?.path.orEmpty()) }
    }

}
