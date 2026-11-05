package local.spw.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

enum class LyricsFormat(val label: String, val extension: String) {
    AUTO("跟随源歌词", "lrc"), LINE("逐行 LRC", "lrc"), WORD("逐字 LRC", "lrc"),
    ENHANCED("增强型逐字 LRC", "lrc"), TTML("TTML", "ttml")
}
enum class ChineseMode(val label: String) { ORIGINAL("保留原文"), SIMPLIFIED("转换为简体"), TRADITIONAL("转换为繁体") }
enum class LyricsPart(val label: String) { ORIGINAL("原文"), TRANSLATION("翻译"), ROMANIZATION("罗马音") }
data class LyricWord(val start: Long, val end: Long, val text: String)
data class LyricRow(val start: Long, val end: Long, val words: List<LyricWord>,
    val translation: String = "", val romanization: String = "", val background: Boolean = false, val agent: String = "") {
    val text: String get() = words.joinToString("") { it.text }
    val hasWords: Boolean get() = words.filter { it.text.isNotBlank() }.map { it.start to it.end }.distinct().size > 1
}
data class LyricsDocument(val rows: List<LyricRow>, val kind: String) {
    val hasWords: Boolean get() = rows.any { it.hasWords }
    val summary: String get() = "$kind · ${rows.size} 行 · ${if (hasWords) "含逐字时间轴" else "逐行时间轴"}"
}

object LyricsCodec {
    private const val MAX_TIME = 604_800_000L
    private val timestamp = Regex("[\\[<](\\d{1,5}:\\d{2}(?:[:.]\\d{1,3})?)[\\]>]")
    private val yrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val yrcWord = Regex("\\((\\d+),(\\d+),\\d+\\)([^()]*)")
    private val krcWord = Regex("<(\\d+),(\\d+),\\d+>([^<]*)")

    fun parse(raw: String): LyricsDocument {
        LyricsStore.validate(SavedLyrics(raw))
        val text = raw.removePrefix("\uFEFF").trim()
        val document = when {
            Regex("(?m)^version:\\s*['\"]?1\\.0").containsMatchIn(text) -> Lyricsfile.parse(text)
            text.contains("<QrcInfos") -> parseQrc(text)
            text.startsWith("<") -> parseTtml(text)
            text.lineSequence().any { yrcLine.matches(it.trim()) } -> if (krcWord.containsMatchIn(text)) parseKrc(text) else parseYrc(text)
            else -> parseLrc(text)
        }
        require(document.rows.isNotEmpty()) { "没有识别到时间轴，支持 LRC、逐字 LRC、增强型 LRC、YRC 和 TTML" }
        validate(document)
        return document
    }

    fun validate(document: LyricsDocument) {
        require(document.rows.size <= 20_000 && document.rows.sumOf { it.words.size } <= 200_000) { "歌词行数或字数过多" }
        document.rows.forEach { row ->
            require(row.start in -MAX_TIME..MAX_TIME && row.end in row.start..MAX_TIME) { "歌词行时间范围无效" }
            row.words.forEach { require(it.start in -MAX_TIME..MAX_TIME && it.end in it.start..MAX_TIME) { "逐字时间范围无效" } }
        }
    }

    fun time(value: Long): String {
        val ms = value.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
    }

