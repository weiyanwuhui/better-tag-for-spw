@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.lyrics

import androidx.compose.ui.awt.ComposeWindow
import com.xuncorp.spw.workshop.api.*
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Window
import javax.swing.SwingUtilities

/** Own test window and disabled worker only; never starts a plugin in the real player. */
fun checkAutoWindow() {
    val root = Files.createTempDirectory(Path.of("build"), "auto-window-")
    val worker = AutoTagger(AutoTagStore(root.resolve("state")), tracks = { error("Disabled monitor queried the library") }, protected = { false }, current = { false })
    val plugin = LyricsPlugin(PluginContext("local.spw.lyrics.test", "0.6.1", root.toString(), "1.19.2", Channel.Steam))
    val monitor = AutoTagsWindow(plugin)
    fun inject(name: String, value: Any) = LyricsPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)
    inject("autoTags", worker)
    inject("preferences", LyricsPreferencesStore(root.resolve("preferences.properties")) { worker.configure(it.autoTags) })
    inject("autoWindow", monitor)
    val previous = LyricsPlugin.active
    val before = Window.getWindows().toSet()
    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    var first: ComposeWindow? = null
    fun flush() { SwingUtilities.invokeAndWait { } }
    try {
        plugin.enabled.value = true; LyricsPlugin.active = plugin
        var routed = false
        plugin.backgroundPage = { routed = true }
        plugin.openAutoTags(); flush()
        check(routed && Window.getWindows().none { it !in before && it.isDisplayable }) { "An existing editor route opened a separate window" }
        plugin.backgroundPage = null
        plugin.openAutoTags(); flush()
        SwingUtilities.invokeAndWait {
            first = Window.getWindows().filterIsInstance<ComposeWindow>().single { it !in before && it.isDisplayable }
            first!!.exceptionHandler = object : androidx.compose.ui.window.WindowExceptionHandler { override fun onException(throwable: Throwable) { failures.add(throwable) } }
            check(first!!.isShowing && plugin.media.value == null) { "Background monitor requires a playing track" }
            first!!.renderImmediately()
        }
        plugin.openAutoTags(); flush()
        SwingUtilities.invokeAndWait { check(Window.getWindows().count { it !in before && it.isDisplayable } == 1) { "Repeated entry opened duplicate monitors" } }
        monitor.close(); flush()
        check(!first!!.isDisplayable && worker.configuration.value.enabled == false)
        monitor.open(); flush()
        SwingUtilities.invokeAndWait {
            val reopened = Window.getWindows().filterIsInstance<ComposeWindow>().single { it !in before && it.isDisplayable }
            check(reopened !== first && reopened.isShowing)
            reopened.renderImmediately()
            plugin.openAutoTags()
            check(reopened.title.contains("后台整理") && reopened.size == java.awt.Dimension(880, 780))
            reopened.renderImmediately()
        }
        check(failures.isEmpty()) { "Standalone monitor failed: ${failures.firstOrNull()}" }
    } finally {
        plugin.enabled.value = false; monitor.close(); flush(); worker.close(); LyricsPlugin.active = previous
    }
    println("PASS: background entry opens a standalone monitor without a song; repeated opening reuses it, and close/reopen leaves the worker intact without Workshop configuration.")
}
