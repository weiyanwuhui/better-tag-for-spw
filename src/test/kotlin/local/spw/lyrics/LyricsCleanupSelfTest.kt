@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.HookHandle

fun checkLyricsCleanup() {
    val plugin = LyricsPlugin(PluginContext("local.spw.lyrics", "test", ".", "1.19.2", Channel.Steam))
    @Suppress("UNCHECKED_CAST") val handles = LyricsPlugin::class.java.getDeclaredField("hooks").apply { isAccessible = true }.get(plugin) as MutableList<HookHandle>
    val calls = IntArray(4)
    var failing = true
    repeat(4) { index -> handles += object : HookHandle {
        override fun unhook() {
            calls[index]++
            if (failing && index == 0) throw IllegalStateException("injected first hook failure")
            if (failing && index == 2) throw NoClassDefFoundError("injected third hook failure")
        }
    } }
    plugin.enabled.value = true
    val errors = java.io.ByteArrayOutputStream()
    val original = System.err
    try {
        System.setErr(java.io.PrintStream(errors))
        val failure = runCatching { plugin.stop() }.exceptionOrNull()
        check(failure is IllegalStateException && failure.suppressed.size == 2)
        check(calls.toList() == listOf(1, 1, 1, 1) && handles.size == 2 && !plugin.enabled.value)
        check(runCatching { plugin.stop() }.exceptionOrNull()?.suppressed?.size == 2)
        check(calls.toList() == listOf(2, 1, 2, 1))
        failing = false; plugin.stop(); plugin.stop()
        check(calls.toList() == listOf(3, 1, 3, 1) && handles.isEmpty())
        check(errors.toString().contains("injected first hook failure") && errors.toString().contains("injected third hook failure"))
    } finally { System.setErr(original) }
    println("PASS: lyric stop attempts every hook, reports/logs multiple failures, retains failed handles for retry and never repeats successful cleanup.")
}
