package local.spw.lyrics

enum class TimingMode(val label: String) { LINE("逐行打轴"), WORD("逐字打轴") }
data class TimingMark(val text: String, val start: Long, val end: Long)
data class TimingEdit(val document: LyricsDocument, val line: Int, val word: Int, val offset: Long, val original: SavedLyrics? = null, val pending: Boolean = false, val previousMark: TimingMark? = null, val previousStart: Long? = null)

/** One completed press is one edit; incomplete presses never leak into exports. */
class LyricsTiming {
    var mode = TimingMode.LINE
    var word = 0
    var started: Long? = null
        private set
    var lastRecorded: TimingMark? = null
        private set
    private var continuousStart = false
    private val history = java.util.ArrayDeque<TimingEdit>()
    val canUndo get() = history.isNotEmpty()
    fun cancel() { started = null; continuousStart = false }
    fun begin(position: Long, continuous: Boolean = false) { check(started == null); require(position >= 0); started = position; continuousStart = continuous }
    fun finish(document: LyricsDocument, line: Int, position: Long, offset: Long = 0, original: SavedLyrics? = null, pending: Boolean = false, continuous: Boolean = false): Pair<LyricsDocument, Int> {
        val start = checkNotNull(started) { "请先记录开始时间" }
        require(position > start) { "结束时间须晚于开始时间，请先播放歌曲" }
        val source = if (offset == 0L) document else document.copy(rows = document.rows.map { row ->
            row.copy(start = row.start + offset, end = row.end + offset, words = row.words.map { it.copy(start = it.start + offset, end = it.end + offset) })
        })
        val row = source.rows[line]
        val tokens = if (mode == TimingMode.WORD) timingTokens(row) else emptyList()
        val index = word.coerceIn(0, tokens.lastIndex.coerceAtLeast(0))
        val changed = if (mode == TimingMode.LINE) {
            val words = if (row.hasWords && row.end > row.start) row.words.map {
                fun scale(time: Long) = start + ((time - row.start).toDouble() * (position - start) / (row.end - row.start)).toLong()
                it.copy(start = scale(it.start).coerceIn(start, position), end = scale(it.end).coerceIn(start, position))
            } else listOf(LyricWord(start, position, row.text))
            row.copy(start = start, end = position, words = words)
        } else {
            require(tokens.isNotEmpty()) { "空行无法逐字打轴" }
            val words = tokens.mapIndexed { i, text ->
                val old = row.words.getOrNull(i)?.takeIf { row.words.size == tokens.size }
                when {
                    i == index -> LyricWord(start, position, text)
                    i < index -> (old ?: LyricWord(start, start, text)).also { require(it.end <= start) { "当前字早于上一字，请重新定位" } }
                    else -> (old ?: LyricWord(position, position, text)).let { it.copy(start = maxOf(position, it.start), end = maxOf(position, it.end)) }
                }
            }
            row.copy(start = words.first().start, end = words.last().end, words = words)
        }
        val next = source.copy(rows = source.rows.toMutableList().also { it[line] = changed })
        LyricsCodec.validate(next)
        history.addLast(TimingEdit(document, line, word, offset, original, pending, lastRecorded, if (continuous || continuousStart) start else null))
        lastRecorded = TimingMark(if (mode == TimingMode.WORD) tokens[index] else row.text, start, position)
        while (history.size > 1 && (history.size > 60 || history.sumOf { it.original?.text?.length ?: 0 } > 4_000_000)) history.removeFirst()
        val finished = mode == TimingMode.LINE || index == tokens.lastIndex
        val nextLine = if (finished) document.rows.indices.firstOrNull { it > line && document.rows[it].text.isNotBlank() } ?: line else line
        started = if (continuous && (!finished || nextLine != line)) position else null
        continuousStart = started != null
        word = if (finished) 0 else index + 1
        return next to nextLine
    }
    fun undo(): TimingEdit? { cancel(); return history.pollLast()?.also { word = it.word; lastRecorded = it.previousMark; started = it.previousStart; continuousStart = started != null } }
    fun regroup(document: LyricsDocument, line: Int, groups: List<String>, offset: Long, original: SavedLyrics, pending: Boolean): LyricsDocument {
        val changed = regroupTimingRow(document.rows[line], groups)
        if (changed == document.rows[line]) return document
        history.addLast(TimingEdit(document, line, word, offset, original, pending, lastRecorded))
        while (history.size > 1 && (history.size > 60 || history.sumOf { it.original?.text?.length ?: 0 } > 4_000_000)) history.removeFirst()
        cancel(); word = word.coerceAtMost(groups.lastIndex)
        return document.copy(rows = document.rows.toMutableList().also { it[line] = changed }).also(LyricsCodec::validate)
    }
    fun clear() { cancel(); word = 0; lastRecorded = null; history.clear() }
}

fun regroupTimingRow(row: LyricRow, groups: List<String>): LyricRow {
    require(groups.isNotEmpty() && groups.all(String::isNotEmpty) && groups.joinToString("") == row.text) { "分组须保留全部歌词" }
    val starts = mutableMapOf<Int, Long>()
    val ends = mutableMapOf<Int, Long>()
    var position = 0
    row.words.forEach { word ->
        val start = position
        word.text.indices.forEach { index ->
            starts[start + index] = word.start + (word.end - word.start) * index / word.text.length
            ends[start + index + 1] = word.start + (word.end - word.start) * (index + 1) / word.text.length
        }
        position += word.text.length
    }
    position = 0
    return row.copy(words = groups.map { text ->
        val start = checkNotNull(starts[position]); position += text.length
        LyricWord(start, checkNotNull(ends[position]), text)
    })
}

fun timingTokens(row: LyricRow): List<String> {
    if (row.words.size > 1) return row.words.map { it.text }
    val result = mutableListOf<String>()
    val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    val text = row.text; iterator.setText(text)
    var begin = iterator.first(); var end = iterator.next()
    var latin = false
    while (end != java.text.BreakIterator.DONE) {
        val token = text.substring(begin, end)
        val code = token.codePointAt(0)
        val currentLatin = Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN || Character.isDigit(code)
        val attach = token.isBlank() || (!Character.isLetterOrDigit(code) && result.isNotEmpty())
        if (result.isNotEmpty() && (attach || currentLatin && latin && !result.last().last().isWhitespace())) result[result.lastIndex] += token
        else result += token
        latin = currentLatin
        begin = end; end = iterator.next()
    }
    return result.filter(String::isNotEmpty)
}

fun plainTimingDocument(text: String): LyricsDocument {
    LyricsStore.validate(SavedLyrics(text))
    require(!Regex("[<]|\\d{1,5}:\\d{2}").containsMatchIn(text)) { "请使用纯文本歌词" }
    val rows = text.lineSequence().filter(String::isNotBlank).map { LyricRow(0, 0, listOf(LyricWord(0, 0, it))) }.toList()
    require(rows.isNotEmpty()) { "请先输入歌词" }
    return LyricsDocument(rows, "待打轴文本").also(LyricsCodec::validate)
}
