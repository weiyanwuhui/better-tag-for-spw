package local.spw.lyrics

class TestTimingPlayback : TimingPlayback {
    var active = true
    var value = TimingClock(10_000, true)
    var commands = 0
    override fun current() = active
    override fun clock() = value
    override fun play() { value = value.copy(playing = true); commands++ }
    override fun pause() { value = value.copy(playing = false); commands++ }
    override fun seek(position: Long) { value = value.copy(position = position); commands++ }
    override fun speed(value: Float) { this.value = this.value.copy(speed = value); commands++ }
}

fun checkTimingEditor() {
    checkContinuousTiming()
    val text = "[00:01.000]<00:01.000>晚<00:01.500>风<00:02.000>\n[00:03.000]下一行"
    val original = LyricsCodec.parse(text).let { it.copy(rows = it.rows.map { row -> row.copy(translation = "译文", romanization = "wan feng", agent = "v1") }) }
    val timing = LyricsTiming()
    val row = original.rows[0]
    val moved = editRowTimes(row, row.start + 1000, row.end, 0, row.words[0], row.text)
    check(moved.end == row.end + 1000 && moved.words.first().start == row.words.first().start + 1000)
    val earlierWord = editRowTimes(row, row.start, row.end, 0, row.words[0].copy(start = 800), row.text)
    check(earlierWord.start == 800L)
    val longerWord = editRowTimes(row, row.start, row.end, row.words.lastIndex, row.words.last().copy(end = row.end + 500), row.text)
    check(longerWord.end == row.end + 500)
    check(runCatching { editRowTimes(row, row.start, row.start + 100, 0, null, row.text) }.isFailure)
    timing.begin(10_000)
    val (changed, next) = timing.finish(original, 0, 12_000, 500)
    check(next == 1 && changed.rows[0].start == 10_000L && changed.rows[0].end == 12_000L)
    check(changed.rows[0].words.first().start == 10_000L && changed.rows[0].words.last().end <= 12_000)
    check(changed.rows[0].translation == "译文" && changed.rows[0].romanization == "wan feng" && changed.rows[0].agent == "v1")
    check(changed.rows[1].start == original.rows[1].start + 500)
    check(timing.undo()?.document == original && !timing.canUndo)
    timing.begin(100); check(runCatching { timing.finish(original, 0, 99) }.isFailure)
    check(!timing.canUndo); timing.cancel(); check(timing.started == null)

    val plain = plainTimingDocument("晚风吹过\nHello world! 👩‍🎤\n\n下一行")
    check(plain.rows.size == 3 && plain.rows.all { it.start == 0L && it.end == 0L })
    check(timingTokens(plain.rows[0]) == listOf("晚", "风", "吹", "过"))
    check(timingTokens(plain.rows[1]).joinToString("") == plain.rows[1].text)
    check(timingTokens(plain.rows[1]).first() == "Hello ")
    timing.mode = TimingMode.WORD
    var document = plain
    var line = 0
    repeat(4) { index ->
        timing.begin(1000 + index * 500L)
        val result = timing.finish(document, line, 1400 + index * 500L)
        document = result.first; line = result.second
        check(document.rows[0].text == "晚风吹过")
    }
    check(line == 1 && timing.word == 0 && document.rows[0].hasWords)
    check(document.rows[0].words.map { it.start } == listOf(1000L, 1500L, 2000L, 2500L))
    val roundTrip = LyricsCodec.parse(LyricsCodec.ttml(document))
    check(roundTrip.rows.any { it.text == "晚风吹过" && it.words == document.rows[0].words })

    val media = com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem("曲", "歌手", "专辑", "", "file", id = "timing", duration = 300_000)
    val state = StudioState(media, SavedLyrics(text, offset = 500), text, StudioPage.EDIT)
    val filtered = StudioState(media, SavedLyrics(text), text, StudioPage.SEARCH, preferences = LyricsPreferences(source = LyricSource.LRCLIB))
    check(filtered.sourceFilter == null && filtered.preferences.source == null)
    val playback = TestTimingPlayback()
    state.document = original; state.editorTab = 1; state.timingPlayback = playback
    val before = state.draft
    state.captureTiming(false); state.captureTiming(false)
    check(state.draft == before && !state.hasUnsavedLyrics()) { "An unfinished/repeated press changed lyrics" }
    playback.value = playback.value.copy(position = 12_000)
    state.captureTiming(true)
    check(state.hasUnsavedLyrics() && state.draft.offset == 0L && state.document!!.rows[0].start == 10_000L)
    check(state.timing.lastRecorded == TimingMark(original.rows[0].text, 10_000, 12_000))
    check(state.documentDirty && state.draft.text == before.text) { "Each tap serialized the entire song" }
    check(state.saved == before && LyricsCodec.render(state.document!!, state.draft).rows.first().start == 10_000L) { "Timing double-applied the original offset or saved itself" }
    state.undoTiming()
    check(state.timing.lastRecorded == null)
    check(state.draft == before && state.document == original && !state.hasUnsavedLyrics()) { "Undo did not restore the exact original draft" }
    playback.value = playback.value.copy(position = 13_000)
    state.captureTiming(false); playback.value = playback.value.copy(position = 14_000); state.captureTiming(true)
    state.changeDocument(checkNotNull(state.document))
    check(!state.documentDirty && LyricsCodec.source(state.draft, "").rows.any { it.start == 13_000L })
    state.undoTiming(); check(state.draft == before && !state.hasUnsavedLyrics())
    playback.value = playback.value.copy(playing = false)
    state.captureTiming(false); check(state.timing.started == null && state.error.isNotBlank())
    playback.active = false
    val commands = playback.commands
    state.replayTimingRow(); state.captureTiming(false)
    check(playback.commands == commands && state.draft == before) { "Timing controlled or edited a different song" }

    state.selectEditorRow(1)
    check(state.line == 1 && playback.commands == commands && state.draft == before)
    playback.active = true
    state.selectEditorRow(1)
    check(playback.value.position == 500L && !playback.value.playing && state.timingClock == playback.value) { "Paused row selection did not seek with a three-second lead and offset" }
    check(state.draft == before && !state.hasUnsavedLyrics()) { "Selecting a row edited the lyrics" }
    playback.value = playback.value.copy(playing = true)
    state.selectEditorRow(0)
    check(playback.value.position == 0L && playback.value.playing) { "Early row seek underflowed or changed play state" }
    val afterSelection = playback.commands
    state.flushEditor = { false }; state.selectEditorRow(1)
    check(state.line == 0 && playback.commands == afterSelection) { "Failed editor validation did not block row navigation" }
    state.flushEditor = null; state.editorTab = 0; state.selectEditorRow(1)
    check(state.line == 1 && playback.commands == afterSelection) { "Ordinary row editing unexpectedly sought playback" }
    state.editorTab = 1; state.line = 0

    val directory = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"), "entry-check-")
    val preferences = LyricsPreferencesStore(directory.resolve("settings.properties"))
    check(preferences.load().entry == LyricsEntry.CONTROLS)
    preferences.save(preferences.load().copy(entry = LyricsEntry.CONTROLS))
    check(LyricsPreferencesStore(directory.resolve("settings.properties")).load().entry == LyricsEntry.CONTROLS)
    preferences.save(preferences.load().copy(entry = LyricsEntry.CONTEXT))
    check(LyricsPreferencesStore(directory.resolve("settings.properties")).load().entry == LyricsEntry.CONTEXT)

    val grouped = regroupTimingRow(original.rows[0], listOf("晚风"))
    check(grouped.words.single().start == row.words.first().start && grouped.words.single().end == row.words.last().end)
    check(regroupTimingRow(grouped, listOf("晚", "风")).words == row.words)
    val gaps = row.copy(words = listOf(LyricWord(1000, 1200, "晚"), LyricWord(1600, 2000, "风")))
    check(regroupTimingRow(gaps, listOf("晚", "风")) == gaps) { "Grouping filled existing word gaps" }
    check(runCatching { regroupTimingRow(row, listOf("改变原文")) }.isFailure)
    state.document = original; state.line = 0; state.timing.clear(); state.timing.mode = TimingMode.WORD
    playback.active = true; playback.value = TimingClock(20_000, true)
    val testWindow = java.awt.Window(null as java.awt.Frame?)
    val keys = TimingWindowKeys(testWindow, state)
    val component = java.awt.Canvas()
    fun press(code: Int, release: Boolean = false) = keys.handle(java.awt.event.KeyEvent(component, if (release) java.awt.event.KeyEvent.KEY_RELEASED else java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED))
    press(java.awt.event.KeyEvent.VK_SPACE); press(java.awt.event.KeyEvent.VK_SPACE)
    check(state.timing.started == 20_000L && state.timing.word == 0)
    press(java.awt.event.KeyEvent.VK_F8, true)
    check(state.timing.started == 20_000L && state.timing.word == 0) { "Orphan F8 release ended the active tap" }
    press(java.awt.event.KeyEvent.VK_SPACE, true); playback.value = playback.value.copy(position = 20_500)
    press(java.awt.event.KeyEvent.VK_SPACE); press(java.awt.event.KeyEvent.VK_SPACE, true)
    check(state.timing.word == 1 && state.timing.started == 20_500L)
    playback.value = playback.value.copy(position = 21_000)
    press(java.awt.event.KeyEvent.VK_ENTER); press(java.awt.event.KeyEvent.VK_ENTER, true)
    check(state.line == 1 && state.timing.started == null && state.document!!.rows.first().words.map { it.start } == listOf(20_000L, 20_500L))
    press(java.awt.event.KeyEvent.VK_BACK_SPACE); press(java.awt.event.KeyEvent.VK_BACK_SPACE, true)
    check(state.line == 0 && state.timing.word == 1)
    state.editorTab = 2
    check(!press(java.awt.event.KeyEvent.VK_SPACE)) { "Timing shortcut stole text-field input" }
    state.editorTab = 1
    testWindow.dispose()
    val control = LyricsControlsHook.original
    check(control.parameterCount == 4 && control.parameterTypes[0] == androidx.compose.ui.Modifier::class.java)
    NativePlayerAccess.validate()
    println("PASS: line/word timing, row selection seeks with 3s pre-roll and original play state, offset/underflow/validation/switched-track guards, no edits from selection; exact undo and persistent entry.")
}

