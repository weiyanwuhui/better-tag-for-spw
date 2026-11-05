package local.spw.lyrics

import com.xuncorp.voxzen.service.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystemException
import java.nio.file.StandardCopyOption.*

interface TagPlayback {
    fun release(): Boolean
    fun isCurrent(): Boolean
    fun restore()
}

/** Prepare and verify the file first; release the active stream only for the final replacement. */
fun replaceTagFile(stage: Path, target: Path, playback: TagPlayback) {
    try { Files.move(stage, target, ATOMIC_MOVE, REPLACE_EXISTING); return }
    catch (locked: FileSystemException) {
        if (locked is java.nio.file.AtomicMoveNotSupportedException || !playback.release()) throw locked
        try {
            repeat(60) {
                check(playback.isCurrent()) { "歌曲已切换，已取消标签写入" }
                try { Files.move(stage, target, ATOMIC_MOVE, REPLACE_EXISTING); return }
                catch (retry: FileSystemException) { if (retry is java.nio.file.AtomicMoveNotSupportedException) throw retry }
                Thread.sleep(50)
            }
            throw java.io.IOException("文件仍被其他程序占用，未改动原文件", locked)
        } finally { playback.restore() }
    }
}

class NativeTagPlayback(private val id: String) : TagPlayback {
    private var position = 0L
    private var playing = false
    private fun <T> main(action: () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }
    override fun isCurrent() = NativeHost.currentId() == id
    override fun release(): Boolean = main {
        if (!isCurrent()) false else {
            val player = NativePlayerAccess.player()
            position = NativePlayerAccess.position(player)
            playing = NativePlayerAccess.playing(player)
            PlaybackController.releaseAudioEngine()
            true
        }
    }
    override fun restore() = main {
        if (isCurrent()) {
            PlaybackController.prepare()
            PlaybackController.seekToQueued(position)
            if (playing) PlaybackController.play() else PlaybackController.pause()
        }
    }
}
