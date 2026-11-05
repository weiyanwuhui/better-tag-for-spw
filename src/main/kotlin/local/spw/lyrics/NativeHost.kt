@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package local.spw.lyrics

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.xuncorp.voxzen.service.PlaybackController
import com.xuncorp.voxzen.util.AppConfig
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object NativeHost {
    val resolver = HostNames.resolver
    val libraryFlow = resolver.function(HostNames.type("com.xuncorp.voxzen.data.repo.TrackRepo"), "getAllFlow") { method ->
        method.parameterCount == 0 && resolver.calls(method) { true }.any { dao ->
            resolver.sqlQueries(dao).any { it.trim().replace(Regex("\\s+"), " ").equals("SELECT * FROM Track", true) }
        }
    }
    @Suppress("UNCHECKED_CAST")
    fun libraryChanges(): kotlinx.coroutines.flow.Flow<List<com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem>> {
        val flow = libraryFlow.invoke(resolver.receiver(libraryFlow))
            as kotlinx.coroutines.flow.Flow<List<com.xuncorp.voxzen.data.entity.Track>>
        return kotlinx.coroutines.flow.flow {
            flow.collect { tracks -> emit(tracks.map { track ->
                com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem(track.title, track.artist, track.album,
                    track.albumArtist, track.path, id = track.id, duration = track.duration)
            }) }
        }
    }
    val originalPlayer = resolver.composable("PlayerLyrics", 5, Modifier::class.java)
    val PLAYER = originalPlayer.declaringClass.name
    val PLAYER_CALLER = resolver.callerIn(originalPlayer, resolver.facade("PlayerScreen", "PlayerLyricsPanel"))
    val documentType = HostNames.type("com.xuncorp.spc.lyrics.LyricsDocument")
    private val lineType = HostNames.type("com.xuncorp.spc.lyrics.LyricsLine")
    private val cellType = HostNames.type("com.xuncorp.spc.lyrics.LyricsCell")
    private val parser = resolver.function(HostNames.type("com.xuncorp.spc.lyrics.spl.SplDecoder"), "format") { it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
    private val documentLines = resolver.field(documentType, "lyricsLines")
    private val documentSource = resolver.getter(documentType, "sourceText")
    private val cellCtor = cellType.getConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java)
    private val lineCtor = lineType.getConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, List::class.java, String::class.java)
    private val documentCtor = documentType.getConstructor(String::class.java, List::class.java)
    private val serviceField = resolver.field(PlaybackController::class.java, "service")
    private val serviceType = HostNames.type("com.xuncorp.voxzen.service.PlaybackService")
    private val loaderField = resolver.field(serviceType, "lyricsLoader")
    private val loaderType = loaderField.type
    private val currentItem = resolver.field(loaderType, "currentItem")
    private val reload = resolver.function(loaderType, "reload") { it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
    // Follow the loader's actual timeline type; its Metadata namespace moved in SPW 1.19.3.
    val timelineType = resolver.field(loaderType, "timeline").type
    val timelineCompanion = timelineType.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.type }.distinct().single { candidate -> candidate.declaredMethods.any {
            it.parameterTypes.contentEquals(arrayOf(documentType)) && it.returnType == List::class.java
        } }
    val timelineMethod = resolver.function(timelineCompanion, "copyLines") {
        it.parameterTypes.contentEquals(arrayOf(documentType)) && it.returnType == List::class.java
    }

    fun decode(text: String): Any = invoke(parser, resolver.receiver(parser), text)!!
    @Suppress("UNCHECKED_CAST")
    fun lines(document: Any): List<Any> = documentLines.get(document) as List<Any>
    fun raw(document: Any): String = documentSource.invoke(document) as String
    private val monitorType = HostNames.type("com.xuncorp.voxzen.service.PlaybackMonitor")
    private val lyricsDocument = resolver.getter(monitorType, "lyricsDocument")
    private val lyricsClock = resolver.getter(monitorType, "lyricsTime")
    fun currentRaw(): String {
        val flow = lyricsDocument.invoke(resolver.receiver(lyricsDocument)) as kotlinx.coroutines.flow.StateFlow<*>
        return flow.value?.let(::raw).orEmpty()
    }
    @Suppress("UNCHECKED_CAST")
    fun lyricsTime(): kotlinx.coroutines.flow.StateFlow<Long> {
        return lyricsClock.invoke(resolver.receiver(lyricsClock)) as kotlinx.coroutines.flow.StateFlow<Long>
    }
    fun preview(text: String): String = lines(decode(text)).joinToString("\n") { line ->
        val time = long(line, "startTime")
        val sub = string(line, "pureSubText")
        "[%02d:%02d.%02d] %s%s".format(time / 60_000, time / 1000 % 60, time / 10 % 100,
            string(line, "pureMainText"), sub?.let { "\n$it" }.orEmpty())
    }
    private val lineStart = resolver.getter(lineType, "startTime")
    private val lineEnd = resolver.getter(lineType, "endTime")
    private val lineText = resolver.getter(lineType, "pureMainText")
    fun lineKey(line: Any) = Triple(lineStart.invoke(line) as Long, lineEnd.invoke(line) as Long, lineText.invoke(line) as String)
    private fun long(value: Any, property: String) = resolver.getter(value.javaClass, property).invoke(value) as Long
    private fun string(value: Any, property: String) = resolver.getter(value.javaClass, property).invoke(value) as String?

    /** Shift native line and word times together, retaining translations and negative lead-in times. */
    fun shifted(document: Any, offset: Long): Any {
        val adjusted = lines(document).map { line ->
            @Suppress("UNCHECKED_CAST")
            val cells = resolver.getter(lineType, "lyricsCells").invoke(line) as List<Any>
            val shiftedCells = cells.map { cell ->
                cellCtor.newInstance(Math.addExact(long(cell, "startTime"), offset),
                    Math.addExact(long(cell, "endTime"), offset), string(cell, "text"))
            }.filter { string(it, "text").orEmpty().isNotEmpty() }.ifEmpty { listOf(cellCtor.newInstance(Math.addExact(long(line, "startTime"), offset), Math.addExact(long(line, "endTime"), offset), " ")) }
            lineCtor.newInstance(Math.addExact(long(line, "startTime"), offset),
                Math.addExact(long(line, "endTime"), offset), shiftedCells, string(line, "pureSubText"))
        }
        return documentCtor.newInstance(raw(document), adjusted)
    }

    fun applyOffsetBeforePublication(document: Any, offset: Long) {
        if (offset == 0L || lines(document).isEmpty()) return
        // The host drops replacement return values. Mutate this fresh document before either publisher sees it.
        val baseline = decode(raw(document))
        documentLines.set(document, lines(shifted(baseline, offset)))
    }

    fun model(document: Any): LyricsDocument = LyricsDocument(lines(document).map { line ->
        @Suppress("UNCHECKED_CAST") val cells = resolver.getter(lineType, "lyricsCells").invoke(line) as List<Any>
        LyricRow(long(line, "startTime"), long(line, "endTime"), cells.map { cell ->
            LyricWord(long(cell, "startTime"), long(cell, "endTime"), string(cell, "text").orEmpty())
        }, string(line, "pureSubText").orEmpty())
    }, "SPW 原歌词")

    fun applySettings(document: Any, saved: SavedLyrics, countdown: Boolean = false, target: SearchTarget? = null, countdownSettings: CountdownSettings = CountdownSettings()) {
        val source = if (saved.text != null) LyricsCodec.source(saved, "") else {
            val original = decode(raw(document))
            LyricsCodec.withAuxiliary(model(original), saved.translation, saved.romanization)
        }
        val presentation = if (countdown) InterludeCountdown.presentation(source, saved, target, countdownSettings)
            else CountdownPresentation(LyricsCodec.render(source, saved), emptyList())
        val rendered = presentation.document
        val cues = java.util.IdentityHashMap<Any, LyricRow>()
        val cueRows = presentation.cues.toHashSet()
        val nativeLines = rendered.rows.map { row ->
            val start = if (row in cueRows) maxOf(row.start, row.end - countdownSettings.leadMillis) else row.start
            val parts = LyricsCodec.parts(row, saved.order)
            val primary = parts.firstOrNull()
            val cells = (if (row in cueRows) listOf(LyricWord(start, row.end, "\u200B"))
                else if (primary?.first == LyricsPart.ORIGINAL) row.words else listOf(LyricWord(row.start, row.end, primary?.second.orEmpty())))
                .filter { it.text.isNotEmpty() }.ifEmpty { listOf(LyricWord(row.start, row.end, " ")) }
            lineCtor.newInstance(start, row.end,
                cells.map { cellCtor.newInstance(it.start, it.end, it.text) }, parts.drop(1).joinToString("\n") { it.second }.ifEmpty { null }).also {
                    if (row in cueRows) cues[it] = row
                }
        }
        CountdownRenderer.publish(cues)
        documentLines.set(document, nativeLines)
    }

    private fun loader(): Any = loaderField.get(serviceField.get(null))
    fun currentId(): String? {
        val item = currentItem.get(loader()) ?: return null
        return string(item, "id")
    }
    fun isCurrentTrack(track: com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem): Boolean {
        if (currentId() == track.id) return true
        val item = NativePlayerAccess.mediaItem() ?: return false
        if (string(item, "id") == track.id) return true
        val playingPath = string(item, "path").orEmpty()
        if (playingPath.contains("://") && !playingPath.startsWith("file:")) return false
        val activePath = if (playingPath.startsWith("file:")) java.nio.file.Path.of(java.net.URI.create(playingPath)) else java.nio.file.Path.of(playingPath)
        return activePath.toAbsolutePath().normalize().toString().equals(TagFiles.localPath(track.path).toString(), true)
    }
    fun reload(id: String) { reload.invoke(loader(), id) }
    private val coverOwner = resolver.field(serviceType, "imageBitmapOwner")
    private val coverFacade = resolver.facade("mutableImageBitmap", "imageBitmap", "updateImageBitmap")
    val coverPublisher = resolver.function(coverFacade, "updateImageBitmap") {
        it.parameterTypes.contentEquals(arrayOf(monitorType, coverOwner.type))
    }
    private val coverFlow = resolver.field(coverFacade, "mutableImageBitmap")
    private val scaledCache = resolver.getter(resolver.facade("sharedScaledImageBitmapCache"), "sharedScaledImageBitmapCache")
    private val coverChanged = resolver.function(scaledCache.returnType, "onSourceChanged") {
        it.parameterTypes.contentEquals(arrayOf(coverOwner.type))
    }
    fun currentCoverOwner(): Any? = coverOwner.get(serviceField.get(null))
    fun publishCover(owner: Any?) {
        invalidateCover(owner)
        invoke(coverPublisher, resolver.receiver(coverPublisher), resolver.singleton(monitorType), owner)
    }
    fun replacePublishedCover(owner: Any?) {
        invalidateCover(owner)
        @Suppress("UNCHECKED_CAST") val flow = coverFlow.get(null) as kotlinx.coroutines.flow.MutableSharedFlow<Any?>
        check(flow.tryEmit(owner)) { "封面刷新失败" }
    }
    fun invalidateCover(owner: Any?) {
        coverChanged.invoke(scaledCache.invoke(resolver.receiver(scaledCache)), owner)
    }

    fun fontSize() = AppConfig.lyricsFontSize
    val localVri = resolver.function(HostNames.type("com.xuncorp.spc.v.Vri\$Companion"), "parse") { resolver.strings(it).contains("file:///") }
    private val rereadTracks = resolver.function(HostNames.type("com.xuncorp.voxzen.service.RepoService"), "rereadTracks") {
        it.parameterTypes.contentEquals(arrayOf(List::class.java, kotlin.coroutines.Continuation::class.java))
    }
    suspend fun refreshTagsQuietly(paths: List<String>) {
        if (paths.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val vris = paths.distinct().mapNotNull { path -> runCatching { TagFiles.localPath(path) }.getOrNull()?.let { localVri.invoke(resolver.receiver(localVri), it.toString()) } }
            if (vris.isEmpty()) return@withContext
            var result = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                invoke(rereadTracks, resolver.receiver(rereadTracks), vris, continuation)
            }
            if (result?.javaClass?.name == "kotlin.Result") result = result.javaClass.getMethod("unbox-impl").invoke(result)
            HostNames.type("kotlin.ResultKt").getMethod("throwOnFailure", Any::class.java).invoke(null, result)
        }
    }
    suspend fun refreshWrittenTags(media: com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem, values: Map<MusicTag, String>) {
        refreshTagsQuietly(listOf(media.path))
        val itemType = HostNames.type("com.xuncorp.pisces.PiscesMediaItem")
        val item = itemType.getConstructor(*Array(6) { String::class.java }).newInstance(media.id,
            values[MusicTag.TITLE] ?: media.title, values[MusicTag.ARTIST] ?: media.artist,
            values[MusicTag.ALBUM] ?: media.album, values[MusicTag.ALBUM_ARTIST] ?: media.albumArtist, media.path)
        val items = java.lang.reflect.Array.newInstance(itemType, 1)
        java.lang.reflect.Array.set(items, 0, item)
        val controller = PlaybackController::class.java.getField("INSTANCE").get(null)
        invoke(PlaybackController::class.java.getMethod("updateMediaItems", items.javaClass), controller, items)
        if (currentId() == media.id) {
            invoke(PlaybackController::class.java.getMethod("refreshCurrentCover"), controller)
            reload(media.id)
        }
    }
    fun fontWeight() = AppConfig.lyricsFontWeight
    fun alignment() = AppConfig.lyricsAlignment.ordinal
    fun fontWeight(value: Int) {
        require(value in 100..900)
        val type = HostNames.type("com.xuncorp.voxzen.util.Spkv")
        check(type.getMethod("encode", String::class.java, Int::class.javaPrimitiveType)
            .invoke(type.getField("INSTANCE").get(null), "lyrics_font_weight", value) != false) { "字重保存失败" }
        AppConfig.updateLyricsFontWeight(value)
    }
    fun alignment(value: Int) {
        val alignment = com.xuncorp.voxzen.ui.screen.lyrics.LyricsAlignment::class.java.enumConstants[value]
        val type = HostNames.type("com.xuncorp.voxzen.util.Spkv")
        check(type.getMethod("encode", String::class.java, Enum::class.java)
            .invoke(type.getField("INSTANCE").get(null), "lyrics_alignment", alignment) != false) { "对齐保存失败" }
        AppConfig.updateLyricsAlignment(alignment)
    }
    fun fontSize(value: Int) {
        val size = value.coerceIn(12, 80)
        val spkv = HostNames.type("com.xuncorp.voxzen.util.Spkv")
        val instance = spkv.getField("INSTANCE").get(null)
        val encode = spkv.getMethod("encode", String::class.java, Int::class.javaPrimitiveType)
        val result = encode.invoke(instance, "lyrics_font_size", size)
        check(result != false) { "字号保存失败" }
        AppConfig.updateLyricsFontSize(size)
    }

    fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (failure: InvocationTargetException) { throw failure.targetException }
}