private fun checkContinuousTiming() {
    val document = plainTimingDocument("晚风\n下一句\n最后一句").let { text ->
        text.copy(rows = listOf(text.rows[0], LyricRow(0, 0, listOf(LyricWord(0, 0, ""))), text.rows[1], text.rows[2], LyricRow(0, 0, emptyList())))
    }
    val media = com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem("曲", "歌手", "", "", "file", id = "continuous", duration = 30_000)
    val saved = SavedLyrics(LyricsCodec.ttml(document), offset = 300)
    val playback = TestTimingPlayback()
    val state = StudioState(media, saved, saved.text.orEmpty(), StudioPage.EDIT).also {
        it.document = document; it.editorTab = 1; it.timingPlayback = playback
    }
    fun tap(time: Long) { playback.value = TimingClock(time, true); state.tapTiming(); check(state.error.isEmpty()) { state.error } }
    fun stop(time: Long) { playback.value = TimingClock(time, true); state.captureTiming(true); check(state.error.isEmpty()) { state.error } }
    tap(1000)
    check(!state.hasUnsavedLyrics())
    tap(2000)
    check(state.line == 2 && state.timing.started == 2000L && state.document!!.rows[0].end == 2000L) { "Line boundary needed a second tap or did not skip blank rows" }
    check(state.draft.offset == 0L && state.document!!.rows[3].start == 300L)
    state.undoTiming()
    check(state.line == 0 && state.timing.started == 1000L && state.draft == saved && state.document == document && !state.hasUnsavedLyrics())
    tap(2100)
    stop(3000)
    check(state.line == 3 && state.timing.started == null && playback.value.playing) { "Ending before an interlude started the next line or paused the song" }
    check(state.document!!.rows[2].start == 2100L && state.document!!.rows[2].end == 3000L)
    state.captureTiming(true)
    check(state.timing.started == null && state.line == 3) { "Repeated end advanced the cursor" }
    tap(8000)
    check(state.document!!.rows[3].start == 300L) { "Pending start leaked into the document" }
    tap(9000)
    check(state.line == 3 && state.timing.started == null && state.document!!.rows[3].start == 8000L && state.document!!.rows[3].end == 9000L) { "Final line continued into blank rows" }
    state.undoTiming()
    check(state.line == 3 && state.timing.started == 8000L)
    stop(9500)
    state.undoTiming()
    check(state.timing.started == 8000L) { "Undo after explicit stop lost the original start" }

    state.timing.clear(); state.line = 0; state.document = document; state.draft = saved; state.documentDirty = false
    state.timing.mode = TimingMode.WORD
    tap(1000); tap(1500); tap(2000)
    check(state.line == 2 && state.timing.word == 0 && state.timing.started == 2000L)
    check(state.document!!.rows[0].words.map { it.start to it.end } == listOf(1000L to 1500L, 1500L to 2000L))
    tap(2500)
    stop(2800)
    check(state.line == 2 && state.timing.word == 2 && state.timing.started == null)
    tap(4000); stop(4500)
    check(state.line == 3 && state.document!!.rows[2].words.last().start == 4000L) { "Word pause was included in the next word" }
    state.undoTiming()
    check(state.line == 2 && state.timing.word == 2 && state.timing.started == 4000L)
    val beforeError = state.document
    playback.value = TimingClock(4000, true); state.tapTiming()
    check(state.error.isNotEmpty() && state.document == beforeError && state.timing.started == 4000L) { "Zero-length tap wrote an edit" }
    playback.value = TimingClock(3500, true); state.tapTiming()
    check(state.error.isNotEmpty() && state.document == beforeError && state.timing.started == null) { "Backward seek reused a stale start" }
    tap(5000)
    state.timingAction { it.pause() }
    check(state.timing.started == null && state.document == beforeError)
    playback.value = TimingClock(6000, true); state.tapTiming(); playback.active = false; state.tapTiming()
    check(state.error.isNotEmpty() && state.timing.started == null && state.document == beforeError)
    playback.active = true
    state.selectEditorRow(0)
    check(state.timing.started == null && state.timing.word == 0 && playback.value.position == 0L)
    println("PASS: single-clock continuous line/word boundaries; explicit interlude/word gaps keep audio playing; blank/final rows, exact undo with offset, zero-length taps, pause, backward seek and song-switch guards.")
}
