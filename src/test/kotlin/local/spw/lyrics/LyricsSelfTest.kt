@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation

private const val SAMPLE = "[00:01.00]one<00:01.50>two\n[00:01.00]translation\n[00:03.00]end"

fun main(args: Array<String>) {
    if (args.firstOrNull() == "timing") { checkTimingEditor(); return }
    if (args.firstOrNull() == "release-fixes") { checkDeferredAutoScan(); checkLyricsCleanup(); return }
    if (args.firstOrNull() == "lyrics-persistence") { checkLyricsPersistence(); return }
    if (args.firstOrNull() == "entry-ui") { checkMoreMenu(); checkEntryControls(); return }
    if (args.firstOrNull() == "native-host") {
        checkMoreMenu(); checkEntryControls(); checkCovers(); checkNativeLyricLayout(); checkCountdownRenderer()
        checkNativeBindings(); return
    }
    if (args.firstOrNull() == "network") { checkNetwork(); return }
    if (args.firstOrNull() == "word-network") { checkWordNetwork(); return }
    if (args.firstOrNull() == "aicuo-network") { checkAicuoNetwork(); return }
    if (args.firstOrNull() == "qq-network") { checkQqNetwork(); return }
    if (args.firstOrNull() == "search-regression") { checkSearchRecovery(); checkQqMetadata(); return }
    if (args.firstOrNull() == "studio") { checkStudioScreens(); return }
    if (args.firstOrNull() == "studio-preview") { checkStudioScreens(false); return }
    if (args.firstOrNull() == "auto-window") { checkAutoWindow(); return }
    if (args.firstOrNull() == "documents") { checkDocuments(); checkCovers(); return }
    if (args.firstOrNull() == "auto-tags") { checkAutoMatching(); checkAutoLedger(); checkAutoWorker(); checkIncrementalScan(); return }
    if (args.firstOrNull() == "countdown") { checkInterludeCountdown(); return }
    if (args.firstOrNull() == "countdown-renderer") { checkCountdownRenderer(); return }
    if (args.firstOrNull() == "studio-state") { checkStudioNavigation(); return }
    if (args.firstOrNull() == "community-sources") { checkCommunitySources(); checkSourceRecovery(); return }
    if (args.firstOrNull() == "community-network") { checkCommunityNetwork(); return }
    if (args.firstOrNull() == "reported-network") { checkReportedCommunityNetwork(); return }
    if (args.firstOrNull() == "auto-benchmark") { checkAutoThroughput(); return }
    if (args.firstOrNull() == "tags-network") { checkTagNetwork(); return }
    checkMoreMenu()
    checkEntryControls()
    checkTransport()
    checkSearchRecovery()
    checkQqMetadata()
    checkCommunitySources()
    checkSourceRecovery()
    checkDocuments()
    checkCovers()
    checkPreferences()
    checkTimingEditor()
    checkStudioNavigation()
    checkInterludeCountdown()
    checkSearchRanking()
    checkAutoMatching()
    checkAutoLedger()
    checkAutoWorker()
    checkIncrementalScan()
    checkDeferredAutoScan()
    checkLyricsCleanup()
    checkAutoThroughput()
    checkTagFiles()
    checkLyricsPersistence()
    checkTagPlayback()
    checkCoverParsers()
    checkNativeLyricLayout()
    checkCountdownRenderer()
    checkLongTimeline()
    checkNativeBindings()
    checkNativeControls()
    checkAutoWindow()
    checkStudioScreens()
}

