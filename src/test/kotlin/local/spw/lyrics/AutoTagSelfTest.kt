@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val autoValues = mapOf(MusicTag.TITLE to "晴天", MusicTag.ARTIST to "周杰伦", MusicTag.ALBUM to "叶惠美")
private fun choices(duration: Long = 1000) = listOf(LyricSource.NETEASE, LyricSource.QQ).map { Candidate(it, it.name, "晴天", "周杰伦", "叶惠美", duration) }
private suspend fun awaitAuto(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(10) }

fun checkAutoMatching() {
    check(timingValue("-275") == -275L && timingValue("60000") == 60000L)
    for (invalid in listOf("60001", "-60001", "1.5", "", "NaN")) check(runCatching { timingValue(invalid) }.isFailure)
    val bridge = NativeHost.localVri
    check(bridge.invoke(null, Path.of("src/test/resources/audio/tone.flac").toAbsolutePath().toString()).toString().startsWith("file:"))
    Class.forName("com.xuncorp.voxzen.service.RepoService", false, AutoTagger::class.java.classLoader)
        .getMethod("rereadTracks-gIAlu-s", List::class.java, kotlin.coroutines.Continuation::class.java)
    check(NativeHost.libraryFlow.genericReturnType.typeName.contains("com.xuncorp.voxzen.data.entity.Track"))
    check(autoTagStatus(AutoTagSettings(), AutoTagProgress(paused = true, phase = "正在搜索")) == "未开启")
    check(autoTagStatus(AutoTagSettings(enabled = true), AutoTagProgress()) == "等待扫描")
    check(autoTagStatus(AutoTagSettings(enabled = true), AutoTagProgress(paused = true, phase = "正在搜索")) == "已暂停")
    check(AutoTagger::class.java.classLoader.getResource("preference_config.json") == null) { "Removed Workshop configuration was still packaged" }
    val target = SearchTarget("晴天", "周杰伦", "叶惠美", 269000)
    val good = choices(269000)
    check(AutoTagMatching.choose(good, listOf(target)) != null)
    check(AutoTagMatching.searchComplete(good, target, 1))
    check(AutoTagMatching.searchComplete(emptyList(), target, 1))
    check(!AutoTagMatching.searchComplete(good, target.copy(album = "另一专辑"), 1)) { "Early completion hid the preferred album on a pending source" }
    check(AutoTagMatching.choose(good.take(1) + good.take(1).map { it.copy(id = "another") }, listOf(target)) == null) { "One platform counted as independent corroboration" }
    check(AutoTagMatching.choose(good.map { it.copy(artist = "其他歌手") }, listOf(target)) == null)
    check(AutoTagMatching.choose(good.map { it.copy(title = "晴天 (Live)") }, listOf(target)) == null)
    check(AutoTagMatching.choose(good.map { it.copy(duration = 290000) }, listOf(target)) == null)
    check(AutoTagMatching.choose(good.map { it.copy(duration = 0) }, listOf(target)) == null)
    val unknownAlbum = target.copy(album = "")
    val otherAlbum = good.map { it.copy(id = "other", album = "另一专辑") }
    check(!AutoTagMatching.searchComplete(good + otherAlbum.take(1), unknownAlbum, 1)) { "Early completion hid an album ambiguity" }
    check(AutoTagMatching.choose(good + otherAlbum, listOf(unknownAlbum)) == null)
    check(AutoTagMatching.choose(good + otherAlbum, listOf(target))?.album == "叶惠美")
    check(AutoTagMatching.choose(good + otherAlbum.take(1), listOf(target.copy(album = "另一专辑"))) == null)
    val original = MusicTags(mapOf(MusicTag.TITLE to "晴天", MusicTag.ARTIST to "错误歌手", MusicTag.ALBUM to "旧专辑"))
    val hints = AutoTagMatching.targets(Path.of("晴天 - 周杰伦.flac"), original, 269000)
    check(AutoTagMatching.choose(good, hints) != null) { "An incorrect singer prevented filename cross-checking" }
    check(AutoTagMatching.targets(Path.of("0123456789abcdef.flac"), MusicTags(emptyMap()), 269000).isEmpty())
    val fetched = MusicTags(autoValues + (MusicTag.YEAR to "2020") + (MusicTag.GENRE to "另一种流派"))
    val existing = MusicTags(original.values + (MusicTag.GENRE to "个人流派"))
    val changed = AutoTagMatching.changes(existing, fetched, good.first(), AutoTagSettings())
    check(changed[MusicTag.ARTIST] == "周杰伦" && changed[MusicTag.ALBUM] == "叶惠美" && MusicTag.GENRE !in changed)
    check(AutoTagMatching.changes(existing, fetched, good.first(), AutoTagSettings(mode = AutoTagMode.FILL)).keys == setOf(MusicTag.YEAR))
    check(runCatching { AutoTagMatching.changes(existing, fetched.copy(values = autoValues + (MusicTag.ARTIST to "其他歌手")), good.first(), AutoTagSettings()) }.isFailure)
    val credits = TagSources().qq(kotlinx.serialization.json.Json.parseToJsonElement("""{"songinfo":{"data":{"track_info":{"title":"Melody","singer":[{"name":"陶喆"}],"album":{"name":"黑色柳丁"}},"info":[{"title":"歌曲流派","selected":"genre","content":[{"value":"Pop"}]},{"title":"作曲","content":[{"value":"陶喆"}]},{"type":"lyricist","content":[{"value":"陶喆 / 娃娃"}]}]}}}"""), false)
    check(credits.values[MusicTag.GENRE] == "Pop" && credits.values[MusicTag.COMPOSER] == "陶喆" && credits.values[MusicTag.LYRICIST] == "陶喆 / 娃娃")
    println("PASS: auto matching requires independent providers, artist/version/duration agreement and unambiguous album; filename repair, fill-only mode and detailed-response identity checks.")
}

