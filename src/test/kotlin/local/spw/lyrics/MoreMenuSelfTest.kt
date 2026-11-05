@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.lyrics

import androidx.compose.runtime.mutableStateOf
import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

fun checkMoreMenu() {
    val resolver = HostNames.resolver
    val method = LyricsMoreMenuHook.original
    local.spw.host.HookInternals.method(method.declaringClass.name, method.name, LyricsMoreMenuHook.parameters)
    val transformerType = HostNames.type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodTransformer")
    val transformer = transformerType.getConstructor(java.lang.reflect.Method::class.java).newInstance(method)
    val before = local.spw.host.HookInternals.bytes(method.declaringClass.name)
    val after = resolver.function(transformerType, "transform").invoke(transformer, method.declaringClass.name, before) as ByteArray
    check(!before.contentEquals(after)) { "Native menu builder was not instrumented" }

    val root = Files.createTempDirectory(Path.of("build"), "more-menu-")
    val plugin = LyricsPlugin(PluginContext("test", "0.6.26", root.toString(), "1.19.2", Channel.Steam))
    plugin.enabled.value = true
    var opened = 0
    val hook = LyricsMoreMenuHook(plugin) { opened++ }
    val actions = mutableListOf<() -> Unit>()
    var dividers = 0
    val item = resolver.function(LyricsMoreMenuHook.scopeType, "item")
    val divider = resolver.function(LyricsMoreMenuHook.scopeType, "divider")
    val scope = Proxy.newProxyInstance(LyricsMoreMenuHook.scopeType.classLoader, arrayOf(LyricsMoreMenuHook.scopeType)) { proxy, called, arguments ->
        when (called) {
            item -> { @Suppress("UNCHECKED_CAST") actions += arguments[0] as () -> Unit; null }
            divider -> { dividers++; null }
            else -> when (called.name) { "toString" -> "menu fixture"; "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === arguments[0]; else -> null }
        }
    }
    val state = HostNames.type("com.xuncorp.pisces.PiscesPlayer\$State").enumConstants.first()
    val arguments: Array<Any?> = Array(method.parameterCount) { index ->
        when { index == method.parameterCount - 1 -> scope
            method.parameterTypes[index] == androidx.compose.runtime.State::class.java -> mutableStateOf(state)
            else -> null }
    }
    // Execute the real host builder with a recording scope; its callbacks are never invoked.
    NativeHost.invoke(method, null, *arguments)
    val originalActions = actions.toList()
    check(originalActions.size >= 3) { "Wrong native menu builder selected" }
    var failure: Throwable? = null
    var skipped = false
    val call = object : MethodHookParam() {
        override val method = LyricsMoreMenuHook.original
        override val thisObject: Any? = null
        override val args = arguments
        override val result: Any = Unit
        override val throwable get() = failure
        override val isOriginalSkipped get() = skipped
        override fun setResult(result: Any?) = error("Menu entry must not replace the original result")
        override fun setThrowable(throwable: Throwable) = error("Menu entry must not change the original error")
        override fun invokeOriginal(): Any = error("Menu entry must not rebuild native items")
    }
    plugin.entry.value = LyricsEntry.CONTEXT
    hook.after(call)
    check(actions == originalActions && dividers == 0)
    plugin.entry.value = LyricsEntry.CONTROLS
    hook.after(call)
    check(actions.take(originalActions.size) == originalActions && actions.size == originalActions.size + 1 && dividers == 1)
    val added = actions.last()
    added(); check(opened == 1)
    plugin.entry.value = LyricsEntry.CONTEXT
    added(); check(opened == 1) { "An old popup action survived the entry-mode change" }
    plugin.entry.value = LyricsEntry.CONTROLS
    failure = IllegalStateException("native builder failed"); hook.after(call)
    failure = null; skipped = true; hook.after(call)
    plugin.enabled.value = false; skipped = false; hook.after(call); added()
    check(actions.size == originalActions.size + 1 && opened == 1)
    println("PASS: real More-menu builder resolves and transforms; native entries remain, one editor action is appended, right-click/disabled/stale/error/skipped paths are inert.")
}
