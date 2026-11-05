@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package local.spw.lyrics

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily

/** Keep SPW's sizes, weights and controls, changing only the plugin's text family. */
object UiTypography {
    private val resolver = HostNames.resolver
    private fun localField(method: java.lang.reflect.Method) = (listOf(method) + resolver.calls(method) { it.returnType == ProvidableCompositionLocal::class.java && it.parameterCount == 0 })
        .flatMap(resolver::referencedFields).distinct().single { it.type == ProvidableCompositionLocal::class.java }
    private val textLocal = localField(resolver.composable("com.moriafly.salt.ui.ProvideTextStyle", 4, TextStyle::class.java))
    private val stylesLocal = localField(NativeUi.themeStyles)
    private val styleGetters = listOf("main", "sub", "paragraph", "largeTitle").map { resolver.getter(NativeUi.stylesType, it) }
    private val styleConstructor = NativeUi.stylesType.getConstructor(*Array(4) { TextStyle::class.java })
    @Composable fun CenteredInput(content: @Composable () -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val local = remember { textLocal.get(null) as ProvidableCompositionLocal<TextStyle> }
        CompositionLocalProvider(local.provides(local.current.merge(TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center))), content = content)
    }
    val family: FontFamily by lazy {
        val available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val name = listOf("Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Noto Sans SC").firstOrNull { it in available } ?: "SansSerif"
        Class.forName("androidx.compose.ui.text.font.FontFamily_desktopKt").getMethod("FontFamily", String::class.java).invoke(null, name) as FontFamily
    }
    @Composable fun Provide(content: @Composable () -> Unit) {
        val original = NativeHost.invoke(NativeUi.themeStyles, null, currentComposer)!!
        val styles = remember(original) {
            val values = styleGetters.map { (it.invoke(original) as TextStyle).merge(SpanStyle(fontFamily = family)) }
            styleConstructor.newInstance(*values.toTypedArray())
        }
        @Suppress("UNCHECKED_CAST")
        val local = remember { stylesLocal.get(null) as ProvidableCompositionLocal<Any> }
        CompositionLocalProvider(local.provides(styles), content = content)
    }
}
