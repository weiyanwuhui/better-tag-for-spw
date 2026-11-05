package local.spw.lyrics

import java.nio.file.Path
import kotlin.math.abs

enum class AutoTagMode(val label: String) { COMPLETE("补全缺失并纠正错误"), FILL("仅补全缺失") }
val AUTO_TAG_SOURCES = setOf(LyricSource.NETEASE, LyricSource.QQ, LyricSource.KUGOU)

data class AutoTagSettings(val enabled: Boolean = false, val mode: AutoTagMode = AutoTagMode.COMPLETE,
    val sources: Set<LyricSource> = AUTO_TAG_SOURCES, val fields: Set<MusicTag> = MusicTag.entries.toSet(), val cover: Boolean = true,
    val concurrency: Int = 32) {
    init { require(concurrency in 1..64) { "搜索并发范围：1 至 64" } }
    val policy: String get() = "v1:$mode:${sources.sortedBy { it.name }}:${fields.sortedBy { it.name }}:$cover"
}

object AutoTagMatching {
    fun meaningful(value: String) = value.isNotBlank() && SearchRanking.key(value) !in setOf("unknown", "unknownartist", "unknownalbum", "untitled", "null", "未知", "未知歌手", "未知专辑", "未命名", "群星", "variousartists") &&
        !Regex("(?i)^(track|音轨|曲目)[\\s._-]*[0-9]+$").matches(value.trim())
    private fun title(a: String, b: String) = SearchRanking.key(a) == SearchRanking.key(b) && SearchRanking.version(a) == SearchRanking.version(b)
    private fun artist(a: String, b: String) = SearchRanking.artists(a) == SearchRanking.artists(b)
    private fun album(a: String, b: String) = SearchRanking.key(a) == SearchRanking.key(b)
    fun targets(path: Path, tags: MusicTags, duration: Long): List<SearchTarget> {
        if (duration <= 0) return emptyList()
        val name = tags.values[MusicTag.TITLE].orEmpty().takeIf(::meaningful).orEmpty()
        val singer = tags.values[MusicTag.ARTIST].orEmpty().takeIf(::meaningful).orEmpty()
        val record = tags.values[MusicTag.ALBUM].orEmpty().takeIf(::meaningful).orEmpty()
        val filename = path.fileName.toString().substringBeforeLast('.').replace(Regex("^\\d{1,3}[.、]\\s*"), "").trim()
        val parts = filename.split(Regex("\\s+[-–—]\\s+")).map(String::trim)
        val choices = mutableListOf(SearchTarget(name, singer, record, duration))
        if (parts.size == 2 && parts.all(::meaningful)) {
            if (singer.isNotBlank() && artist(singer, parts[1])) choices += SearchTarget(parts[0], singer, record, duration)
            else if (singer.isNotBlank() && artist(singer, parts[0])) choices += SearchTarget(parts[1], singer, record, duration)
            else {
                choices += SearchTarget(parts[0], parts[1], record, duration)
                choices += SearchTarget(parts[1], parts[0], record, duration)
            }
        } else if (meaningful(filename) && !filename.matches(Regex("[a-fA-F0-9]{16,}"))) choices += SearchTarget(filename, singer, record, duration)
        return choices.filter { meaningful(it.title) && (meaningful(it.artist) || meaningful(it.album)) }
            .distinctBy { Triple(SearchRanking.key(it.title), SearchRanking.artists(it.artist), SearchRanking.version(it.title)) }
    }
    private fun eligible(candidate: Candidate, target: SearchTarget) =
            meaningful(candidate.title) && meaningful(candidate.artist) && meaningful(candidate.album) && candidate.duration > 0 &&
                title(candidate.title, target.title) && abs(candidate.duration - target.duration) <= minOf(3000L, maxOf(1500L, target.duration / 100)) &&
                    (if (target.artist.isNotBlank()) artist(candidate.artist, target.artist) else album(candidate.album, target.album))
    fun searchComplete(candidates: List<Candidate>, target: SearchTarget, remaining: Int): Boolean {
        val eligible = candidates.filter { eligible(it, target) }
        val groups = eligible.groupBy { SearchRanking.key(it.album) }
        val matched = choose(candidates, listOf(target))
        if (matched != null && (if (target.album.isBlank()) groups.size == 1 else album(matched.album, target.album))) return true
        return (groups.values.maxOfOrNull { it.map(Candidate::source).distinct().size } ?: 0) + remaining < 2
    }
    fun choose(candidates: List<Candidate>, targets: List<SearchTarget>): Candidate? {
        val eligible = candidates.distinctBy { it.key }.filter { candidate -> targets.any { eligible(candidate, it) } }
        val groups = eligible.groupBy { Triple(SearchRanking.key(it.title), SearchRanking.artists(it.artist), SearchRanking.key(it.album)) }
        val corroborated = groups.values.filter { it.map(Candidate::source).distinct().size >= 2 }
        if (corroborated.isEmpty()) return null
        if (corroborated.map { SearchRanking.key(it.first().title) to SearchRanking.artists(it.first().artist) }.distinct().size != 1) return null
        val albumMatches = corroborated.filter { group -> targets.any { it.album.isNotBlank() && album(group.first().album, it.album) } }
        val group = if (albumMatches.size == 1) albumMatches.single() else {
            if (albumMatches.isNotEmpty() || corroborated.size != 1) return null
            val only = corroborated.single()
            if (eligible.any { other -> targets.any { it.album.isNotBlank() && album(other.album, it.album) } && !album(other.album, only.first().album) }) return null
            only
        }
        return group.minBy { when (it.source) { LyricSource.QQ -> 0; LyricSource.NETEASE -> 1; else -> 2 } }
    }
    fun changes(original: MusicTags, fetched: MusicTags, candidate: Candidate, settings: AutoTagSettings): Map<MusicTag, String> {
        require(title(fetched.values[MusicTag.TITLE].orEmpty(), candidate.title) && artist(fetched.values[MusicTag.ARTIST].orEmpty(), candidate.artist) && album(fetched.values[MusicTag.ALBUM].orEmpty(), candidate.album)) { "详细标签与交叉匹配结果不一致" }
        val albumChanged = meaningful(original.values[MusicTag.ALBUM].orEmpty()) && !album(original.values[MusicTag.ALBUM].orEmpty(), candidate.album)
        return fetched.values.filter { (field, value) ->
            val previous = original.values[field].orEmpty()
            val equal = when (field) { MusicTag.TITLE -> title(previous, value); MusicTag.ARTIST -> artist(previous, value); MusicTag.ALBUM -> album(previous, value); else -> previous == value }
            val numericInvalid = when (field) {
                MusicTag.YEAR -> !previous.matches(Regex("[12][0-9]{3}"))
                MusicTag.TRACK, MusicTag.DISC -> !previous.matches(Regex("[1-9][0-9]*(/[1-9][0-9]*)?"))
                else -> false
            }
            field in settings.fields && meaningful(value) && !equal && (!meaningful(previous) || settings.mode == AutoTagMode.COMPLETE &&
                (field in setOf(MusicTag.TITLE, MusicTag.ARTIST, MusicTag.ALBUM) || numericInvalid || albumChanged && field in setOf(MusicTag.YEAR, MusicTag.TRACK, MusicTag.DISC, MusicTag.ALBUM_ARTIST)))
        }
    }
}