/** Calls the already-loaded SPW controls so colors, fonts, corners and dialogs follow the host theme. */
object NativeUi {
    private val resolver = HostNames.resolver
    private fun native(semantic: String, count: Int, first: Class<*>? = null) = resolver.composable(semantic, count, first)

    private val text = native("com.moriafly.salt.ui.Text", 21, String::class.java)
    private val annotatedText = native("com.moriafly.salt.ui.Text", 22, androidx.compose.ui.text.AnnotatedString::class.java)
    private val appTheme = native("AppTheme", 5)
    private val edit = native("ItemEdit", 12, String::class.java)
    private val button = native("Button", 12)
    private val buttonAppearances = button.parameterTypes[4].enumConstants
    private val item = native("Item", 17, kotlin.jvm.functions.Function0::class.java)
    private val slider = native("ItemSlider", 17, Float::class.javaPrimitiveType)
    private val switcher = native("Switcher", 6, Boolean::class.javaPrimitiveType)
    private val card = native("Card", 8, Modifier::class.java)
    private val group = native("RoundedColumn", 7, Modifier::class.java)
    private val outerTitle = native("ItemOuterTitle", 5, String::class.java)
    private val valueItem = native("ItemValue", 6, String::class.java)
    private val switchItem = native("ItemSwitcher", 12, Boolean::class.javaPrimitiveType)
    private val checkItem = native("ItemCheck", 9, Boolean::class.javaPrimitiveType)
    private val selectItem = native("ItemSelect", 15)
    private val popupState = resolver.function(resolver.facade("rememberPopupState"), "rememberPopupState") { it.parameterTypes.contentEquals(arrayOf(Composer::class.java)) }
    private val dismissPopup = resolver.function(HostNames.type("com.moriafly.salt.ui.popup.PopupState"), "dismiss") { method ->
        resolver.referencedFields(method).any { it.declaringClass == java.lang.Boolean::class.java && it.name == "FALSE" } ||
            (resolver.integers(method).contains(0) && !resolver.integers(method).contains(1))
    }
    private val flyoutCheck = native("MenuFlyoutItemCheck", 8)
    private val playerMenuItem = native("MenuFlyoutItemCompat", 7, String::class.java)
    private val basicScreen = native("BasicScreen", 12)
    private val screenDefaults = HostNames.type("com.moriafly.salt.ui.screen.BasicScreenDefaults")
    private val backButton = resolver.function(screenDefaults, "BackButton") { it.parameterCount == 6 && it.parameterTypes[0] == kotlin.jvm.functions.Function0::class.java }
    val theme = HostNames.type("com.moriafly.salt.ui.SaltTheme")
    val themeStyles = resolver.getter(theme, "textStyles")
    private val themeColors = resolver.getter(theme, "colors")
    val stylesType = HostNames.type("com.moriafly.salt.ui.SaltTextStyles")
    private val colorsType = HostNames.type("com.moriafly.salt.ui.SaltColors")
    private val colors = listOf("highlight", "text", "subText", "background", "subBackground", "stroke", "error").map { resolver.getter(colorsType, it) }
    private val subStyle = resolver.getter(stylesType, "sub")
    private val background = HostNames.type("androidx.compose.foundation.BackgroundKt").declaredMethods.single { it.name == "background-bw27NRU\$default" }
    private val click = HostNames.type("androidx.compose.foundation.ClickableKt").declaredMethods.single { it.name == "clickable-XHw0xAI" }
    private val rounded = HostNames.type("androidx.compose.foundation.shape.RoundedCornerShapeKt").declaredMethods.single { it.name == "RoundedCornerShape-0680j_4" }
    private val border = HostNames.type("androidx.compose.foundation.BorderKt").declaredMethods.single { it.name == "border-xT4_qwU" }
    private val image = HostNames.type("androidx.compose.foundation.ImageKt").declaredMethods.single { it.name == "Image" && it.parameterCount == 10 && it.parameterTypes[0] == ImageBitmap::class.java }
    private val decision = native("BlurYesNoDialog", 11, kotlin.jvm.functions.Function0::class.java)
    private val popup = native("PopupMenu", 7, PopupPositionProvider::class.java)
    private val scopeType = HostNames.type("com.xuncorp.spc.ui.component.popupmenu.PopupMenuScope")
    private val scrollType = HostNames.type("com.moriafly.salt.ui.ScrollState")
    private val scroll = resolver.facade("rememberScrollState", "verticalScroll")
    private val rememberScroll = resolver.function(scroll, "rememberScrollState") { it.parameterTypes.contentEquals(arrayOf(Composer::class.java)) }
    private val verticalScroll = resolver.function(scroll, "verticalScroll") { it.parameterCount == 4 }
    private val scrollValue = scrollType.declaredMethods.single { it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType && resolver.referencedFields(it) == listOf(resolver.initializedField(scrollType, "initial")) }.apply { isAccessible = true }
    private val scrollDelta = resolver.function(scrollType, "dispatchRawDelta")
    private val menuItem = resolver.function(scopeType, "item")
    private val submenu = resolver.function(scopeType, "submenu")
    private val divider = resolver.function(scopeType, "divider")

