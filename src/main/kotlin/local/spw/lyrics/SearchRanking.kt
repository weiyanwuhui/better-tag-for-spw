package local.spw.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class SearchTarget(val title: String, val artist: String, val album: String, val duration: Long)
data class RankedCandidate(val candidate: Candidate, val score: Int)

object SearchRanking {
    fun key(value: String) = ChineseConverter.convert(Normalizer.normalize(value, Normalizer.Form.NFKC), ChineseMode.SIMPLIFIED)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    fun artists(value: String) = value.replace(Regex("(?<=[\\p{IsHan}\\s])\\s*[(（][^)）]*[)）]"), "")
        .split(Regex("[/、,&;，；＆]")).map(::key).filter { it.isNotEmpty() }.toSet()
    private fun similarity(a: String, b: String): Double {
        val x = key(a); val y = key(b)
        if (x == y && x.isNotEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var previous = IntArray(y.length + 1) { it }
        x.forEachIndexed { i, ch ->
            val next = IntArray(y.length + 1); next[0] = i + 1
            y.forEachIndexed { j, other -> next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (ch == other) 0 else 1) }
            previous = next
        }
        return 1.0 - previous.last().toDouble() / maxOf(x.length, y.length)
    }
    fun version(value: String) = listOf("live", "现场", "伴奏", "instrumental", "remix", "翻唱", "karaoke").filter { key(value).contains(it) }.toSet()
    fun score(candidate: Candidate, target: SearchTarget): Int {
        val title = similarity(candidate.title, target.title)
        var weighted = title * 48; var weight = 48.0
        if (target.artist.isNotBlank()) {
            val expected = artists(target.artist); val actual = artists(candidate.artist)
            val match = if (expected == actual && expected.isNotEmpty()) 1.0 else if (expected.isNotEmpty()) expected.intersect(actual).size.toDouble() / expected.union(actual).size.coerceAtLeast(1) else 0.0
            weighted += match * 24; weight += 24
        }
        if (target.album.isNotBlank()) { weighted += similarity(candidate.album, target.album) * 16; weight += 16 }
        if (target.duration > 0) {
            val delta = abs(candidate.duration - target.duration)
            val timing = if (candidate.duration <= 0) 0.0 else if (delta <= 1500) 1.0 else (1.0 - (delta - 1500).toDouble() / maxOf(10_000.0, target.duration * 0.08)).coerceAtLeast(0.0)
            weighted += timing * 12; weight += 12
        }
        var result = weighted / weight * 100
        if (title < 0.5) result = minOf(result, 49.0)
        if (version(candidate.title) != version(target.title)) result = minOf(result, 65.0)
        return result.roundToInt().coerceIn(0, 100)
    }
    fun rank(candidates: List<Candidate>, target: SearchTarget): List<RankedCandidate> = candidates.distinctBy { it.key }
        .map { RankedCandidate(it, score(it, target)) }
        .sortedWith(compareByDescending<RankedCandidate> { it.score }
            .thenBy { if (target.duration > 0 && it.candidate.duration > 0) abs(target.duration - it.candidate.duration) else Long.MAX_VALUE }.thenBy { key(it.candidate.title) }
            .thenBy { key(it.candidate.artist) }.thenBy { key(it.candidate.album) }.thenBy { it.candidate.key })
}