private fun checkNativeBindings() {
    val oldSize = NativeHost.fontSize(); val oldWeight = NativeHost.fontWeight(); val oldAlignment = NativeHost.alignment()
    NativeHost.fontSize(27); NativeHost.fontWeight(600); NativeHost.alignment(2)
    check(NativeHost.fontSize() == 27 && NativeHost.fontWeight() == 600 && NativeHost.alignment() == 2)
    val hostPreferences = java.util.prefs.Preferences.userRoot().node("com/xuncorp/voxzen")
    check(hostPreferences.getInt("lyrics_font_size", 0) == 27)
    check(hostPreferences.getInt("lyrics_font_weight", 0) == 600)
    NativeHost.fontSize(oldSize); NativeHost.fontWeight(oldWeight); NativeHost.alignment(oldAlignment)
    println("PASS: size, weight and alignment use SPW's real persistence bridge, isolated from user preferences.")
    NativeUi.validate()
    local.spw.host.HookInternals.ui(NativeHost.PLAYER, NativeHost.originalPlayer.name, NativeHost.PLAYER_CALLER, listOf("androidx.compose.ui.Modifier", "boolean"))
    local.spw.host.HookInternals.ui(LyricsControlsHook.OWNER, LyricsControlsHook.original.name, LyricsControlsHook.CALLER, listOf("androidx.compose.ui.Modifier"))
    local.spw.host.HookInternals.ui(CountdownRenderer.native.declaringClass.name, CountdownRenderer.native.name, CountdownRenderer.caller, CountdownRenderer.parameters)
    local.spw.host.HookInternals.method(NativeHost.timelineCompanion.name, NativeHost.timelineMethod.name, listOf(NativeHost.documentType.name))

    val companion = HostNames.resolver.receiver(NativeHost.timelineMethod)
    val convert = NativeHost.timelineMethod
    @Suppress("UNCHECKED_CAST")
    fun timeline(document: Any) = convert.invoke(companion, document) as List<PlaybackExtensionPoint.LyricsLine>
    val baseline = NativeHost.decode(SAMPLE)
    val initial = timeline(baseline)
    check(initial.size == 2 && initial[0].pureMainText == "onetwo" && initial[0].pureSubText == "translation")
    val moved = timeline(NativeHost.shifted(baseline, -2000))
    check(moved[0].startTime == -1000L && moved[0].endTime == 1000L)
    check(moved[0].lyricsCells[0].startTime == -1000L && moved[0].lyricsCells[1].startTime == -500L)
    check(moved[0].pureMainText == initial[0].pureMainText && moved[0].pureSubText == initial[0].pureSubText)
    check(timeline(baseline) == initial) { "Offset mutated the source timeline" }
    NativeHost.applyOffsetBeforePublication(baseline, 500)
    NativeHost.applyOffsetBeforePublication(baseline, 500)
    check(timeline(baseline)[0].startTime == 1500L) { "Offset was applied twice" }
    check(NativeHost.raw(baseline) == SAMPLE)

    val directory = Files.createTempDirectory(Path.of("build"), "self-check-")
    val store = LyricsStore(directory)
    val saved = SavedLyrics(SAMPLE, "test", 500)
    store.save("../same-id", saved)
    check(LyricsStore(directory).get("../same-id") == saved) { "Saved lyrics did not survive a new store" }
    check(Files.list(directory).allMatch { it.parent == directory && it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) })
    try { store.save("../same-id", saved.copy(offset = 60_001)); error("Invalid offset accepted") } catch (_: IllegalArgumentException) { }
    check(LyricsStore(directory).get("../same-id") == saved) { "Rejected save destroyed previous data" }
    check(LyricSources.htmlDecode("&#65;&#x4F60;&amp;&lt;") == "A你&<")
    check(LyricSources.applyLrcOffset("[offset:200]\n[00:01.00]a").contains("[00:01.200]a"))
    check(LyricSources.applyLrcOffset("[00:01.00]a") == "[00:01.00]a")
    checkReload(store, directory)
    check(LyricsPlayback().onBeforeLoadLyrics(PlaybackExtensionPoint.MediaItem("title", "artist", "album", "", "path")) == null)
    println("PASS: native controls and hook targets; native line/word/translation offsets; durable atomic saves; same-song host reload; stopped provider.")
}