    fun milliseconds(value: String): Long {
        val raw = value.trim()
        val units = Regex("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)$").matchEntire(raw)
        val result = if (units != null) {
            val multiplier = when (units.groupValues[2]) { "ms" -> 1; "s" -> 1000; "m" -> 60_000; else -> 3_600_000 }
            java.math.BigDecimal(units.groupValues[1]).multiply(java.math.BigDecimal(multiplier)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
        } else {
            val parts = raw.split(':')
            require(parts.size in 2..3) { "时间格式应为 mm:ss.mmm 或 hh:mm:ss.mmm" }
            val seconds = parts.last().toDouble()
            require(seconds.isFinite() && seconds in 0.0..<60.0) { "秒数应小于 60" }
            if (parts.size == 3) require(parts[1].toInt() in 0..59) { "小时制时间的分钟数应小于 60" }
            java.math.BigDecimal(parts.last()).multiply(java.math.BigDecimal(1000)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() +
                parts.dropLast(1).fold(0L) { total, p -> Math.addExact(Math.multiplyExact(total, 60), p.toLong()) } * 60_000
        }
        require(result in 0..MAX_TIME) { "时间超出支持范围" }
        return result
    }

    private fun parseLrc(raw: String): LyricsDocument {
        fun lrcTime(value: String): Long {
            val parts = value.split(':')
            return milliseconds(if (parts.size == 3) parts[0] + ":" + parts[1] + "." + parts[2] else value)
        }
        val entries = mutableListOf<Triple<Long, List<LyricWord>, String>>()
        val offset = Regex("(?im)^\\[offset:([+-]?\\d+)]\\s*$").find(raw)?.groupValues?.get(1)?.toLong() ?: 0
        require(offset in -60_000..60_000) { "源歌词 offset 超出前后 60 秒" }
        raw.lineSequence().forEach { line ->
            val matches = timestamp.findAll(line).toList()
            if (matches.isEmpty() || matches[0].range.first != 0) return@forEach
            val start = lrcTime(matches[0].groupValues[1]) + offset
            val firstEnd = matches[0].range.last + 1
            var prefixEnd = firstEnd
            val repeats = mutableListOf(start)
            var n = 1
            while (n < matches.size && matches[n].range.first == prefixEnd && line[matches[n].range.first] == '[') {
                repeats += lrcTime(matches[n].groupValues[1]) + offset
                prefixEnd = matches[n].range.last + 1; n++
            }
            val wordMarks = matches.drop(n)
            if (wordMarks.isEmpty()) {
                val content = line.substring(prefixEnd)
                repeats.forEach { entries += Triple(it, listOf(LyricWord(it, it, content)), "") }
            } else {
                var cursor = firstEnd
                var wordStart = start
                val words = mutableListOf<LyricWord>()
                matches.drop(1).forEach { mark ->
                    val end = lrcTime(mark.groupValues[1]) + offset
                    val content = line.substring(cursor, mark.range.first)
                    if (content.isNotEmpty()) words += LyricWord(wordStart, end.coerceAtLeast(wordStart), content)
                    wordStart = end; cursor = mark.range.last + 1
                }
                val tail = line.substring(cursor)
                if (tail.isNotEmpty()) words += LyricWord(wordStart, wordStart, tail)
                if (words.isNotEmpty()) entries += Triple(start, words, "")
            }
        }
        val ordered = entries.sortedWith(Comparator { a, b -> a.first.compareTo(b.first) })
        val groups = ordered.groupBy { it.first }.toList()
        val rows = groups.mapIndexed { index, (start, lines) ->
            val end = (groups.getOrNull(index + 1)?.first ?: (start + 3000)).coerceAtLeast(start)
            val main = lines.firstOrNull { it.second.any { word -> word.text.isNotBlank() } } ?: lines.first()
            val words = main.second.mapIndexed { wi, word ->
                if (word.end == word.start) word.copy(end = main.second.getOrNull(wi + 1)?.start ?: end) else word
            }
            val aux = lines.filter { it !== main }.map { it.second.joinToString("") { word -> word.text } }.filter { it.isNotBlank() }
            LyricRow(start, maxOf(end, words.maxOfOrNull { it.end } ?: end), words, aux.firstOrNull().orEmpty(), aux.drop(1).joinToString("\n"))
        }
        return LyricsDocument(rows, if (rows.any { it.hasWords }) "逐字 LRC" else "逐行 LRC")
    }

    private fun parseYrc(text: String): LyricsDocument = LyricsDocument(text.lineSequence().mapNotNull { line ->
        val match = yrcLine.matchEntire(line.trim()) ?: return@mapNotNull null
        val start = match.groupValues[1].toLong()
        val end = Math.addExact(start, match.groupValues[2].toLong())
        val words = yrcWord.findAll(match.groupValues[3]).map { word ->
            val ws = word.groupValues[1].toLong()
            LyricWord(ws, Math.addExact(ws, word.groupValues[2].toLong()), word.groupValues[3])
        }.toList()
        LyricRow(start, end, words)
    }.toList(), "YRC 逐字")

    private fun parseKrc(text: String): LyricsDocument {
        val rows = text.lineSequence().mapNotNull { line ->
            val match = yrcLine.matchEntire(line.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLong()
            val end = Math.addExact(start, match.groupValues[2].toLong())
            val words = krcWord.findAll(match.groupValues[3]).map {
                val begin = Math.addExact(start, it.groupValues[1].toLong())
                LyricWord(begin, Math.addExact(begin, it.groupValues[2].toLong()), it.groupValues[3])
            }.toList()
            LyricRow(start, end, words)
        }.toMutableList()
        val language = Regex("(?m)^\\[language:([^]]+)]").find(text)?.groupValues?.get(1)
        if (language != null) {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(String(java.util.Base64.getDecoder().decode(language), Charsets.UTF_8))
            for (track in (json as? kotlinx.serialization.json.JsonObject)?.get("content").array()) {
                val objectTrack = track as? kotlinx.serialization.json.JsonObject ?: continue
                val type = objectTrack.string("type")
                val content = objectTrack["lyricContent"].array()
                if (content.size != rows.size) continue
                rows.indices.forEach { i ->
                    val value = content[i].array().joinToString(if (type == "0") " " else "") { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                    rows[i] = when (type) { "0" -> rows[i].copy(romanization = value); "1" -> rows[i].copy(translation = value); else -> rows[i] }
                }
            }
        }
        return LyricsDocument(rows, "KRC 逐字")
    }

    private fun parseXml(raw: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
    }

    private fun parseQrc(raw: String): LyricsDocument {
        // QQ sometimes leaves quotes and '&' unescaped inside LyricContent; treat it as lyric data, never XML entities.
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(raw)) { "QRC 不允许外部实体或文档声明" }
        require(Regex("<QrcInfos(?:\\s|>)").containsMatchIn(raw) && raw.contains("</QrcInfos>")) { "不是有效的 QRC 文档" }
        val attribute = Regex("<Lyric_1\\b[^>]*?LyricContent=\"(.*?)\"\\s*/>", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)
            ?: error("QQ 歌词缺少内容")
        val content = LyricSources.htmlDecode(attribute)
        if (!content.lineSequence().any { yrcLine.matches(it.trim()) }) return parseLrc(content)
        val offset = Regex("(?im)^\\[offset:([+-]?\\d+)]\\s*$").find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        require(offset in -60_000..60_000) { "QQ 歌词 offset 超出前后 60 秒" }
        val wordPattern = Regex("(.*?)\\((\\d+),(\\d+)\\)")
        val rows = content.lineSequence().mapNotNull { line ->
            val match = yrcLine.matchEntire(line.trim()) ?: return@mapNotNull null
            val start = Math.addExact(match.groupValues[1].toLong(), offset)
            val end = Math.addExact(start, match.groupValues[2].toLong())
            val words = wordPattern.findAll(match.groupValues[3]).map { word ->
                val begin = Math.addExact(word.groupValues[2].toLong(), offset)
                LyricWord(begin, Math.addExact(begin, word.groupValues[3].toLong()), word.groupValues[1])
            }.toList().ifEmpty { listOf(LyricWord(start, end, match.groupValues[3])) }
            LyricRow(start, end, words)
        }.sortedBy { it.start }.toList()
        return LyricsDocument(rows, "QRC 逐字")
    }

    private fun parseTtml(raw: String): LyricsDocument {
        val document = parseXml(raw)
        require(document.documentElement.localName == "tt") { "不是有效的 TTML 文档" }
        val rows = mutableListOf<LyricRow>()
        val nodes = document.getElementsByTagNameNS("*", "p")
        for (index in 0 until nodes.length) {
            val p = nodes.item(index) as Element
            val start = milliseconds(p.getAttribute("begin"))
            val end = p.getAttribute("end").takeIf { it.isNotBlank() }?.let(::milliseconds)
                ?: Math.addExact(start, milliseconds(p.getAttribute("dur")))
            val key = p.attributes.let { attrs -> (0 until attrs.length).map { attrs.item(it) }.firstOrNull { it.localName == "key" }?.nodeValue.orEmpty() }
            val main = ttmlRow(p, start, end, role(p) == "x-bg")
            fun headAux(type: String): String {
                if (key.isEmpty()) return ""
                val tracks = document.getElementsByTagNameNS("*", type)
                for (ti in 0 until tracks.length) {
                    val items = (tracks.item(ti) as Element).getElementsByTagNameNS("*", "text")
                    for (j in 0 until items.length) if ((items.item(j) as Element).getAttribute("for") == key) return items.item(j).textContent.trim()
                }
                return ""
            }
            rows += main.copy(translation = main.translation.ifEmpty { headAux("translation") }, romanization = main.romanization.ifEmpty { headAux("transliteration") })
            val descendants = p.getElementsByTagNameNS("*", "span")
            for (j in 0 until descendants.length) {
                val span = descendants.item(j) as Element
                if (role(span) == "x-bg") rows += ttmlRow(span,
                    span.getAttribute("begin").takeIf { it.isNotBlank() }?.let(::milliseconds) ?: start,
                    span.getAttribute("end").takeIf { it.isNotBlank() }?.let(::milliseconds) ?: end, true)
            }
        }
        return LyricsDocument(rows.sortedWith(Comparator { a, b -> a.start.compareTo(b.start) }), "TTML")
    }

    private fun role(element: Element): String = element.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
        .ifEmpty { element.getAttribute("ttm:role") }

    private fun ttmlRow(element: Element, start: Long, end: Long, background: Boolean): LyricRow {
        val words = mutableListOf<LyricWord>()
        val translations = mutableListOf<String>()
        val romans = mutableListOf<String>()
        fun visit(node: Node, inheritedStart: Long, inheritedEnd: Long) {
            if (node is Element) {
                when (role(node)) {
                    "x-bg" -> if (node !== element) return
                    "x-translation" -> { translations += node.textContent.trim(); return }
                    "x-roman" -> { romans += node.textContent.trim(); return }
                }
                val begin = node.getAttribute("begin").takeIf { it.isNotBlank() }?.let(::milliseconds) ?: inheritedStart
                val finish = node.getAttribute("end").takeIf { it.isNotBlank() }?.let(::milliseconds) ?: inheritedEnd
                var child = node.firstChild
                while (child != null) { visit(child, begin, finish); child = child.nextSibling }
            } else if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val value = node.nodeValue
                // Pretty-print indentation is not a lyric word; ordinary spaces between words are retained.
                if (value.isNotEmpty() && !(value.isBlank() && (value.contains('\n') || value.contains('\r')))) {
                    if (value.isBlank() && words.isNotEmpty()) words[words.lastIndex] = words.last().copy(text = words.last().text + value)
                    else words += LyricWord(inheritedStart, inheritedEnd, value)
                }
            }
        }
        visit(element, start, end)
        return LyricRow(start, end, words, translations.joinToString("\n"), romans.joinToString("\n"), background,
            element.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent"))
    }

    fun withAuxiliary(document: LyricsDocument, translation: String?, romanization: String?): LyricsDocument {
        fun auxiliary(raw: String?): List<LyricRow>? = raw?.let {
            if (it.isBlank()) emptyList()
            else if (it.trimStart().startsWith('<') || timestamp.containsMatchIn(it)) parse(it).rows
            else {
                val lines = it.lines()
                require(lines.size == document.rows.size) { "纯文本辅助轨的行数必须与原文一致，也可以使用带时间戳的 LRC" }
                lines.mapIndexed { index, text -> val row = document.rows[index]; row.copy(words = listOf(LyricWord(row.start, row.end, text))) }
            }
        }
        val translated = auxiliary(translation)
        val roman = auxiliary(romanization)
        fun at(rows: List<LyricRow>, target: LyricRow, index: Int): String =
            rows.minByOrNull { abs(it.start - target.start) }?.takeIf { abs(it.start - target.start) <= 600 }?.text
                ?: if (rows.size == document.rows.size) rows.getOrNull(index)?.text.orEmpty() else ""
        return document.copy(rows = document.rows.mapIndexed { index, row -> row.copy(
            translation = translated?.let { at(it, row, index) } ?: row.translation,
            romanization = roman?.let { at(it, row, index) } ?: row.romanization) })
    }

    fun source(saved: SavedLyrics, fallback: String): LyricsDocument = withAuxiliary(parse(saved.text ?: fallback), saved.translation, saved.romanization)

    fun render(document: LyricsDocument, saved: SavedLyrics): LyricsDocument {
        return document.copy(rows = document.rows.filter { !saved.removeEmptyLines || it.text.isNotBlank() }.map { row ->
            val text = ChineseConverter.convert(row.text, saved.chinese)
            var position = 0
            val words = if (saved.format == LyricsFormat.LINE) listOf(LyricWord(row.start, row.end, text)) else row.words.mapIndexed { index, word ->
                val next = if (index == row.words.lastIndex) text.length else (position + word.text.length).coerceAtMost(text.length)
                val part = text.substring(position, next); position = next
                word.copy(text = part)
            }
            row.copy(start = row.start + saved.offset, end = row.end + saved.offset,
                words = words.map { it.copy(start = it.start + saved.offset, end = it.end + saved.offset) },
                translation = if (saved.showTranslation) ChineseConverter.convert(row.translation, saved.chinese) else "",
                romanization = if (saved.showRomanization) row.romanization else "")
        })
    }

    fun parts(row: LyricRow, order: List<LyricsPart>): List<Pair<LyricsPart, String>> = order.map { part -> part to when (part) {
        LyricsPart.ORIGINAL -> row.text
        LyricsPart.TRANSLATION -> row.translation
        LyricsPart.ROMANIZATION -> row.romanization
    } }.filter { it.second.isNotBlank() }

    fun export(document: LyricsDocument, format: LyricsFormat, order: List<LyricsPart> = LyricsPart.entries): String {
        validate(document)
        if (format == LyricsFormat.TTML) return ttml(document)
        val actual = if (format == LyricsFormat.AUTO) if (document.hasWords) LyricsFormat.ENHANCED else LyricsFormat.LINE else format
        require(actual == LyricsFormat.LINE || document.hasWords) { "当前歌词没有逐字时间轴，请选择带逐字数据的歌词来源" }
        return document.rows.joinToString("\n") { row ->
            val selected = parts(row, order)
            if (selected.isEmpty()) "[${time(row.start)}]" else selected.joinToString("\n") { (part, content) ->
                if (part != LyricsPart.ORIGINAL || actual == LyricsFormat.LINE) "[${time(row.start)}]$content"
                else buildString {
                    append("[${time(row.start)}]")
                    row.words.forEach { word ->
                        val open = if (actual == LyricsFormat.WORD) '[' else '<'
                        val close = if (actual == LyricsFormat.WORD) ']' else '>'
                        append(open).append(time(word.start)).append(close).append(word.text)
                        append(open).append(time(word.end)).append(close)
                    }
                }
            }
        }
    }

    fun ttml(document: LyricsDocument): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">")
        val agents = document.rows.map { it.agent }.filter { it.isNotBlank() }.distinct()
        if (agents.isNotEmpty()) {
            append("<head><metadata>")
            agents.forEach { append("<ttm:agent xml:id=\"").append(xml(it)).append("\" type=\"person\"/>") }
            append("</metadata></head>")
        }
        append("<body><div>\n")
        val backgrounds = document.rows.filter { it.background }.toMutableList()
        document.rows.filter { !it.background }.forEach { row ->
            append("<p begin=\"").append(time(row.start)).append("\" end=\"").append(time(row.end)).append("\"")
            if (row.agent.isNotBlank()) append(" ttm:agent=\"").append(xml(row.agent)).append("\"")
            append('>'); writeTtmlRow(row)
            val matching = backgrounds.filter { it.start >= row.start && it.start < row.end }
            matching.forEach { bg ->
                append("<span ttm:role=\"x-bg\" begin=\"").append(time(bg.start)).append("\" end=\"").append(time(bg.end)).append("\">")
                writeTtmlRow(bg); append("</span>")
            }
            backgrounds.removeAll(matching.toSet())
            append("</p>\n")
        }
        backgrounds.forEach { bg ->
            append("<p ttm:role=\"x-bg\" begin=\"").append(time(bg.start)).append("\" end=\"").append(time(bg.end)).append("\">")
            writeTtmlRow(bg); append("</p>\n")
        }
        append("</div></body></tt>")
    }

    private fun StringBuilder.writeTtmlRow(row: LyricRow) {
        row.words.forEach { word -> append("<span begin=\"").append(time(word.start)).append("\" end=\"").append(time(word.end)).append("\">").append(xml(word.text)).append("</span>") }
        if (row.translation.isNotBlank()) append("<span ttm:role=\"x-translation\">").append(xml(row.translation)).append("</span>")
        if (row.romanization.isNotBlank()) append("<span ttm:role=\"x-roman\">").append(xml(row.romanization)).append("</span>")
    }
    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