    fun validate() { check(text.parameterCount == 21 && popup.parameterCount == 7) }

    @Composable fun AppTheme(content: @Composable () -> Unit) {
        // The playback page forces dark colors; settings must resolve AppConfig again inside the dialog.
        val body: @Composable () -> Unit = { UiTypography.Provide(content) }
        draw(appTheme, currentComposer, 1, 1 to body)
    }

    @Composable fun Text(value: androidx.compose.ui.text.AnnotatedString, modifier: Modifier = Modifier, size: Int = 19) {
        draw(annotatedText, currentComposer, 262132, 0 to value, 1 to modifier, 3 to size.sp.packedValue.toLong())
    }

    private val lazyColumn = HostNames.type("androidx.compose.foundation.lazy.LazyDslKt").methods.single { it.name == "LazyColumn" && it.parameterCount == 13 }
    @Composable fun LazyRows(modifier: Modifier, state: androidx.compose.foundation.lazy.LazyListState, count: Int, row: @Composable (Int) -> Unit) {
        val body: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int) -> Unit = { index -> row(index) }
        val build: (androidx.compose.foundation.lazy.LazyListScope) -> Unit = { scope ->
            androidx.compose.foundation.lazy.LazyListScope::class.java.methods.single { it.name == "items" && it.parameterCount == 4 }.invoke(scope, count, null, { _: Int -> null }, body)
        }
        draw(lazyColumn, currentComposer, 508, 0 to modifier, 1 to state, 9 to build)
    }

    @Composable fun TimeSlider(position: Long, duration: Long, change: (Long) -> Unit) {
        val callback: (Float) -> Unit = { change(it.toLong()) }
        draw(slider, currentComposer, 4336, 0 to position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), 1 to callback,
            2 to LyricsCodec.time(position), 3 to Modifier, 8 to true, 9 to (0f..duration.coerceAtLeast(1).toFloat()), 10 to 0, 11 to {})
    }

    private fun draw(method: Method, composer: Composer, defaults: Int, vararg values: Pair<Int, Any?>) {
        val args: Array<Any?> = Array(method.parameterCount) { index -> when (method.parameterTypes[index]) {
            java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f; java.lang.Boolean.TYPE -> false; else -> null
        } }
        args[method.parameterTypes.indexOf(Composer::class.java)] = composer
        args[args.lastIndex] = defaults
        values.forEach { (index, value) -> args[index] = value }
        NativeHost.invoke(method, null, *args)
    }

    @Composable fun Text(value: String, modifier: Modifier = Modifier, size: Int? = null, color: Color? = null, bold: Boolean = false, singleLine: Boolean = false, systemFont: Boolean = false) {
        var defaults = 131068
        val values = mutableListOf<Pair<Int, Any?>>(0 to value, 1 to modifier)
        if (size != null) { defaults = defaults and 8.inv(); values += 3 to size.sp.packedValue.toLong() }
        if (color != null) { defaults = defaults and 4.inv(); values += 2 to color.value.toLong() }
        if (systemFont) { defaults = defaults and 64.inv(); values += 6 to UiTypography.family }
        if (bold) { defaults = defaults and 32.inv(); values += 5 to FontWeight.SemiBold }
        if (singleLine) { defaults = defaults and (2048 or 4096 or 8192).inv(); values += 11 to 2; values += 12 to false; values += 13 to 1 }
        draw(text, currentComposer, defaults, *values.toTypedArray())
    }
    @Composable fun scrollModifier(modifier: Modifier, scrollTo: Int? = null): Modifier {
        val state = NativeHost.invoke(rememberScroll, null, currentComposer)
        LaunchedEffect(state, scrollTo) {
            if (scrollTo != null) {
                val current = scrollValue.invoke(state) as Int
                scrollDelta.invoke(state, (scrollTo - current).toFloat())
            }
        }
        return NativeHost.invoke(verticalScroll, null, modifier, state, true, 0) as Modifier
    }
    @Composable fun Edit(value: String, hint: String, singleLine: Boolean = true, modifier: Modifier = Modifier, enabled: Boolean = true, change: (String) -> Unit) {
        draw(edit, currentComposer, 192, 0 to value, 1 to change, 2 to modifier,
            3 to enabled, 4 to false, 5 to singleLine, 8 to hint)
    }
    @Composable fun Button(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, primary: Boolean = false, quiet: Boolean = false, click: () -> Unit) {
        val appearance = buttonAppearances.single { (it as Enum<*>).name == if (primary) "Filled" else if (quiet) "Plain" else "Subtle" }
        draw(button, currentComposer, 480, 0 to click, 1 to label, 2 to modifier, 3 to enabled, 4 to appearance)
    }
    @Composable fun Switch(value: Boolean, change: (Boolean) -> Unit) { draw(switcher, currentComposer, 0, 0 to value, 1 to change, 2 to Modifier) }
    @Composable fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = { content() }
        draw(card, currentComposer, 14, 0 to modifier, 4 to body)
    }
    data class Palette(val accent: Color, val text: Color, val muted: Color, val background: Color, val surface: Color, val stroke: Color, val error: Color)
    @Composable fun palette(): Palette {
        val palette = NativeHost.invoke(themeColors, null, currentComposer)!!
        val values = colors.map { Color((it.invoke(palette) as Long).toULong()) }
        return Palette(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
    }
    fun background(modifier: Modifier, color: Color): Modifier = NativeHost.invoke(background, null, modifier, color.value.toLong(), null, 2, null) as Modifier
    fun clickable(modifier: Modifier, role: Role, action: () -> Unit): Modifier = NativeHost.invoke(click, null, modifier, true, null, role, action) as Modifier
    fun rounded(size: Dp): androidx.compose.ui.graphics.Shape = NativeHost.invoke(rounded, null, size.value) as androidx.compose.ui.graphics.Shape
    fun outline(modifier: Modifier, color: Color, radius: Dp): Modifier = NativeHost.invoke(border, null, modifier, 1f, color.value.toLong(), rounded(radius)) as Modifier
    @Composable fun Image(bitmap: ImageBitmap, description: String, modifier: Modifier, contentScale: ContentScale) {
        draw(image, currentComposer, 96, 0 to bitmap, 1 to description, 2 to modifier, 3 to androidx.compose.ui.Alignment.Center, 4 to contentScale)
    }
    @Composable fun Item(label: String, sub: String?, click: () -> Unit) {
        draw(item, currentComposer, 7920, 0 to click, 1 to label, 2 to Modifier, 3 to true, 8 to sub)
    }
    @Composable fun Slider(value: Float, finished: () -> Unit = {}, change: (Float) -> Unit) {
        draw(slider, currentComposer, 4336, 0 to value, 1 to change, 2 to "歌词字号：${value.toInt()}",
            3 to Modifier, 8 to true, 9 to (12f..80f), 10 to 67, 11 to finished)
    }
    @Composable fun Group(title: String = "", modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        androidx.compose.foundation.layout.Column(modifier) {
            if (title.isNotBlank()) draw(outerTitle, currentComposer, 2, 0 to title)
            draw(group, currentComposer, 7, 3 to content)
        }
    }
    @Composable fun Value(label: String, value: String, modifier: Modifier = Modifier) {
        draw(valueItem, currentComposer, 0, 0 to label, 1 to value, 2 to modifier)
    }
    @Composable fun SettingSwitch(label: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
        draw(switchItem, currentComposer, 488, 0 to value, 1 to change, 2 to label, 4 to enabled)
    }
    @Composable fun Check(label: String, checked: Boolean, enabled: Boolean = true, sub: String? = null, click: () -> Unit) {
        val change: (Boolean) -> Unit = { click() }
        draw(checkItem, currentComposer, 8, 0 to checked, 1 to change, 2 to label, 4 to enabled, 5 to sub)
    }
    @Composable fun <T> Select(label: String, value: T, values: List<T>, text: (T) -> String, change: (T) -> Unit) {
        val state = NativeHost.invoke(popupState, null, currentComposer)!!
        val menu: @Composable (Any) -> Unit = { scope ->
            values.forEach { item ->
                val select: (Boolean) -> Unit = { dismissPopup.invoke(state); change(item) }
                draw(flyoutCheck, currentComposer, 0, 0 to scope, 1 to select, 2 to (value == item), 3 to text(item), 4 to true)
            }
        }
        draw(selectItem, currentComposer, 1008, 0 to state, 1 to label, 2 to text(value), 3 to true, 10 to menu)
    }
    @Composable fun Screen(title: String, subtitle: String, back: () -> Unit, tools: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
        val action: @Composable () -> Unit = {
            NativeHost.invoke(backButton, resolver.receiver(backButton), back, Modifier, true, currentComposer, 0, 2)
        }
        val body: @Composable androidx.compose.foundation.layout.BoxScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) { content() }
        }
        draw(basicScreen, currentComposer, 224, 0 to action, 1 to Modifier.fillMaxSize().background(palette().background), 2 to title, 3 to subtitle, 4 to tools, 8 to body)
    }
    @Composable fun Caption(value: String, modifier: Modifier = Modifier) {
        val styles = NativeHost.invoke(themeStyles, null, currentComposer)!!
        val style = subStyle.invoke(styles)
        draw(text, currentComposer, 65528, 0 to value, 1 to modifier, 2 to palette().muted.value.toLong(), 16 to style)
    }
    @Composable fun Dialog(title: String, description: String, confirm: String, dismiss: () -> Unit,
        apply: () -> Unit, content: @Composable () -> Unit) {
        androidx.compose.ui.window.Popup(onDismissRequest = dismiss, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(Role.Button) { dismiss() }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                Group(modifier = Modifier.width(440.dp).clip(rounded(6.dp)).background(palette().background.copy(alpha = 1f)).clickable(Role.Button) { }) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, size = 18, bold = true)
                        Text(description, size = 14)
                        content()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button("取消", quiet = true, click = dismiss)
                            Spacer(Modifier.width(8.dp))
                            Button(confirm, primary = true, click = apply)
                        }
                    }
                }
            }
        }
    }
    @Composable fun Menu(position: PopupPositionProvider, dismiss: () -> Unit, build: (MenuBuilder) -> Unit) {
        val builder: (Any) -> Unit = { build(MenuBuilder(it)) }
        draw(popup, currentComposer, 4, 0 to position, 1 to dismiss, 3 to builder)
    }
    @Composable fun TagIcon() {
        val color = palette().text
        Box(Modifier.fillMaxSize().drawBehind {
            val unit = size.minDimension / 24f
            val outline = Path().apply {
                moveTo(3 * unit, 3 * unit); lineTo(11 * unit, 3 * unit)
                lineTo(21 * unit, 13 * unit); lineTo(13 * unit, 21 * unit)
                lineTo(3 * unit, 11 * unit); close()
            }
            drawPath(outline, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round))
            drawCircle(color, 1.4f * unit, Offset(7 * unit, 7 * unit))
        })
    }
    class MenuBuilder(private val scope: Any) {
        fun content(body: @Composable () -> Unit) { NativeHost.invoke(menuItem, scope, {}, true, body) }
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) {
            val content: @Composable () -> Unit = { Text(label) }
            NativeHost.invoke(menuItem, scope, action, enabled, content)
        }
        fun playerItem(label: String, icon: @Composable () -> Unit, action: () -> Unit) {
            val content: @Composable () -> Unit = { draw(playerMenuItem, currentComposer, 10, 0 to label, 2 to icon) }
            NativeHost.invoke(menuItem, scope, action, true, content)
        }
        fun submenu(label: String, build: (MenuBuilder) -> Unit) {
            val content: @Composable () -> Unit = { Text(label) }
            val builder: (Any) -> Unit = { build(MenuBuilder(it)) }
            NativeHost.invoke(submenu, scope, builder, true, content)
        }
        fun divider() { divider.invoke(scope) }
    }
}

fun Modifier.background(color: Color): Modifier = NativeUi.background(this, color)
fun Modifier.clickable(role: Role, action: () -> Unit): Modifier = NativeUi.clickable(this, role, action)
fun roundShape(size: Dp) = NativeUi.rounded(size)
fun Modifier.outline(color: Color, radius: Dp) = NativeUi.outline(this, color, radius)
@Composable fun Image(bitmap: ImageBitmap, description: String, modifier: Modifier, contentScale: ContentScale) = NativeUi.Image(bitmap, description, modifier, contentScale)
