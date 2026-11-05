package local.spw.lyrics

/** LRCLIB's documented YAML format; safe loading never instantiates application classes. */
object Lyricsfile {
    fun parse(text: String): LyricsDocument {
        require(text.toByteArray().size <= LyricsStore.MAX_LYRICS_BYTES) { "Lyricsfile 过大" }
        val options = org.yaml.snakeyaml.LoaderOptions().apply {
            isAllowDuplicateKeys = false; maxAliasesForCollections = 0; nestingDepthLimit = 12; codePointLimit = LyricsStore.MAX_LYRICS_BYTES
        }
        val yaml = org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(options))
        require(yaml.parse(text.reader()).none { it is org.yaml.snakeyaml.events.AliasEvent }) { "Lyricsfile 不允许别名" }
        val root = yaml.load<Any>(text) as? Map<*, *> ?: error("Lyricsfile 结构无效")
        require(root["version"] == "1.0") { "Lyricsfile 版本暂不支持" }
        require(root["metadata"] is Map<*, *>) { "Lyricsfile 缺少元数据" }
        val lines = root["lines"] as? List<*> ?: error("此 Lyricsfile 没有同步时间轴")
        require(lines.size in 1..20000) { "Lyricsfile 行数无效" }
        fun time(value: Any?): Long {
            require(value is Int || value is Long) { "Lyricsfile 时间须为整数毫秒" }
            return (value as Number).toLong().also { require(it in 0..604800000) { "Lyricsfile 时间超出范围" } }
        }
        val ordered = lines.map { it as? Map<*, *> ?: error("Lyricsfile 行结构无效") }.sortedBy { time(it["start_ms"]) }
        val rows = ordered.mapIndexed { index, line ->
            val start = time(line["start_ms"])
            val main = line["text"] as? String ?: error("Lyricsfile 缺少歌词文字")
            val words = (line["words"] as? List<*>).orEmpty()
            require(words.size <= 10000) { "Lyricsfile 字词过多" }
            val wordMaps = words.map { it as? Map<*, *> ?: error("Lyricsfile 字词结构无效") }.sortedBy { time(it["start_ms"]) }
            val boundary = line["end_ms"]?.let(::time) ?: maxOf(start, ordered.getOrNull(index + 1)?.get("start_ms")?.let(::time) ?: start,
                wordMaps.maxOfOrNull { time(it["end_ms"] ?: it["start_ms"]) } ?: start)
            val mapped = wordMaps.mapIndexed { wi, word ->
                val begin = time(word["start_ms"])
                // As with LRC, missing ends run to the next known start; raw YAML remains the saved source.
                LyricWord(begin, word["end_ms"]?.let(::time) ?: wordMaps.getOrNull(wi + 1)?.get("start_ms")?.let(::time) ?: boundary, word["text"] as? String ?: error("Lyricsfile 字词缺少文字"))
            }
            require(mapped.isEmpty() || mapped.joinToString("") { it.text } == main) { "Lyricsfile 字词与原文不一致" }
            val end = boundary
            LyricRow(start, end, mapped.ifEmpty { listOf(LyricWord(start, end, main)) })
        }
        return LyricsDocument(rows.sortedBy { it.start }, "Lyricsfile").also(LyricsCodec::validate)
    }
}
