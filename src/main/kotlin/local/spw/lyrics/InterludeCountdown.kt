package local.spw.lyrics

data class CountdownPresentation(val document: LyricsDocument, val cues: List<LyricRow>)
/** Presentation only: reuse SPW's playback-driven word highlighting, without changing saved lyrics. */
object InterludeCountdown {
    private val credits = Regex("^(?:(?:作词|作詞|作曲|词曲|詞曲|词|詞|曲|编曲|編曲|制作人|製作人|制作|製作|录音|錄音|混音|母带|母帶|监制|監製|发行|發行|原唱|演唱|歌手|歌名|歌曲|专辑|專輯|出品|统筹|統籌|策划|策劃|和声|和聲|录音师|錄音師|音乐总监|音樂總監|Lyrics?|Words|Music|Composer|Arranger|Producer|Artist|Title|Album)\\s*[:：]\\s*\\S.*|(?:Lyrics?|Words|Music|Composed|Arranged|Produced|Written)\\s+by\\s+\\S.*)$", RegexOption.IGNORE_CASE)
    private val existingCue = Regex("[.·•●○…\\s]+")
    private val speaker = Regex("^(?:男|女|合|男声|女声|合唱|男聲|女聲|Male|Female|All)\\s*[:：]$", RegexOption.IGNORE_CASE)
    private fun heading(row: LyricRow, target: SearchTarget?): Boolean {
        val text = row.text.trim()
        if (credits.matches(text) || speaker.matches(text)) return true
        if (target == null || target.title.isBlank() || target.artist.isBlank() || row.start > 5000) return false
        return Regex("[-–—－]").findAll(text).any { separator ->
            SearchRanking.key(text.substring(0, separator.range.first)) == SearchRanking.key(target.title) &&
                SearchRanking.artists(text.substring(separator.range.last + 1)) == SearchRanking.artists(target.artist)
        }
    }
    fun render(document: LyricsDocument, saved: SavedLyrics, target: SearchTarget? = null, settings: CountdownSettings = CountdownSettings()) =
        presentation(document, saved, target, settings).document
    fun presentation(document: LyricsDocument, saved: SavedLyrics, target: SearchTarget? = null, settings: CountdownSettings = CountdownSettings()): CountdownPresentation {
        // Source-authored dots are replaced only in the display model so settings apply to them too.
        val ordered = document.rows.filterNot { it.text.isNotBlank() && existingCue.matches(it.text) }.sortedBy { it.start }
        val firstIndex = ordered.indexOfFirst { it.text.isNotBlank() && !heading(it, target) }
        if (firstIndex < 0) return CountdownPresentation(LyricsCodec.render(document, saved), emptyList())
        val first = ordered[firstIndex]
        val firstWord = first.words.filter { it.text.isNotBlank() }.minOfOrNull { it.start } ?: first.start
        val firstStart = firstWord.takeIf { it in first.start..first.end } ?: first.start
        val intro = firstStart >= 1000 && !existingCue.matches(first.text)
        // Late credit/blank rows must not take focus away from the opening countdown.
        val display = ordered.mapIndexedNotNull { index, row ->
            when {
                intro && index < firstIndex -> null
                intro && index == firstIndex -> row.copy(start = firstStart)
                else -> row
            }
        }
        var vocalEnd = 0L
        val dots = mutableListOf<LyricRow>()
        ordered.drop(firstIndex).forEachIndexed { index, row ->
            if (row.text.isNotBlank() && !heading(row, target)) {
                val end = if (index == 0) firstStart else row.start
                if (if (index == 0) intro else end - vocalEnd >= settings.gapMillis) {
                    val span = minOf(settings.leadMillis, if (index == 0) firstStart else end - vocalEnd)
                    val count = minOf(3, ((span + 999) / 1000).toInt())
                    val start = (if (index == 0) 0 else vocalEnd) + saved.offset
                    val countdownStart = end - span + saved.offset
                    dots += LyricRow(start, end + saved.offset,
                        List(count) { i -> LyricWord(countdownStart + span * i / count, countdownStart + span * (i + 1) / count, if (i < count - 1) "● " else "●") })
                }
                vocalEnd = maxOf(vocalEnd, row.words.maxOfOrNull { it.end } ?: row.end)
            }
        }
        val visible = display.filterNot { row ->
            (row.text.isBlank() || heading(row, target)) && dots.any {
                row.start + saved.offset < it.end && maxOf(row.end, row.start + 1) + saved.offset > it.start
            }
        }
        val rendered = LyricsCodec.render(document.copy(rows = visible), saved)
        return CountdownPresentation(rendered.copy(rows = (rendered.rows + dots).sortedBy { it.start }), dots)
    }
}
