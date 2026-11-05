package local.spw.lyrics

fun checkSearchRanking() {
    val target = SearchTarget("有谁还没睡", "周传雄", "蓝色土耳其", 283000)
    val exact = Candidate(LyricSource.QQ, "a", target.title, target.artist, target.album, target.duration)
    val otherPlatform = exact.copy(source = LyricSource.KUGOU, id = "b")
    val wrongSong = exact.copy(source = LyricSource.NETEASE, id = "c", title = "青花")
    val wrongArtist = exact.copy(id = "d", artist = "其他歌手")
    val wrongAlbum = exact.copy(id = "e", album = "精选集")
    val wrongDuration = exact.copy(id = "f", duration = 320000)
    val live = exact.copy(id = "g", title = target.title + " (Live)")
    val candidates = listOf(wrongSong, wrongArtist, live, wrongDuration, wrongAlbum, otherPlatform, exact)
    val ranked = SearchRanking.rank(candidates, target)
    check(ranked.take(2).all { it.score == 100 && it.candidate.title == target.title })
    check(SearchRanking.rank(candidates.reversed(), target) == ranked) { "Platform arrival order changed ranking" }
    check(SearchRanking.score(wrongSong, target) < 50)
    check(SearchRanking.score(live, target) <= 65)
    check(listOf(wrongArtist, wrongAlbum, wrongDuration).all { SearchRanking.score(it, target) < 100 })
    check(SearchRanking.score(exact.copy(title = "有誰還沒睡", artist = "周傳雄", album = "藍色土耳其"), target) == 100)
    val duet = SearchTarget("Song", "A / B", "", 0)
    check(SearchRanking.score(exact.copy(title = "Song", artist = "B / A"), duet) == 100)
    check(SearchRanking.score(exact.copy(duration = 0), target) < 100)
    check(timelineLine(LyricRow(1000, 3000, listOf(LyricWord(1000, 2000, "你"), LyricWord(2000, 3000, "好")))) == "<00:01.000>你<00:02.000>好")
    check(timelineLine(LyricRow(1000, 3000, listOf(LyricWord(1000, 3000, "你好")))) == "你好")
    println("PASS: cross-platform deterministic title/artist/album/duration ranking, version penalties, Chinese/duet normalization and real line/word timestamp text.")
}
