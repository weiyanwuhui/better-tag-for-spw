package local.spw.lyrics

import kotlinx.serialization.json.*

fun checkQqMetadata() {
    fun json(text: String) = Json.parseToJsonElement(text)
    val full = json("""{"search":{"code":0,"data":{"body":{"song":{"list":[{"id":1,"mid":"mid1","title":"歌曲","interval":269,"singer":[{"name":"歌手"}],"album":{"name":"专辑","mid":"album1"}}]}}}}}""")
    val modern = LyricSources { url, pairs ->
        check(url == QqMusic.URL)
        val params = pairs.toMap()
        check(params["outCharset"] == "utf-8")
        val data = json(params.getValue("data")).jsonObject
        check(data["comm"]!!.jsonObject["ct"]!!.jsonPrimitive.int == 19)
        full
    }.search(LyricSource.QQ, "歌曲", "歌手").single()
    check(modern.duration == 269000L && modern.album == "专辑" && modern.songId == "1")
    var batchCalls = 0
    val fallback = LyricSources { url, pairs ->
        when {
            url.contains("smartbox") -> json("""{"code":0,"data":{"song":{"itemlist":[{"id":1,"mid":"mid1","name":"歌曲","singer":"歌手"},{"id":2,"mid":"mid2","name":"其他版本","singer":"歌手"}]}}}""")
            url == QqMusic.URL && json(pairs.toMap().getValue("data")).jsonObject.containsKey("detail0") -> {
                batchCalls++
                json("""{"detail0":{"data":{"track_info":{"id":1,"mid":"mid1","title":"歌曲","interval":269,"singer":[{"name":"歌手"}],"album":{"name":"专辑"}}}},"detail1":{"data":{"track_info":{"id":99,"mid":"wrong","title":"错误歌曲","interval":5}}}}""")
            }
            else -> error("simulated unavailable full-search endpoint")
        }
    }.search(LyricSource.QQ, "歌曲", "歌手")
    check(batchCalls == 1 && fallback.size == 1 && fallback.single().duration == 269000L && fallback.single().album == "专辑")
    val tags = TagSources().qq(json("""{"songinfo":{"data":{"track_info":{"title":"歌曲","interval":269,"singer":[{"name":"歌手"}],"album":{"name":"专辑"},"index_album":2,"index_cd":1},"info":{"genre":{"selected":"genre","content":[{"value":"Pop"}]},"pub_time":{"selected":"pub_time","content":[{"value":"2004-12-31"}]},"composer":{"title":"作曲","content":[{"value":"作曲人"}]}}}}}"""), false)
    check(tags.values[MusicTag.GENRE] == "Pop" && tags.values[MusicTag.YEAR] == "2004" && tags.values[MusicTag.COMPOSER] == "作曲人")
    check(tags.values[MusicTag.TRACK] == "2" && tags.values[MusicTag.DISC] == "1" && tags.duration == 269000L)
    println("PASS: QQ full metadata search, UTF-8 parameters, batched suggestion enrichment with identity checks, and object-shaped detailed credits/year/genre.")
}
