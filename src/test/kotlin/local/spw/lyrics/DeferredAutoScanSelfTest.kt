@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

fun checkDeferredAutoScan() = runBlocking {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "deferred-")
    fun song(name: String): PlaybackExtensionPoint.MediaItem {
        val path = root.resolve("$name.flac")
        Files.copy(Path.of("src/test/resources/audio/tone.flac"), path)
        TagFiles.write(path, mapOf(MusicTag.TITLE to name, MusicTag.ARTIST to "测试歌手", MusicTag.ALBUM to "旧专辑"), null, null, null)
        return PlaybackExtensionPoint.MediaItem(name, "测试歌手", "旧专辑", "", path.toString(), id = name, duration = 1000)
    }
    suspend fun await(test: () -> Boolean) = withTimeout(5000) { while (!test()) delay(10) }
    val first = song("正在播放"); val second = song("已检查"); val added = song("新增歌曲")
    val library = MutableStateFlow(listOf(first, second))
    val current = AtomicReference<String?>(first.id)
    val queries = ConcurrentHashMap<String, AtomicInteger>()
    val reads = AtomicInteger()
    val ledger = AutoTagStore(root.resolve("ledger"))
    fun worker(store: AutoTagStore) = AutoTagger(store, { reads.incrementAndGet(); library.value }, { false }, { it.id == current.get() },
        search = { _, target -> queries.computeIfAbsent(target.title) { AtomicInteger() }.incrementAndGet(); emptyList() }, libraryChanges = { library })
    val config = AutoTagSettings(enabled = true, cover = false)
    val plugin = LyricsPlugin(PluginContext("local.spw.lyrics", "test", ".", "1.19.2", Channel.Steam))
    val workerField = LyricsPlugin::class.java.getDeclaredField("autoTags").apply { isAccessible = true }
    val previous = LyricsPlugin.active
    var runner = worker(ledger)
    try {
        plugin.enabled.value = true; LyricsPlugin.active = plugin; workerField.set(plugin, runner)
        runner.configure(config)
        await { ledger.wasSeen(second.path) && ledger.isDeferred(first.path) && !runner.status.value.running }
        check(!ledger.wasSeen(first.path) && queries[first.title] == null)
        val secondQueries = queries.getValue(second.title).get(); val snapshots = reads.get()
        current.set(second.id)
        library.value = listOf(first, second, added)
        LyricsPlayback().onStateChanged(PlaybackExtensionPoint.State.Ready)
        await { ledger.wasSeen(first.path) && ledger.wasSeen(added.path) && !runner.status.value.running }
        check(queries.getValue(second.title).get() == secondQueries && reads.get() == snapshots) { "Playback retry restarted completed library work" }
        check(!ledger.isDeferred(first.path))
        val firstQueries = queries.getValue(first.title).get()
        repeat(5) { LyricsPlayback().onIsPlayingChanged(false); LyricsPlayback().onStateChanged(PlaybackExtensionPoint.State.Ready) }
        delay(200)
        check(queries.getValue(first.title).get() == firstQueries && reads.get() == snapshots)

        current.set(first.id)
        runner.scanNow()
        await { ledger.isDeferred(first.path) && !runner.status.value.running }
        check(!ledger.wasSeen(first.path))
        runner.close()
        val reopened = AutoTagStore(root.resolve("ledger"))
        check(reopened.isDeferred(first.path) && !reopened.wasSeen(first.path)) { "Old scan checkpoint hid a deferred track after restart" }
        runner = worker(reopened); workerField.set(plugin, runner); runner.configure(config)
        await { runner.status.value.completedAt > 0 && !runner.status.value.running }
        val beforeResume = queries.getValue(first.title).get()
        current.set(null); LyricsPlayback().onStateChanged(PlaybackExtensionPoint.State.Idle)
        await { reopened.wasSeen(first.path) && !runner.status.value.running }
        check(queries.getValue(first.title).get() > beforeResume) { "Deferred manual request obeyed an old retry checkpoint" }

        current.set(first.id); runner.scanNow()
        await { reopened.isDeferred(first.path) && !runner.status.value.running }
        library.value = listOf(second, added)
        delay(100)
        val beforeRemoval = queries.getValue(first.title).get()
        current.set(null); LyricsPlayback().onStateChanged(PlaybackExtensionPoint.State.Ready)
        delay(200)
        check(queries.getValue(first.title).get() == beforeRemoval) { "Removed library track was processed from the deferred queue" }
    } finally { runner.close(); plugin.enabled.value = false; LyricsPlugin.active = previous }

    val commit = song("提交前开始播放")
    val commitLedger = AutoTagStore(root.resolve("commit-ledger"))
    val playing = AtomicBoolean(false); val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val before = Files.readAllBytes(Path.of(commit.path))
    val commitWorker = AutoTagger(commitLedger, { listOf(commit) }, { false }, { playing.get() },
        search = { source, _ -> listOf(Candidate(source, source.name, commit.title, commit.artist, "匹配专辑", 1000)) },
        fetch = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MusicTags(mapOf(MusicTag.TITLE to commit.title, MusicTag.ARTIST to commit.artist, MusicTag.ALBUM to "匹配专辑")) })
    try {
        commitWorker.configure(config)
        await { entered.count == 0L }; playing.set(true); release.countDown()
        await { commitLedger.isDeferred(commit.path) && !commitWorker.status.value.running }
        check(!commitLedger.wasSeen(commit.path) && Files.readAllBytes(Path.of(commit.path)).contentEquals(before))
        playing.set(false); commitWorker.playbackChanged()
        await { commitLedger.isProtected(commit.id, Path.of(commit.path)) && !commitWorker.status.value.running }
        check(commitLedger.wasSeen(commit.path) && !commitLedger.isDeferred(commit.path))
        check(TagFiles.read(Path.of(commit.path)).values[MusicTag.ALBUM] == "匹配专辑")
    } finally { release.countDown(); commitWorker.close() }
    println("PASS: deferred playing tracks resume via real playback callbacks, including commit races and restart; old checkpoints cannot hide them; completed/new/removed paths and idle events remain isolated.")
}