fun checkAutoLedger() {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "auto-ledger-")
    val audio = root.resolve("song.flac"); Files.writeString(audio, "original")
    val ledger = AutoTagStore(root.resolve("state"))
    check(!ledger.isProtected("id", audio))
    check(runCatching { ledger.protectWrite("id", audio) { error("replace failed") } }.isFailure)
    check(!ledger.isProtected("id", audio))
    ledger.protectWrite("id", audio) { Files.writeString(audio, "updated") }
    val reopened = AutoTagStore(root.resolve("state"))
    check(reopened.isProtected("id", root.resolve("moved.flac")) && reopened.isProtected("different-id", audio))
    val backup = Files.createDirectories(root.resolve("old-backups"))
    val old = root.resolve("old.flac"); Files.writeString(old, "old")
    Files.writeString(backup.resolve("legacy.flac.txt"), old.toString())
    reopened.importLegacy(backup)
    check(reopened.isProtected("legacy", old))
    val stamp = ledger.stamp(audio)
    check(ledger.due(audio, stamp, "a", 0))
    ledger.checked(audio, stamp, "a", 100)
    check(AutoTagStore(root.resolve("state")).wasSeen(audio.toString())) { "Upgrade ignored previously checked songs" }
    check(!ledger.due(audio, stamp, "a", 99) && ledger.due(audio, stamp, "a", 100))
    check(ledger.due(audio, stamp + "changed", "a", 0) && ledger.due(audio, stamp, "b", 0))
    println("PASS: durable ID/path protection, legacy migration, failed-replacement rollback, incremental checkpoints and retry/policy invalidation without tag history.")
}