private fun checkPreferences() {
    val directory = Files.createTempDirectory(Path.of("build"), "preferences-check-")
    val path = directory.resolve("settings.properties")
    val store = LyricsPreferencesStore(path)
    check(store.load().format == LyricsFormat.WORD)
    val value = LyricsPreferences(format = LyricsFormat.ENHANCED, source = LyricSource.QQ,
        enabled = setOf(LyricSource.QQ, LyricSource.KUGOU), exportFormat = LyricsFormat.TTML,
        chinese = ChineseMode.SIMPLIFIED, translation = false, romanization = true,
        order = LyricsPart.entries.reversed(), tagFields = setOf(MusicTag.TITLE, MusicTag.ARTIST), tagCover = false, tagLyrics = false, coverSources = setOf(CoverSource.KUGOU, CoverSource.ITUNES),
        autoTags = AutoTagSettings(enabled = true, mode = AutoTagMode.FILL, sources = setOf(LyricSource.NETEASE, LyricSource.KUGOU), fields = setOf(MusicTag.TITLE), cover = false, concurrency = 12), interludeCountdown = true,
        countdown = CountdownSettings(5000, 6500, CountdownAlignment.LEFT))
    store.save(value)
    check(value.applyContentTo(SavedLyrics(format = LyricsFormat.LINE)).format == LyricsFormat.LINE) { "Content settings changed the current lyric timing mode" }
    check(LyricsPreferencesStore(path).load() == value)
    for (legacyStyle in listOf("NUMBER", "TRACE", "DOTS")) {
        Files.writeString(path, "\ncountdown.style=$legacyStyle\n", java.nio.file.StandardOpenOption.APPEND)
        check(store.load() == value) { "Legacy countdown style prevented migration to dots" }
    }
    store.save(value)
    check("countdown.style" !in Files.readString(path))
    check(countdownSeconds("1.275", 30) == 1275L)
    for (invalid in listOf("0", "31", "NaN", "1.0001", "abc")) check(runCatching { countdownSeconds(invalid, 30) }.isFailure)
    check(runCatching { CountdownSettings(999) }.isFailure && runCatching { CountdownSettings(gapMillis = 120001) }.isFailure)
    for (invalid in listOf(0, 65)) check(runCatching { value.autoTags.copy(concurrency = invalid) }.isFailure)
    Files.writeString(path, "\nromanizationFontSize=18\n", java.nio.file.StandardOpenOption.APPEND)
    check(store.load() == value) { "Removed romanization font setting still overrides preferences" }
    check(runCatching { store.save(value.copy(autoTags = value.autoTags.copy(sources = setOf(LyricSource.NETEASE)))) }.isFailure)
    check(runCatching { store.save(value.copy(autoTags = value.autoTags.copy(fields = emptySet()))) }.isFailure)
    check(runCatching { store.save(value.copy(coverSources = emptySet())) }.isFailure)
    check(runCatching { store.save(value.copy(enabled = emptySet())) }.isFailure)
    check(runCatching { store.save(value.copy(source = LyricSource.AMLL)) }.isFailure)
    check(store.load() == value) { "Rejected preferences damaged the saved settings" }
    val media = PlaybackExtensionPoint.MediaItem("song", "artist", "album", "", "path")
    val state = StudioState(media, SavedLyrics(), "", StudioPage.SEARCH, preferences = value, persistPreferences = store::save)
    state.updatePreferences(value.copy(format = LyricsFormat.WORD, source = LyricSource.KUGOU))
    val reopened = StudioState(media, SavedLyrics(), "", StudioPage.SEARCH, preferences = LyricsPreferencesStore(path).load())
    check(reopened.preferences.format == LyricsFormat.WORD && reopened.sourceFilter == LyricSource.KUGOU)
    check(reopened.exportFormat == LyricsFormat.TTML)
    state.updatePreferences(value.copy(enabled = emptySet()))
    check(state.preferences == reopened.preferences && state.error.isNotBlank())
    println("PASS: global word/source/content/export preferences survive reopening; invalid saves preserve previous settings.")
}

