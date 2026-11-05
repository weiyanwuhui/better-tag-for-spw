package local.spw.lyrics

fun checkInterludeCountdown() {
    fun line(start: Long, end: Long) = LyricRow(start, end, listOf(LyricWord(start, start + 500, "你"), LyricWord(start + 500, end, "好")))
    fun dots(document: LyricsDocument) = document.rows.filter { it.text == "● ● ●" }
    val source = LyricsDocument(listOf(line(5000, 7000), line(16000, 18000), line(19000, 21000)), "TTML")
    val original = LyricsCodec.ttml(source)
    for (format in listOf(LyricsFormat.WORD, LyricsFormat.LINE)) {
        val saved = SavedLyrics(format = format, offset = -250)
        val rendered = InterludeCountdown.render(source, saved)
        val rows = dots(rendered)
        check(rows.size == 2 && rows.map { it.start } == listOf(-250L, 6750L))
        check(rows[1].words.map { it.start } == listOf(12750L, 13750L, 14750L) && rows[1].end == 15750L)
        check(rows.all { it.translation.isEmpty() && it.romanization.isEmpty() && it.words.size == 3 })
        check(rendered == InterludeCountdown.render(source, saved)) { "Countdown depends on a wall-clock timer" }
    }
    val backing = source.copy(rows = source.rows + line(6800, 15500).copy(background = true))
    check(dots(InterludeCountdown.render(backing, SavedLyrics())).size == 1) { "Countdown overlapped backing vocals" }
    val held = source.copy(rows = listOf(line(5000, 13500), line(12000, 14000)))
    check(dots(InterludeCountdown.render(held, SavedLyrics())).size == 1) { "Countdown interrupted a held note" }
    val plain = LyricsCodec.parse("[00:01.000]第一句\n[00:12.000]下一句")
    check(dots(InterludeCountdown.render(plain, SavedLyrics())).isEmpty()) { "Guessed a vocal ending from plain LRC" }
    val blank = LyricsCodec.parse("[00:01.000]第一句\n[00:04.000]\n[00:12.000]下一句")
    check(dots(InterludeCountdown.render(blank, SavedLyrics())).single().start == 4000L)
    val existing = source.copy(rows = source.rows + LyricRow(8000, 12000, listOf(LyricWord(8000, 12000, "● ● ●"))))
    check(dots(InterludeCountdown.render(existing, SavedLyrics())).size == 2) { "Inserted duplicate dots" }
    check(original == LyricsCodec.ttml(source)) { "Presentation changed source/export data" }
    val baseline = "[00:01.000]placeholder"
    val native = NativeHost.decode(baseline)
    NativeHost.applySettings(native, SavedLyrics(text = original, format = LyricsFormat.LINE), true)
    check(NativeHost.lines(native).count { CountdownRenderer.cue(it) != null } == 2 && NativeHost.raw(native) == baseline)
    check(NativeHost.model(native).rows.filter { it.text == "\u200B" }.size == 2)
    NativeHost.applySettings(native, SavedLyrics(text = original, format = LyricsFormat.LINE), false)
    check(dots(NativeHost.model(native)).isEmpty()) { "Disabling countdown did not restore original lines" }
    val target = SearchTarget("测试曲", "测试歌手", "", 30000)
    val headings = LyricsCodec.parse("[00:00.000]测试曲 - 测试歌手\n[00:01.000]作词：甲\n[00:03.000]作曲 : 乙\n[00:14.000]编曲：丙\n[00:15.000]\n[00:16.000]第一句\n[00:20.000]第二句")
    val before = LyricsCodec.ttml(headings)
    val intro = InterludeCountdown.render(headings, SavedLyrics(removeEmptyLines = false), target)
    check(dots(intro).single().start == 0L && dots(intro).single().end == 16000L) { "Credits blocked the opening countdown" }
    check(intro.rows.none { it.start in 0L..<16000L && it.text != "● ● ●" }) { "Late credit or blank row stole countdown focus" }
    check(LyricsCodec.ttml(headings) == before && LyricsCodec.render(headings, SavedLyrics()).rows.any { it.text == "编曲：丙" })
    val hyphenHeader = LyricsCodec.parse("[00:00.000]带-符号-A-B\n[00:08.000]第一句")
    check(dots(InterludeCountdown.render(hyphenHeader, SavedLyrics(), SearchTarget("带-符号", "A-B", "", 30000))).single().start == 0L)
    val short = LyricsDocument(listOf(line(1500, 3000)), "TTML")
    val shortCue = InterludeCountdown.render(short, SavedLyrics()).rows.first()
    check(shortCue.text == "● ●" && shortCue.start == 0L && shortCue.end == 1500L && shortCue.words.last().end == 1500L)
    check(InterludeCountdown.render(LyricsDocument(listOf(line(500, 2000)), "TTML"), SavedLyrics()).rows.size == 1) { "Forced a countdown into immediate singing" }
    val delayed = LyricsDocument(listOf(line(5000, 7000).copy(start = 0)), "TTML")
    val delayedRows = InterludeCountdown.render(delayed, SavedLyrics()).rows
    check(delayedRows.first().text == "● ● ●" && delayedRows.last().start == 5000L) { "Container timestamp hid the actual first-word lead-in" }
    val hasCue = LyricsCodec.parse("[00:02.000]● ● ●\n[00:05.000]第一句")
    check(dots(InterludeCountdown.render(hasCue, SavedLyrics())).size == 1) { "Duplicated the source's opening countdown" }
    val customSourceCue = InterludeCountdown.presentation(hasCue, SavedLyrics(), settings = CountdownSettings(5000))
    check(customSourceCue.cues.single().start == 0L && customSourceCue.cues.single().end == 5000L) { "Source dots bypassed countdown customization" }
    check(hasCue.rows.first().start == 2000L && hasCue.rows.first().text == "● ● ●")
    val allCredits = LyricsCodec.parse("[00:00.000]作词：甲\n[00:03.000]作曲：乙")
    check(dots(InterludeCountdown.render(allCredits, SavedLyrics())).isEmpty())
    val sung = LyricsCodec.parse("[00:00.000]作词的人也在歌唱\n[00:09.000]下一句")
    check(InterludeCountdown.render(sung, SavedLyrics()).rows.first().text == "作词的人也在歌唱") { "Deleted genuine sung text" }
    NativeHost.applySettings(native, SavedLyrics(text = before, removeEmptyLines = false), true, target)
    check(NativeHost.lines(native).mapNotNull(CountdownRenderer::cue).single().start == 0L) { "Native publication did not receive metadata-aware intro" }
    for ((file, firstTime, interludeEnd) in listOf(Triple("intro-reference.lrc", 11890L, 120920L), Triple("intro-qq.lrc", 12468L, 121342L))) {
        val raw = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/$file"))
        val parsed = LyricsCodec.parse(raw)
        val snapshot = LyricsCodec.ttml(parsed)
        val result = InterludeCountdown.presentation(parsed, SavedLyrics(removeEmptyLines = false), SearchTarget("因为爱情", "陈奕迅 / 王菲", "", 218000))
        check(result.cues.first().start == 0L && result.cues.first().end == firstTime) { "$file: metadata/speaker marker blocked intro: ${result.cues.map { it.start to it.end }}" }
        check(result.cues.first().words.first().start == firstTime - 3000) { "$file: final countdown span no longer honors lead setting" }
        check(result.cues.any { it.end == interludeEnd }) { "$file: speaker marker hid a real instrumental break" }
        check(result.cues.none { it.end in 33000..55000 }) { "$file: normal phrase gap received an interlude countdown" }
        check(result.document.rows.none { row -> row.text.trim() in setOf("男：", "女：", "合：") && result.cues.any { row.start < it.end && row.end > it.start } }) { "$file: a speaker row still overlaps countdown focus" }
        check(LyricsCodec.ttml(parsed) == snapshot && raw == java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/$file")))
    }
    val configured = InterludeCountdown.presentation(source, SavedLyrics(), settings = CountdownSettings(5000, 10000))
    check(configured.cues.single().start == 0L && configured.cues.single().end == 5000L)
    check(CountdownRenderer.alignment(CountdownAlignment.FOLLOW, 2) == 2 && CountdownRenderer.alignment(CountdownAlignment.CENTER, 1) == 0)
    println("PASS: both supplied lyric variants get an intro and real instrumental cue, without phrase-gap countdowns or source mutation; configurable span/threshold and seek boundaries.")
    println("PASS: persisted optional interlude dots use native word timing; intro, gap, blank LRC marker, overlapping/backing vocals, held notes, offsets, line mode and toggle; source/export remain unchanged.")
    println("PASS: known title/artist headers and credits do not block intros; late blank/credit rows cannot steal focus; short intros shrink; first word timestamps, source cue deduplication, real sung text and native publication.")
}
