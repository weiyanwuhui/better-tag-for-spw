package local.spw.lyrics

import androidx.compose.runtime.*
import androidx.compose.ui.window.PopupPositionProvider

@Composable fun LyricsContextMenu(position: PopupPositionProvider, dismiss: () -> Unit, open: (StudioPage) -> Unit,
    update: ((SavedLyrics) -> SavedLyrics) -> Unit, refresh: () -> Unit, failure: (String) -> Unit, offset: Long = 0) {
    NativeUi.AppTheme {
        NativeUi.Menu(position, dismiss) { builder ->
            builder.item("搜索歌词…") { open(StudioPage.SEARCH) }
            builder.item("音乐标签…") { open(StudioPage.TAGS) }
            builder.item("更换封面…") { open(StudioPage.COVER) }
            builder.item("后台整理…") { open(StudioPage.AUTO) }
            builder.divider()
            builder.content { QuickTypographyControls(failure) }
            builder.divider()
            builder.content { QuickTimingControls(offset, update, failure) }
            builder.item("立即刷新歌词", action = refresh)
            builder.item("恢复本曲原歌词") { update { it.copy(text = null, translation = null, romanization = null, source = "", offset = 0) } }
        }
    }
}
