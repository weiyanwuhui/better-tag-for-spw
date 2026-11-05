package local.spw.lyrics

import com.xuncorp.voxzen.service.PlaybackController
import java.lang.reflect.Modifier

/** Resolve once; use the raw audio clock rather than a delayed UI progress snapshot. */
object NativePlayerAccess {
    private val resolver = HostNames.resolver
    private val serviceField = resolver.field(PlaybackController::class.java, "service")
    private val serviceType = HostNames.type("com.xuncorp.voxzen.service.PlaybackService")
    private val getPlayer = resolver.getter(serviceType.superclass, "player")
    private val playerType = getPlayer.returnType
    private val progress = resolver.function(serviceType.superclass, "updateProgress") {
        it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType))
    }
    private val progressDefaults = serviceType.superclass.declaredMethods.single {
        Modifier.isStatic(it.modifiers) && it.parameterTypes.firstOrNull() == serviceType.superclass &&
            resolver.calls(it) { candidate -> candidate == progress }.isNotEmpty()
    }
    private val progressClocks = resolver.calls(progressDefaults) { it.declaringClass == playerType && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }.also {
        check(it.size == 2 && it[0] != it[1]) { "SPW 播放时钟接口不兼容" }
    }
    private val position = progressClocks[0]
    private val playing = resolver.calls(resolver.function(PlaybackController::class.java, "playOrPause")) {
        it.declaringClass == playerType && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
    }.single()
    private val mediaItem = resolver.getter(playerType, "mediaItem")
    fun player(): Any = getPlayer.invoke(serviceField.get(null))
    fun position(player: Any = player()) = position.invoke(player) as Long
    fun playing(player: Any = player()) = playing.invoke(player) as Boolean
    fun mediaItem(): Any? = mediaItem.invoke(player())
    fun validate() { check(Modifier.isStatic(progressDefaults.modifiers)) }
}
