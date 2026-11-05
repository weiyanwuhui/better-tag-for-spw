@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.*
import java.util.concurrent.atomic.AtomicInteger

fun checkIncrementalScan() = runBlocking {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "incremental-")
    fun song(id: String): MediaItem {
        val folder = Files.createDirectories(root.resolve(id))
        val path = folder.resolve("待匹配$id.flac")
        Files.copy(Path.of("src/test/resources/audio/tone.flac"), path)
        TagFiles.write(path, mapOf(MusicTag.TITLE to "待匹配$id", MusicTag.ARTIST to "测试歌手", MusicTag.ALBUM to "测试专辑"), null, null, null)
        return MediaItem("待匹配$id", "测试歌手", "测试专辑", "", path.toString(), id = id, duration = 1000)
    }
    val first = song("first"); val second = song("second")
    val library = MutableStateFlow(listOf(first))
    val reads = AtomicInteger(); val queries = AtomicInteger(); val refreshes = AtomicInteger()
    val ledger = AutoTagStore(root.resolve("state"))
    val worker = AutoTagger(ledger, tracks = { reads.incrementAndGet(); library.value }, protected = { false }, current = { false },
        search = { _, _ -> queries.incrementAndGet(); emptyList() }, refresh = { refreshes.incrementAndGet() }, libraryChanges = { library })
    suspend fun idle(test: () -> Boolean) = withTimeout(5000) { while (worker.status.value.running || !test()) delay(10) }
    try {
        delay(80); check(reads.get() == 0 && library.subscriptionCount.value == 0)
        worker.configure(AutoTagSettings(enabled = true, cover = false))
        idle { ledger.wasSeen(first.path) && worker.status.value.completedAt > 0 }
        val calls = queries.get(); val snapshots = reads.get()
        check(calls in 2..3 && ledger.wasSeen(Path.of(first.path).toUri().toString())) { "Initial search calls=$calls; URI checkpoint=${ledger.wasSeen(Path.of(first.path).toUri().toString())}" }
        library.value = listOf(first.copy(title = "标签已更新"))
        delay(150)
        check(queries.get() == calls && reads.get() == snapshots && refreshes.get() == 0) { "Metadata-only emission restarted organization" }
        library.value = listOf(first, second)
        idle { ledger.wasSeen(second.path) }
        val addedCalls = queries.get()
        check(addedCalls - calls in 2..3 && worker.status.value.total == 1) { "New song caused a full rescan" }
        delay(450)
        check(queries.get() == addedCalls && reads.get() == snapshots && refreshes.get() == 0) { "Completed scan kept working" }
        val completed = worker.status.value.completedAt
        worker.scanNow()
        idle { reads.get() > snapshots && worker.status.value.completedAt > completed }
        check(queries.get() - addedCalls in 4..6) { "Manual scan was blocked by automatic backoff checkpoints" }
        check(worker.status.value.total == 2) { "Explicit scan did not retry existing unmatched songs" }
        worker.configure(AutoTagSettings())
        withTimeout(2000) { while (library.subscriptionCount.value != 0) delay(10) }
    } finally { worker.close() }
    val reopened = AutoTagger(AutoTagStore(root.resolve("state")), tracks = { listOf(first, second) }, protected = { false }, current = { false },
        search = { _, _ -> error("Previously checked songs were searched after restart") })
    try {
        reopened.configure(AutoTagSettings(enabled = true, cover = false))
        withTimeout(3000) { while (reopened.status.value.completedAt == 0L || reopened.status.value.running) delay(10) }
        check(reopened.status.value.total == 0 && reopened.status.value.failed == 0)
    } finally { reopened.close() }
    println("PASS: completion is idle without timers, metadata updates do not rescan, only new paths are queued, manual scan retries, restart retains checkpoints and disabling removes the library subscription.")
}
