@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.voxzen.service.PlaybackController

data class TimingClock(val position: Long = 0, val playing: Boolean = false, val speed: Float = 1f)
interface TimingPlayback {
    fun current(): Boolean
    fun clock(): TimingClock
    fun play()
    fun pause()
    fun seek(position: Long)
    fun speed(value: Float)
}

class NativeTimingPlayback(private val id: String) : TimingPlayback {
    override fun current() = WorkshopApi.playback.getCurrentMediaItem()?.id == id
    override fun clock(): TimingClock {
        check(current()) { "歌曲已切换，编辑内容已保留，请切回本曲再打轴" }
        val player = NativePlayerAccess.player()
        return TimingClock(NativePlayerAccess.position(player).coerceAtLeast(0),
            NativePlayerAccess.playing(player), PlaybackController.getPlaybackSpeed())
    }
    private fun checked(action: () -> Unit) { check(current()) { "歌曲已切换，请切回本曲再打轴" }; action() }
    override fun play() = checked { WorkshopApi.playback.play() }
    override fun pause() = checked { WorkshopApi.playback.pause() }
    override fun seek(position: Long) = checked { WorkshopApi.playback.seekTo(position.coerceAtLeast(0)) }
    override fun speed(value: Float) = checked { require(value in 0.5f..1.5f); PlaybackController.changeSpeed(value) }
}
