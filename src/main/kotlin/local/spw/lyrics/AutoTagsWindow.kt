@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.lyrics

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** Independent of the current track, so changing songs or closing lyrics never hides the monitor. */
class AutoTagsWindow(private val plugin: LyricsPlugin) : AutoCloseable {
    private var window: ComposeWindow? = null
    fun open() {
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater { open() }; return }
        if (!plugin.enabled.value) return
        window?.let { it.isVisible = true; it.toFront(); it.requestFocus(); return }
        val frame = ComposeWindow()
        window = frame
        frame.title = "后台整理 · Better Tag for SPW"
        frame.minimumSize = Dimension(720, 600)
        frame.size = Dimension(880, 780)
        frame.defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
        frame.exceptionHandler = object : androidx.compose.ui.window.WindowExceptionHandler {
            override fun onException(throwable: Throwable) { frame.dispose(); plugin.notify(throwable.message ?: "后台整理界面打开失败", true) }
        }
        frame.addWindowListener(object : WindowAdapter() { override fun windowClosed(event: WindowEvent) { if (window === frame) window = null } })
        frame.setContent {
            var config by remember { mutableStateOf(plugin.autoTags.configuration.value) }
            var progress by remember { mutableStateOf(plugin.autoTags.status.value) }
            var error by remember { mutableStateOf("") }
            LaunchedEffect(plugin) { (plugin.autoTags.configuration as Flow<AutoTagSettings>).collect { config = it } }
            LaunchedEffect(plugin) {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                (plugin.autoTags.status as Flow<AutoTagProgress>).sample(150).collect { progress = it }
            }
            StandaloneLyricsTheme {
                AutoTagsScreen(config, progress.copy(message = error.ifBlank { progress.message }), { value ->
                    try { plugin.preferences.save(plugin.preferences.load().copy(autoTags = value)); error = "" }
                    catch (failure: Exception) { error = failure.message ?: "设置保存失败" }
                }, plugin.autoTags::scanNow, plugin.autoTags::togglePause, frame::dispose)
            }
        }
        frame.setLocationRelativeTo(java.awt.Window.getWindows().firstOrNull { it !== frame && it.isShowing })
        frame.isVisible = true
    }
    override fun close() {
        val dispose = { window?.dispose(); window = null }
        if (EventQueue.isDispatchThread()) dispose() else EventQueue.invokeLater { dispose() }
    }
}

@Composable fun StandaloneLyricsTheme(content: @Composable () -> Unit) {
    val hazeLocals = remember {
        val type = HostNames.resolver.facade("BlurYesNoDialog", "LocalWallpaperHazeState", "LocalPopupHazeState")
        type.declaredFields.filter { it.type == ProvidableCompositionLocal::class.java }.also { check(it.size == 2) }.map { field ->
            @Suppress("UNCHECKED_CAST")
            (field.apply { isAccessible = true }.get(null) as ProvidableCompositionLocal<Any>).provides(dev.chrisbanes.haze.HazeState())
        }.toTypedArray()
    }
    CompositionLocalProvider(*hazeLocals) { NativeUi.AppTheme(content) }
}

