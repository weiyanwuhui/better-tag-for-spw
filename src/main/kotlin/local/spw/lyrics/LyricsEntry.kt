@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)
package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import kotlinx.coroutines.launch

enum class LyricsEntry(val label: String) { CONTEXT("歌词右键"), CONTROLS("播放菜单") }
data class StudioRequest(val media: MediaItem, val page: StudioPage, val owner: LyricsEntry)

/** Keep the editor's lifetime outside the popup, without adding a playback button. */
class LyricsControlsHook(private val plugin: LyricsPlugin, private val native: java.lang.reflect.Method = original) : ComposableHook() {
    companion object {
        val original = HostNames.resolver.composable("MiniPlayerControl", 4, Modifier::class.java)
        val OWNER = original.declaringClass.name
        val CALLER = HostNames.resolver.callerIn(original, HostNames.resolver.facade("MiniPlayer", "miniPlayerCapsuleSurface"))
    }
    @Composable override fun Content(call: UiHookCall) {
        NativeHost.invoke(native, call.thisObject, call.arguments[0], currentComposer, 0, 0)
        val request = plugin.studio.value
        if (plugin.enabled.value && request?.owner == LyricsEntry.CONTROLS) key(request) {
            StudioWindow(plugin, request.media, request.page) { plugin.studio.value = null }
        }
    }
}

class LyricsMoreMenuHook(private val plugin: LyricsPlugin,
    private val open: () -> Unit = { plugin.openStudio(StudioPage.TAGS, LyricsEntry.CONTROLS) }) : MethodHook() {
    companion object {
        val scopeType = HostNames.type("com.xuncorp.spc.ui.component.popupmenu.PopupMenuScope")
        // This host strips DSL parameter names; identify its ordinary menu builder by scope/signature.
        val original = HostNames.resolver.facade("PlayerMoreMenuFlyout").declaredMethods.single {
            it.returnType == Unit::class.java && it.parameterTypes.lastOrNull() == scopeType &&
                Composer::class.java !in it.parameterTypes
        }.apply { isAccessible = true }
        val parameters = original.parameterTypes.map { it.name }
    }
    override fun after(call: MethodHookParam) {
        if (call.throwable == null && !call.isOriginalSkipped) append(call.args.last()!!)
    }
    fun append(scope: Any) {
        if (!plugin.enabled.value || plugin.entry.value != LyricsEntry.CONTROLS) return
        NativeUi.MenuBuilder(scope).apply {
            divider()
            playerItem("标签快捷编辑", { NativeUi.TagIcon() }) {
                if (plugin.enabled.value && plugin.entry.value == LyricsEntry.CONTROLS) open()
            }
        }
    }
}

@Composable fun LyricsEntryMenu(plugin: LyricsPlugin, position: androidx.compose.ui.window.PopupPositionProvider, dismiss: () -> Unit, owner: LyricsEntry) {
    val scope = rememberCoroutineScope()
    val media = plugin.media.value
    val offset = remember(media?.id, plugin.revision.intValue) { media?.let { plugin.store.get(it.id).offset } ?: 0 }
    LyricsContextMenu(position, dismiss, open = { dismiss(); plugin.openStudio(it, owner) }, update = { change ->
        val current = media
        if (current == null) plugin.notify("请先播放歌曲", true)
        else scope.launch {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { plugin.update(current.id, change = change) } }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { plugin.notify(failure.message ?: "保存失败", true) }
        }
    }, refresh = { dismiss(); media?.id?.let(NativeHost::reload) }, failure = { plugin.notify(it, true) }, offset = offset)
}
