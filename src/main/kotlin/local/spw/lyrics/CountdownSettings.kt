package local.spw.lyrics

enum class CountdownAlignment(val label: String) { FOLLOW("跟随歌词"), LEFT("靠左"), CENTER("居中"), RIGHT("靠右") }

data class CountdownSettings(val leadMillis: Long = 3000, val gapMillis: Long = 8000,
    val alignment: CountdownAlignment = CountdownAlignment.FOLLOW) {
    init {
        require(leadMillis in 1000..30_000) { "提前时间范围：1 至 30 秒" }
        require(gapMillis in 1000..120_000) { "最短间奏范围：1 至 120 秒" }
    }
}

fun countdownSeconds(text: String, maximum: Int): Long {
    val value = text.trim().toBigDecimalOrNull() ?: error("请输入有效秒数")
    require(value >= java.math.BigDecimal.ONE && value <= maximum.toBigDecimal()) { "范围：1 至 $maximum 秒" }
    return value.multiply(1000.toBigDecimal()).longValueExact()
}