private fun checkReload(store: LyricsStore, directory: Path) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val timelineType = NativeHost.timelineType
    val timelineListeners: (Any) -> Unit = { }
    val nativeTimeline = timelineType.constructors.single().newInstance(scope, timelineListeners)
    val itemType = Class.forName("com.xuncorp.pisces.PiscesMediaItem")
    val item = itemType.getConstructor(*Array(6) { String::class.java }).newInstance("same-song", "title", "artist", "album", "", "path")
    val id = HostNames.resolver.getter(itemType, "id").invoke(item) as String
    val count = AtomicInteger()
    val latch = AtomicReference(CountDownLatch(1))
    val applied = AtomicReference<Any>()
    val load: (Any, Continuation<Any>) -> Any = { _, _ ->
        count.incrementAndGet()
        val saved = LyricsStore(directory).get(id)
        NativeHost.shifted(NativeHost.decode(saved.text ?: SAMPLE), saved.offset)
    }
    val apply: (Any) -> Unit = { applied.set(it); latch.get().countDown() }
    val failure: (Throwable) -> Unit = { throw AssertionError("Native reload failed", it) }
    val loaderType = Class.forName("com.xuncorp.voxzen.service.PlaybackLyricsLoader")
    val loader = loaderType.constructors.single().newInstance(scope, nativeTimeline, load, apply, failure)
    try {
        loaderType.getMethod("onLoaded", itemType, Boolean::class.javaPrimitiveType).invoke(loader, item, false)
        check(latch.get().await(5, TimeUnit.SECONDS)) { "Initial lyrics were not applied" }
        val generation = loaderType.getDeclaredField("generation").apply { isAccessible = true }
        val before = generation.getLong(loader)
        loaderType.getMethod("reload", String::class.java).invoke(loader, "different-song")
        check(generation.getLong(loader) == before) { "A stale search could reload another song" }
        store.save(id, SavedLyrics("[00:01.00]updated\n[00:02.00]line", "test", 300))
        latch.set(CountDownLatch(1))
        loaderType.getMethod("reload", String::class.java).invoke(loader, id)
        check(latch.get().await(5, TimeUnit.SECONDS)) { "Same-song reload did not publish lyrics" }
        check(count.get() == 2 && NativeHost.raw(applied.get()).contains("updated"))
    } finally { loaderType.getMethod("release").invoke(loader); scope.cancel() }
}

private fun checkNetwork() = runBlocking {
    val sources = LyricSources()
    LyricSource.entries.map { source -> async(Dispatchers.IO) {
        try {
            val results = sources.search(source, "晴天", "周杰伦")
            check(results.isNotEmpty()) { "No results" }
            val preferred = results.firstOrNull { it.title == "晴天" && it.artist.contains("周杰伦") && it.duration > 200_000 }
                ?: results.first()
            val loaded = sources.download(preferred)
            val document = loaded.document()
            println("NETWORK ${source.name}: results=${results.size}, rows=${document.rows.size}, words=${document.hasWords}, kind=${document.kind}")
        } catch (failure: Exception) { println("NETWORK ${source.label}: FAILED ${failure.javaClass.simpleName}: ${failure.message}") }
    } }.awaitAll()
    CoverSource.entries.map { source -> async(Dispatchers.IO) {
        try {
            val found = Covers.search(source, "周杰伦 叶惠美")
            check(found.isNotEmpty())
            val data = Covers.normalize(Network.bytes(java.net.URI.create(found.first().url)), source.label)
            println("COVER ${source.name}: results=${found.size}, image=${data.width}x${data.height}")
        } catch (failure: Exception) { println("COVER ${source.name}: FAILED ${failure.javaClass.simpleName}: ${failure.message}") }
    } }.awaitAll()
}