fun checkAutoWorker() = runBlocking {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "auto-worker-")
    val fixture = Path.of("src/test/resources/audio/tone.flac")
    fun track(id: String, album: String = "旧专辑"): MediaItem {
        val folder = Files.createDirectories(root.resolve(id))
        val file = folder.resolve("晴天 - 周杰伦.flac"); Files.copy(fixture, file)
        TagFiles.write(file, autoValues + (MusicTag.ALBUM to album), null, "[00:00.000]保留<00:00.500>逐字", null)
        return MediaItem("晴天", "周杰伦", album, "", file.toString(), id = id, duration = 1000)
    }
    fun fetched() = MusicTags(autoValues + (MusicTag.YEAR to "2020"))
    val item = track("update")
    val prior = track("prior")
    val active = track("active")
    val ledger = AutoTagStore(root.resolve("ledger")); ledger.protect(prior.id, Path.of(prior.path))
    val searches = AtomicInteger(); val concurrent = AtomicInteger(); val maximum = AtomicInteger(); val passes = AtomicInteger()
    val worker = AutoTagger(ledger, tracks = { passes.incrementAndGet(); listOf(item, prior, active) }, protected = { false }, current = { it.id == active.id },
        search = { source, _ ->
            searches.incrementAndGet(); val n = concurrent.incrementAndGet(); maximum.accumulateAndGet(n, ::maxOf)
            try { Thread.sleep(20); choices().filter { it.source == source } } finally { concurrent.decrementAndGet() }
        }, fetch = { _, cover -> check(!cover); fetched() }, requestDelayMillis = 0)
    try {
        delay(30); check(passes.get() == 0) { "Disabled worker read the library" }
        worker.configure(AutoTagSettings(enabled = true, cover = false))
        awaitAuto { worker.status.value.processed == 3 && !worker.status.value.running }
        check(worker.status.value.updated == 1 && worker.status.value.protected == 1)
        delay(120); check(passes.get() == 1) { "Idle worker re-read the library" }
        check(worker.status.value.completedAt > 0 && worker.status.value.path.isEmpty() && worker.status.value.phase == "已完成 · 等待新歌曲")
        check(maximum.get() <= 48 && ledger.isProtected(item.id, Path.of(item.path)))
        check(TagFiles.read(Path.of(item.path)).values[MusicTag.ALBUM] == "叶惠美")
        val tag = org.jaudiotagger.audio.AudioFileIO.read(Path.of(item.path).toFile()).tag
        check(tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS) == "[00:00.000]保留<00:00.500>逐字")
        val before = searches.get(); worker.scanNow()
        awaitAuto { passes.get() >= 2 && worker.status.value.processed == 3 && !worker.status.value.running }
        check(searches.get() == before) { "Protected songs were searched again" }
        Files.list(Path.of(item.path).parent).use { files -> check(files.noneMatch { it.fileName.toString().startsWith(".spw-tags-") }) }
    } finally { worker.close() }
    val reopened = AutoTagger(AutoTagStore(root.resolve("ledger")), tracks = { listOf(item) }, protected = { false }, current = { false }, search = { _, _ -> error("protected song searched") }, requestDelayMillis = 0)
    try {
        reopened.configure(AutoTagSettings(enabled = true, cover = false))
        awaitAuto { reopened.status.value.completedAt > 0 && !reopened.status.value.running }
        check(reopened.status.value.total == 0 && reopened.status.value.failed == 0)
    } finally { reopened.close() }

    suspend fun interruptedCase(kind: String) {
        val target = track(kind)
        val state = AutoTagStore(root.resolve("state-$kind"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val playing = AtomicBoolean(false); val allowed = AtomicBoolean(true); val checks = AtomicInteger()
        val runner = AutoTagger(state, tracks = { listOf(target) }, protected = { false },
            current = { if (kind == "play-at-commit") checks.incrementAndGet() >= 3 else playing.get() },
            search = { source, _ -> choices().filter { it.source == source } },
            fetch = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); fetched() },
            canWrite = { allowed.get() }, requestDelayMillis = 0)
        val before = Files.readAllBytes(Path.of(target.path))
        try {
            runner.configure(AutoTagSettings(enabled = true, cover = false))
            awaitAuto { entered.count == 0L }
            check(runner.status.value.phase == "正在获取标签" && runner.status.value.path == target.path && runner.status.value.running)
            when (kind) {
                "disable" -> runner.configure(AutoTagSettings(enabled = false, cover = false))
                "pause" -> runner.togglePause()
                "play" -> playing.set(true)
                "permission" -> allowed.set(false)
                "manual" -> state.protect(target.id, Path.of(target.path))
            }
            release.countDown()
            awaitAuto { !runner.status.value.running && (runner.status.value.processed == 1 || runner.status.value.phase == "未开启") }
            check(Files.readAllBytes(Path.of(target.path)).contentEquals(before)) { "$kind wrote after state changed" }
            if (kind != "manual") check(!state.isProtected(target.id, Path.of(target.path)))
            if (kind == "pause") {
                runner.togglePause()
                awaitAuto { state.isProtected(target.id, Path.of(target.path)) && !runner.status.value.running }
                check(TagFiles.read(Path.of(target.path)).values[MusicTag.ALBUM] == "叶惠美")
            }
        } finally { release.countDown(); runner.close() }
    }
    for (kind in listOf("disable", "pause", "play", "permission", "manual", "play-at-commit")) interruptedCase(kind)
    val ambiguous = track("conflicting-clues")
    TagFiles.write(Path.of(ambiguous.path), mapOf(MusicTag.ARTIST to "另一歌手"), null, null, null)
    val ambiguousBytes = Files.readAllBytes(Path.of(ambiguous.path))
    val careful = AutoTagger(AutoTagStore(root.resolve("ambiguous-state")), tracks = { listOf(ambiguous) }, protected = { false }, current = { false },
        search = { source, target -> listOf(Candidate(source, target.artist, target.title, target.artist, "叶惠美", 1000)) },
        fetch = { _, _ -> error("Conflicting tag and filename identities reached the writer") }, requestDelayMillis = 0)
    try {
        careful.configure(AutoTagSettings(enabled = true, cover = false))
        awaitAuto { careful.status.value.processed == 1 && !careful.status.value.running }
        check(careful.status.value.updated == 0 && careful.status.value.failed == 0 && Files.readAllBytes(Path.of(ambiguous.path)).contentEquals(ambiguousBytes))
    } finally { careful.close() }
    val batchTracks = (1..4).map { track("parallel-$it") }
    val batchLedger = AutoTagStore(root.resolve("batch-state"))
    val networkCount = AtomicInteger(); val networkMax = AtomicInteger()
    val fetchCount = AtomicInteger(); val fetchMax = AtomicInteger()
    val refreshCount = AtomicInteger(); val published = java.util.concurrent.CopyOnWriteArrayList<String>()
    val rendezvous = java.util.concurrent.CyclicBarrier(2)
    val batchWorker = AutoTagger(batchLedger, tracks = { batchTracks }, protected = { false }, current = { false },
        search = { source, _ ->
            val n = networkCount.incrementAndGet(); networkMax.accumulateAndGet(n, ::maxOf)
            try { Thread.sleep(60); choices().filter { it.source == source } } finally { networkCount.decrementAndGet() }
        }, fetch = { _, _ ->
            val n = fetchCount.incrementAndGet(); fetchMax.accumulateAndGet(n, ::maxOf)
            try { rendezvous.await(5, TimeUnit.SECONDS); fetched() } finally { fetchCount.decrementAndGet() }
        }, refresh = { batch ->
            if (refreshCount.incrementAndGet() == 1) error("simulated library refresh failure")
            published.addAll(batch.map { it.id })
        }, requestDelayMillis = 0)
    val started = System.nanoTime()
    try {
        batchWorker.configure(AutoTagSettings(enabled = true, cover = false))
        awaitAuto { batchWorker.status.value.processed == 4 && !batchWorker.status.value.running }
        check(batchWorker.status.value.updated == 4 && fetchMax.get() in 2..4 && networkMax.get() in 2..48)
        check(refreshCount.get() == 1 && published.isEmpty()) { "Each song independently refreshed the library" }
        batchWorker.scanNow()
        awaitAuto { published.size == 4 && !batchWorker.status.value.running }
        check(refreshCount.get() == 2 && published.toSet() == batchTracks.map { it.id }.toSet())
        batchTracks.forEach { check(batchLedger.isProtected(it.id, Path.of(it.path)) && TagFiles.read(Path.of(it.path)).values[MusicTag.ALBUM] == "叶惠美") }
        println("PASS: bounded parallel preparation, serialized protected writes and coalesced refresh; failed refresh retries without rewriting. Controlled sample: ${(System.nanoTime() - started) / 1_000_000} ms.")
    } finally { batchWorker.close() }
    println("PASS: disabled default, real test-file writes, lyric preservation, persisted protection/restart, four-request limit, pause/resume, disable, playback races, manual protection races and permission revocation.")
}
