package local.spw.lyrics

/** OpenCC dictionaries, longest phrase first; no network service or native library is needed. */
object ChineseConverter {
    private data class Dictionary(val values: Map<String, String>, val longest: Int)
    private val simplified by lazy { dictionary("TS") }
    private val traditional by lazy { dictionary("ST") }

    private fun dictionary(prefix: String): Dictionary {
        val values = HashMap<String, String>()
        for (kind in listOf("Characters", "Phrases")) {
            val stream = checkNotNull(javaClass.getResourceAsStream("/opencc/$prefix$kind.txt")) { "缺少繁简转换词典" }
            try {
                stream.bufferedReader(Charsets.UTF_8).lineSequence().forEach { line ->
                    if (line.isNotBlank() && !line.startsWith('#')) {
                        val pair = line.split('\t', limit = 2)
                        if (pair.size == 2) values[pair[0]] = pair[1].substringBefore(' ')
                    }
                }
            } finally { stream.close() }
        }
        check(values.size > 1000) { "繁简转换词典不完整" }
        return Dictionary(values, values.keys.maxOf { it.length })
    }

    fun convert(text: String, mode: ChineseMode): String {
        if (mode == ChineseMode.ORIGINAL || text.isEmpty()) return text
        val dictionary = if (mode == ChineseMode.SIMPLIFIED) simplified else traditional
        return buildString {
            var index = 0
            while (index < text.length) {
                var count = minOf(dictionary.longest, text.length - index)
                var replacement: String? = null
                while (count > 0) {
                    replacement = dictionary.values[text.substring(index, index + count)]
                    if (replacement != null) break
                    count--
                }
                if (replacement == null) { append(text[index]); index++ }
                else { append(replacement); index += count }
            }
        }
    }
}
