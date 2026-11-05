@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

fun checkAutoThroughput() = runBlocking {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "throughput-")
    val blank = root.resolve("blank.flac")
    Files.copy(Path.of("src/test/resources/audio/tone.flac"), blank)
    TagFiles.write(blank, MusicTag.entries.associateWith { "" }, null, null, null)
    val tracks = (1..200).map { n ->
        val title = "测试歌曲$n"
        val path = root.resolve("$title - 测试歌手.flac"); Files.copy(blank, path)
        MediaItem("", "", "", "", path.toString(), id = "speed-$n", duration = 1000)
    }
    suspend fun finish(worker: AutoTagger) = withTimeout(60_000) { while (worker.status.value.processed != 200 || worker.status.value.running) delay(10) }
    val cover = sampleCover()
    for (mode in listOf("basic-write", "unchanged", "complete")) {
        val write = mode != "unchanged"
        val complete = mode == "complete"
        val batch = if (!complete) tracks else tracks.map { track ->
            val folder = Files.createDirectories(root.resolve("complete"))
            val path = folder.resolve(Path.of(track.path).fileName); Files.copy(blank, path)
            track.copy(path = path.toString(), id = "full-${track.id}")
        }
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger(); val cancelled = AtomicInteger()
        val ledger = AutoTagStore(root.resolve("state-$mode"))
        val worker = AutoTagger(ledger, tracks = { batch }, protected = { false }, current = { false }, search = { source, target ->
            calls.incrementAndGet(); val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
            try {
                Thread.sleep(if (source == LyricSource.KUGOU) 4000 else 250)
                if (target.artist == "测试歌手") listOf(Candidate(source, target.title, target.title, target.artist, "测试专辑", 1000)) else emptyList()
            } catch (cancel: InterruptedException) { cancelled.incrementAndGet(); throw cancel }
            finally { active.decrementAndGet() }
        }, fetch = { candidate, includeCover ->
            check(complete && includeCover) { "Basic-tag-only work requested an unnecessary detail fetch" }
            calls.incrementAndGet(); val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
            try {
                Thread.sleep(250)
                MusicTags(MusicTag.entries.associateWith { when (it) {
                    MusicTag.TITLE -> candidate.title; MusicTag.ARTIST, MusicTag.ALBUM_ARTIST -> candidate.artist; MusicTag.ALBUM -> candidate.album
                    MusicTag.YEAR -> "2026"; MusicTag.TRACK, MusicTag.DISC -> "1"; MusicTag.GENRE -> "Pop"; else -> "作者"
                } }, cover)
            } finally { active.decrementAndGet() }
        })
        val start = System.nanoTime()
        try {
            worker.configure(AutoTagSettings(enabled = true, fields = if (complete) MusicTag.entries.toSet() else setOf(MusicTag.TITLE, MusicTag.ARTIST, MusicTag.ALBUM), cover = complete, concurrency = 32))
            finish(worker)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            check(elapsed < 30_000) { "Controlled 200-track pass took $elapsed ms" }
            check(worker.status.value.failed == 0 && worker.status.value.updated == if (write) 200 else 0)
            check(maximum.get() in 2..32 && cancelled.get() > 0) { "Slow third source held up confirmed matches" }
            batch.forEach {
                val tags = TagFiles.read(Path.of(it.path), false)
                check(tags.values[MusicTag.ALBUM] == "测试专辑")
                if (complete) check(tags.cover?.bytes?.contentEquals(cover.bytes) == true && tags.values.values.all(String::isNotBlank))
            }
            println("BENCHMARK: 200 $mode files; $elapsed ms; requests=${calls.get()}; max=${maximum.get()}; cancelled slow requests=${cancelled.get()}; simulated fast sources/detail=250 ms, slow source=4000 ms; tiny local fixtures, no live API throughput claim.")
        } finally { worker.close() }
    }
    val errors = AtomicInteger()
    for (limit in listOf(1, 4, 8)) {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val bounded = AutoTagger(AutoTagStore(root.resolve("limit-$limit")), tracks = { tracks.take(8) }, protected = { false }, current = { false },
            search = { source, target ->
                val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
                try { Thread.sleep(30); listOf(Candidate(source, target.title, target.title, target.artist, "测试专辑", 1000)) } finally { active.decrementAndGet() }
            })
        try {
            bounded.configure(AutoTagSettings(enabled = true, fields = setOf(MusicTag.TITLE, MusicTag.ARTIST, MusicTag.ALBUM), cover = false, concurrency = limit))
            withTimeout(15_000) { while (bounded.status.value.processed != 8 || bounded.status.value.running) delay(10) }
            check(maximum.get() in 1..limit && bounded.status.value.failed == 0) { "Configured request cap $limit ignored: ${maximum.get()}" }
        } finally { bounded.close() }
    }
    println("PASS: configured 1, 4 and 8 request caps are enforced by the actual worker.")
    val worker = AutoTagger(AutoTagStore(root.resolve("offline-state")), tracks = { tracks }, protected = { false }, current = { false },
        search = { _, _ -> errors.incrementAndGet(); throw java.io.IOException("offline") })
    try {
        val start = System.nanoTime()
        worker.configure(AutoTagSettings(enabled = true, cover = false))
        finish(worker)
        check(worker.status.value.failed == 200 && worker.status.value.updated == 0)
        check(errors.get() in 3..96) { "Unavailable source was retried for every song: ${errors.get()}" }
        check((System.nanoTime() - start) / 1_000_000 < 15_000) { "Per-song failure waits still block the queue" }
        println("PASS: 200 failures are reported, offline sources cool down after repeated failures without per-song sleeps; calls=${errors.get()}.")
    } finally { worker.close() }
}