private fun checkWordNetwork() = runBlocking {
    val sources = LyricSources()
    val available = LyricSources.available(LyricsFormat.WORD)
    check(LyricSource.LRCLIB !in available)
    val counts = available.map { source -> async(Dispatchers.IO) {
        val found = sources.searchTyped(source, "晴天", "周杰伦", LyricsFormat.WORD)
        if (found.isEmpty() && source == LyricSource.NETEASE) {
            val yrc = sources.download(Candidate(source, "26096272", "YRC fixture", ""), LyricsFormat.WORD)
            check(yrc.document().hasWords && yrc.document().kind == "YRC 逐字")
            println("WORD SEARCH NETEASE: line-only matches filtered; independent YRC sample verified")
            return@async 0
        }
        check(found.isNotEmpty()) { "$source returned no word lyrics" }
        check(found.all { it.loaded?.document()?.hasWords == true })
        val loaded = sources.download(found.first(), LyricsFormat.WORD)
        check(LyricsCodec.render(loaded.document(), SavedLyrics(format = LyricsFormat.WORD)).hasWords)
        check(!LyricsCodec.render(loaded.document(), SavedLyrics(format = LyricsFormat.LINE)).hasWords)
        println("WORD SEARCH $source: verified=${found.size}, kind=${loaded.document().kind}, rows=${loaded.document().rows.size}")
        found.size
    } }.awaitAll()
    check(counts.size == available.size)
    val ttml = sources.searchTyped(LyricSource.AMLL, "晴天", "周杰伦", LyricsFormat.TTML)
    check(ttml.isNotEmpty() && ttml.all { it.loaded!!.document().kind == "TTML" })
    println("PASS: real word search, line rendering, and TTML source filtering.")
}

private fun checkAicuoNetwork() = runBlocking {
    val sources = LyricSources()
    val results = sources.searchTyped(LyricSource.KUGOU, "爱错", "王力宏", LyricsFormat.WORD)
    check(results.isNotEmpty()) { "Kugou returned no verified word lyrics for 爱错" }
    val original = results.first { it.title == "爱错" && it.artist == "王力宏" }
    check(original.hash.isNotBlank() && original.duration in 230000..245000) { "The original song was replaced with a short clip" }
    val document = checkNotNull(original.loaded).document()
    check(document.hasWords && document.kind == "KRC 逐字")
    val firstVocal = document.rows.first { it.text == "北风毫不留情" }
    check(firstVocal.start in 13000..16000 && firstVocal.words.size == 6)
    check(firstVocal.words[1].start > firstVocal.words[0].start && firstVocal.words.last().end <= firstVocal.end)
    check(document.rows.any { it.text == "我从来没想过我会这样做" })
    check(LyricsCodec.parse(LyricsCodec.export(document, LyricsFormat.WORD)).rows.first { it.text == firstVocal.text }.words == firstVocal.words)
    println("PASS: 爱错 / 王力宏 resolved by song hash, duration=${original.duration}, verified=${results.size}, KRC first-vocal=${firstVocal.start}, real-word-times and exported word timing preserved.")
}

private fun checkQqNetwork() = runBlocking {
    val sources = LyricSources()
    val results = sources.searchTyped(LyricSource.QQ, "爱错", "王力宏", LyricsFormat.WORD)
    check(results.isNotEmpty()) { "QQ returned no verified QRC results" }
    val original = results.first { it.title == "爱错" && it.artist == "王力宏" }
    val loaded = checkNotNull(original.loaded)
    val document = loaded.document()
    check(document.hasWords && document.kind == "QRC 逐字" && original.songId == "105905415")
    val vocal = document.rows.first { it.text == "北风毫不留情" }
    check(vocal.words.size == 6 && vocal.words[1].start == 15030L)
    val native = NativeHost.decode("[00:01.00]placeholder")
    val saved = loaded.applyTo(SavedLyrics(offset = 100), null, LyricsFormat.WORD, "")
    NativeHost.applySettings(native, saved)
    val nativeVocal = NativeHost.model(native).rows.first { it.text == vocal.text }
    check(nativeVocal.words == vocal.words.map { it.copy(start = it.start + 100, end = it.end + 100) })
    for (format in listOf(LyricsFormat.WORD, LyricsFormat.ENHANCED, LyricsFormat.TTML)) {
        check(LyricsCodec.parse(LyricsCodec.export(document, format)).rows.first { it.text == vocal.text }.words == vocal.words)
    }
    println("PASS: QQ 爱错 search -> real QRC download/decrypt -> word preview model -> host native word cells with offset; WORD/ENHANCED/TTML retain actual timestamps. Verified=${results.size}")
}
