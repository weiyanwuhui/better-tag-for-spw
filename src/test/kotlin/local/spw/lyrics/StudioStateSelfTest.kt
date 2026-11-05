package local.spw.lyrics

fun checkStudioNavigation() {
    val media = com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem("曲", "歌手", "专辑", "", "file", id = "test")
    val saved = SavedLyrics("[00:01.000]已应用", "原来源")
    val state = StudioState(media, saved, "[00:02.000]播放器当前", StudioPage.SEARCH)
    check(state.searchAlbum.isEmpty() && state.album.isEmpty() && state.lyricTarget().album.isEmpty() && state.target().album.isEmpty()) { "Search inherited the track album" }
    state.searchAlbum = "手动筛选"
    check(state.lyricTarget().album == "手动筛选" && state.target().album.isEmpty())
    state.searchAlbum = ""
    state.album = "标签专辑筛选"
    check(state.target().album == "标签专辑筛选" && state.lyricTarget().album.isEmpty() && media.album == "专辑")
    state.album = ""
    check(state.previewLyrics() == null) { "Player overrides were mislabeled as embedded lyrics" }
    state.embedded = SavedLyrics("[00:03.000]文件内嵌", "文件内嵌歌词")
    check(state.previewLyrics()?.text == state.embedded?.text)
    check(state.lyricsForExport().text == state.embedded?.text)
    state.editPreview()
    check(state.page == StudioPage.EDIT && !state.hasUnsavedLyrics()) { "Opening embedded lyrics counted as an edit" }
    state.navigate(StudioPage.DISPLAY); state.navigate(StudioPage.COVER)
    check(state.back() && state.page == StudioPage.DISPLAY)
    check(state.back() && state.page == StudioPage.EDIT)
    check(state.back() && state.page == StudioPage.SEARCH && !state.hasUnsavedLyrics())
    val words = "[00:04.000]<00:04.000>来<00:04.500>源<00:05.000>"
    state.selected = Candidate(LyricSource.QQ, "qq", "曲", "歌手")
    state.downloaded = DownloadedLyrics(words, "QQ 音乐")
    state.previewCurrent = false
    check(state.previewLyrics()?.text == words)
    check(state.lyricsForExport().text == words)
    state.editPreview()
    check(!state.hasUnsavedLyrics() && state.document?.hasWords == true)
    check(state.flushEditor?.invoke() != false && state.back() && state.page == StudioPage.SEARCH)
    check(state.previewLyrics()?.text == words && state.selected?.id == "qq")
    state.editPreview(); state.draft = state.draft.copy(text = words.replace("来", "新"))
    check(state.hasUnsavedLyrics())
    state.back()
    check(state.previewLyrics()?.text == state.draft.text && state.hasUnsavedLyrics()) { "Back discarded pending edits" }
    val changed = state.draft
    val apply: (SavedLyrics) -> SavedLyrics = { it.copy(text = changed.text, translation = changed.translation, romanization = changed.romanization) }
    state.committed(apply(state.saved), apply)
    check(!state.hasUnsavedLyrics()) { "Applied edits stayed dirty" }
    state.previewCurrent = true
    check(state.previewLyrics()?.text == state.embedded?.text) { "Result edits overwrote embedded preview" }
    check(state.saved.text != state.embedded?.text) { "Preview applied embedded lyrics automatically" }
    state.navigate(StudioPage.TAGS); state.enterAuto(); check(state.back() && state.page == StudioPage.TAGS)
    state.navigate(StudioPage.SEARCH); check(!state.back()) { "Navigation accumulated a loop" }
    println("PASS: embedded/result preview separation, untouched editor cleanliness, pending edit retention, applied baseline, nested navigation and loop-free back stack.")
}
