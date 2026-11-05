package local.spw.lyrics

import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

object LyricsFiles {
    fun choose(save: Boolean, title: String, name: String, extensions: List<String>): Path? {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val mode = if (save) FileDialog.SAVE else FileDialog.LOAD
        val dialog = if (owner is Dialog) FileDialog(owner, title, mode) else FileDialog(owner as? Frame, title, mode)
        try {
            if (name.isNotEmpty()) dialog.file = name
            dialog.filenameFilter = java.io.FilenameFilter { _, filename -> extensions.any { filename.endsWith(".$it", true) } }
            dialog.isVisible = true
            return dialog.files.firstOrNull()?.toPath()
        } finally { dialog.dispose() }
    }
    fun read(path: Path): String {
        require(Files.isRegularFile(path) && Files.size(path) in 1..LyricsStore.MAX_LYRICS_BYTES.toLong()) { "歌词文件不能超过 1 MiB" }
        val bytes = Files.readAllBytes(path)
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "krc1") return WordSources.krc(bytes)
        val charset = if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) Charsets.UTF_16LE
            else if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) Charsets.UTF_16BE else Charsets.UTF_8
        return try { charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: java.nio.charset.CharacterCodingException) { error("请将歌词另存为 UTF-8 或 UTF-16 编码后导入") }
    }
    fun write(path: Path, text: String) {
        val target = path.toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".lyrics-", ".tmp")
        try {
            Files.writeString(temporary, text)
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
