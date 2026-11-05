@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package local.spw.lyrics

import androidx.compose.runtime.*
import java.lang.classfile.*
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.constant.ClassDesc

/** Same bridge signatures, isolated dispatch; the host's real transformer builds the call site. */
object CountdownHookFixture {
    var settings by mutableStateOf(CountdownSettings())
    var enabled by mutableStateOf(true)
    var calls = 0
    @JvmStatic fun hasReplacement(id: Int) = false
    @JvmStatic @Composable fun RenderReplacement(id: Int, receiver: Any?, arguments: Array<Any?>) {
    }
    @JvmStatic @Composable fun RenderAfter(id: Int, receiver: Any?, arguments: Array<Any?>) {
        calls++
        if (enabled) CountdownRenderer.Content(arguments.toList().toMutableList().apply { this[6] = androidx.compose.ui.graphics.Color.Blue.value.toLong() }, settings, receiver)
    }
}

fun transformedCountdownEntry(): java.lang.reflect.Method {
    val method = CountdownRenderer.native
    val owner = method.declaringClass.name
    val caller = CountdownRenderer.caller
    fun bytes(name: String) = checkNotNull(CountdownHookFixture::class.java.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")).use { it.readAllBytes() }
    val resolved = local.spw.host.HookInternals.ui(owner, method.name, caller, CountdownRenderer.parameters)
    val transformed = local.spw.host.HookInternals.transform(method, caller, resolved)
    check(!transformed.contentEquals(bytes(caller)))
    val classFile = ClassFile.of()
    val isolated = classFile.transformClass(classFile.parse(transformed), ClassTransform.transformingMethodBodies(CodeTransform { builder, element ->
        if (element is InvokeInstruction && element.owner().asInternalName() == "com/xuncorp/voxzen/workshop/hooks/WorkshopHookBridge") {
            builder.invokestatic(ClassDesc.of(CountdownHookFixture::class.java.name), element.name().stringValue(), element.typeSymbol())
        } else builder.with(element)
    }))
    val child = object : ClassLoader(CountdownHookFixture::class.java.classLoader) { fun load() = defineClass(caller, isolated, 0, isolated.size) }.load()
    return child.declaredMethods.single { it.name == method.name && it.parameterCount == 13 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }.apply { isAccessible = true }
}
