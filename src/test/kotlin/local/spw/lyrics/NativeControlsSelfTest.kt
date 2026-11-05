@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import javax.swing.SwingUtilities
import java.nio.file.Files
import java.nio.file.Path

private fun testPlatform(width: Int, height: Int, owners: MutableList<SemanticsOwner> = mutableListOf()): PlatformContext = object : PlatformContext {
    override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
        override fun onSemanticsOwnerAppended(owner: SemanticsOwner) { owners += owner }
        override fun onSemanticsOwnerRemoved(owner: SemanticsOwner) { owners -= owner }
        override fun onSemanticsChange(owner: SemanticsOwner) {}
        override fun onLayoutChange(owner: SemanticsOwner, semanticsNodeId: Int) {}
    }
    override val windowInfo = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize = IntSize(width, height)
    }
    override val inputModeManager = object : InputModeManager {
        override val inputMode = InputMode.Keyboard
        override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
    }
}

/** Draw native controls in an isolated scene; never opens, installs into, or controls SPW. */
fun checkNativeControls() {
    SwingUtilities.invokeAndWait {
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(640, 600))
        val surface = Surface.makeRasterN32Premul(640, 600)
        try {
            scene.setContent { nativeTheme {
                Column(NativeUi.scrollModifier(Modifier.fillMaxSize())) {
                    NativeUi.Text("Better Tag for SPW")
                    NativeUi.Edit("晴天", "歌名") { }
                    NativeUi.Button("搜索") { }
                    NativeUi.Item("晴天", "周杰伦 · 网易云 · 4:29") { }
                    NativeUi.Slider(32f) { }
                }
            } }
            repeat(3) {
                frame.performFrame((it + 1L) * 16_000_000)
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }
            check(surface.makeImageSnapshot().width == 640)
        } finally { scene.close(); frame.close(); surface.close() }
    }
    println("PASS: host theme, text input, button, result row, font slider and scroll area rendered without extra UI libraries.")
}

private object EntryControlFixture {
    var clicks = 0
    @JvmStatic @Composable fun Controls(modifier: Modifier = Modifier) {
        Row(modifier.width(180.dp).height(42.dp)) {
            NativeUi.Button("上一首", modifier = Modifier.width(60.dp), quiet = true) { clicks++ }
            NativeUi.Button("播放", modifier = Modifier.width(60.dp), quiet = true) { clicks++ }
            NativeUi.Button("下一首", modifier = Modifier.width(60.dp), quiet = true) { clicks++ }
        }
    }
    @JvmStatic @Composable fun Lyrics(modifier: Modifier, unused: Boolean = false) { Box(modifier) }
}

@OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
fun checkEntryControls() {
    SwingUtilities.invokeAndWait {
        val root = Files.createTempDirectory(Path.of("build"), "entry-ui-")
        val plugin = LyricsPlugin(com.xuncorp.spw.workshop.api.PluginContext("test", "0.6.7", root.toString(), "1.19.2", com.xuncorp.spw.workshop.api.Channel.Steam))
        plugin.enabled.value = true
        plugin.entry.value = LyricsEntry.CONTEXT
        val original = EntryControlFixture::class.java.getDeclaredMethod("Controls", Modifier::class.java, Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val controls = LyricsControlsHook(plugin, original)
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val owners = mutableListOf<SemanticsOwner>()
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(640, 640), platformContext = testPlatform(640, 640, owners))
        val surface = Surface.makeRasterN32Premul(640, 640)
        var width = 0
        val call = object : com.xuncorp.spw.workshop.api.hook.UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> = listOf(Modifier.onSizeChanged { width = it.width })
            override fun getArgument(name: String): Any? = null
        }
        var number = 0L
        fun draw() { repeat(5) { Snapshot.sendApplyNotifications(); frame.performFrame(++number * 32_000_000); scene.measureAndLayout(); surface.canvas.clear(0xfff7f7f7.toInt()); scene.draw(surface.canvas.asComposeCanvas()) } }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), buttons = PointerButtons(), button = PointerButton.Primary); draw()
        }
        try {
            scene.setContent { nativeTheme { NativeUi.AppTheme { Column { controls.Content(call) } } } }
            draw(); check(width == 180) { "Context mode changed the original playback control group" }
            plugin.entry.value = LyricsEntry.CONTROLS; draw(); check(width == 180) { "More-menu mode added a playback button" }
            EntryControlFixture.clicks = 0; click(90f, 21f); check(EntryControlFixture.clicks == 1) { "Playback entry intercepted an original button" }
            val layers = scene.javaClass.getDeclaredField("layers").apply { isAccessible = true }
            check((layers.get(scene) as List<*>).isEmpty()) { "Playback controls opened the removed entry" }
            plugin.entry.value = LyricsEntry.CONTEXT; draw()
            check(width == 180 && (layers.get(scene) as List<*>).isEmpty()) { "Switching entry left the old playback entry/menu active" }
            plugin.entry.value = LyricsEntry.CONTROLS
            var moreOpened = 0
            val menuHook = LyricsMoreMenuHook(plugin) { moreOpened++ }
            val nativeRow = HostNames.resolver.composable("MenuFlyoutItemCompat", 7, String::class.java)
            scene.setContent { nativeTheme {
                NativeUi.Menu(CursorMenuPosition(Offset(100f, 20f)), {}) { builder ->
                    listOf("播放速度", "桌面歌词", "歌词翻译", "释放音频引擎", "手动搜索歌词", "编辑元数据").forEach { label ->
                        val icon: (@Composable () -> Unit)? = if (label == "桌面歌词") null else { {} }
                        builder.content { NativeHost.invoke(nativeRow, null, label, null, icon, false, currentComposer, 0, if (icon == null) 14 else 10) }
                    }
                    val menuScope = builder.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(builder)
                    menuHook.append(menuScope)
                }
            } }
            Files.createDirectories(Path.of("build/previews"))
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun textNode(label: String) = owners.flatMap { nodes(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
            }
            for ((index, scale) in listOf(1f, 1.5f, 2f).withIndex()) {
                scene.density = Density(scale); draw()
                val reference = textNode("播放速度").boundsInWindow
                val added = textNode("标签快捷编辑").boundsInWindow
                check(kotlin.math.abs(reference.left - added.left) < 0.5f && kotlin.math.abs(reference.height - added.height) < 0.5f) {
                    "More-menu label alignment/font differs at density $scale: $reference / $added"
                }
                surface.makeImageSnapshot().use { bitmap -> bitmap.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use {
                    Files.write(Path.of("build/previews/playback-more-menu-$scale.png"), it.bytes)
                    val rendered = javax.imageio.ImageIO.read(it.bytes.inputStream())
                    val iconLeft = (added.left - 29.5f * scale).toInt()
                    val iconRight = (added.left - 10.5f * scale).toInt()
                    val ink = (iconLeft until iconRight).sumOf { x ->
                        (added.top.toInt() until added.bottom.toInt()).count { y -> java.awt.Color(rendered.getRGB(x, y)).red < 150 }
                    }
                    check(ink > 20 * scale * scale) { "Tag icon is missing or clipped at density $scale" }
                } }
                click(added.center.x, added.center.y)
                check(moreOpened == index + 1) { "Native More-menu editor row did not respond at density $scale" }
            }
            scene.density = Density(1f)
            println("PASS: native More-menu labels, typography and clicks match at 100%, 150% and 200% scale.")
            scene.setContent { nativeTheme {
                LyricsContextMenu(CursorMenuPosition(Offset(100f, 20f)), {}, {}, {}, {}, {})
            } }
            draw()
            val labels = owners.flatMap { nodes(it.unmergedRootSemanticsNode) }.flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
            }
            check("歌词翻译" !in labels) { "Removed translation shortcut is still visible" }
            check(listOf("搜索歌词…", "后台整理…", "字号", "字重", "偏移", "恢复本曲原歌词").all { it in labels }) { "Removing the shortcut affected another menu control" }
            println("PASS: context translation shortcut is absent; original entries, typography and timing controls remain.")
            val lyricMethod = EntryControlFixture::class.java.getDeclaredMethod("Lyrics", Modifier::class.java, Boolean::class.javaPrimitiveType, Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val lyrics = LyricsHook(plugin, lyricMethod)
            val lyricCall = object : com.xuncorp.spw.workshop.api.hook.UiHookCall() {
                override val thisObject: Any? = null
                override val arguments: List<Any?> = listOf(Modifier, false)
                override fun getArgument(name: String): Any? = null
            }
            var consumed: Boolean? = null
            plugin.entry.value = LyricsEntry.CONTROLS
            scene.setContent { nativeTheme { Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press, PointerEventPass.Final) { event ->
                if (event.buttons.isSecondaryPressed) consumed = event.changes.any { it.isConsumed }
            }) { lyrics.Content(lyricCall) } } }
            draw()
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 80f), buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 80f), buttons = PointerButtons(), button = PointerButton.Secondary); draw()
            check(consumed == false && (layers.get(scene) as List<*>).isEmpty()) { "Control mode still consumed lyric right-clicks" }
        } finally { plugin.enabled.value = false; scene.close(); frame.close(); surface.close() }
    }
    println("PASS: playback controls keep their original width/actions with no extra entry, and more-menu mode passes lyric right-clicks through.")
}

@Composable private fun nativeTheme(content: @Composable () -> Unit) {
    val method = remember { HostNames.resolver.composable("AppTheme", 5, null) }
    val hazeLocals = remember {
        val type = HostNames.resolver.facade("BlurYesNoDialog", "LocalWallpaperHazeState", "LocalPopupHazeState")
        type.declaredFields.filter { it.type == ProvidableCompositionLocal::class.java }.also { check(it.size == 2) }.map { field ->
            @Suppress("UNCHECKED_CAST")
            (field.apply { isAccessible = true }.get(null) as ProvidableCompositionLocal<Any>).provides(dev.chrisbanes.haze.HazeState())
        }.toTypedArray()
    }
    val body: @Composable () -> Unit = { CompositionLocalProvider(*hazeLocals) { content() } }
    NativeHost.invoke(method, null, false, body, currentComposer, 0, 0)
}

fun checkStudioScreens(interactions: Boolean = true) {
    val output = Path.of("build/previews")
    Files.createDirectories(output)
    val coverBytes = sampleCover().bytes
    val coverServer = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val coverRequests = java.util.concurrent.atomic.AtomicInteger()
    val coverServing = Thread.ofVirtual().start {
        try { while (!coverServer.isClosed) {
            val socket = coverServer.accept()
            Thread.ofVirtual().start { socket.use {
                try {
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine(); while (!input.readLine().isNullOrEmpty()) { }
                    coverRequests.incrementAndGet()
                    socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${coverBytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(coverBytes); flush() }
                } catch (_: Exception) { }
            } }
        } } catch (_: java.net.SocketException) { }
    }
    try {
    SwingUtilities.invokeAndWait {
        val themeProperty = com.xuncorp.voxzen.util.AppConfig::class.java.getDeclaredField("lightDarkTheme\$delegate").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST") val themeState = themeProperty.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(themeProperty) as MutableState<Any>
        val oldTheme = themeState.value
        fun setTheme(dark: Boolean) { themeState.value = Class.forName("com.xuncorp.voxzen.ui.theme.LightDarkTheme").enumConstants.single { (it as Enum<*>).name == if (dark) "Dark" else "Light" } }
        fun render(dark: Boolean, width: Int, page: StudioPage, tab: Int = 0, height: Int = 790, scale: Float = 1f) {
            setTheme(dark)
            var renderedDark: Boolean? = null
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val frame = FrameRecomposer(Dispatchers.Unconfined + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> failures.add(error) })
            val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(scale), size = IntSize(width, height), platformContext = testPlatform(width, height))
            val surface = checkNotNull(Surface.makeRasterN32Premul(width, height))
            val sample = LyricsCodec.ttml(LyricsDocument(listOf("晚风经过长街", "灯火映在窗前", "把今天轻轻折好", "寄给明天的自己", "沿着熟悉的小路", "听见远处的声音", "每个平凡的瞬间", "都值得慢慢记起").mapIndexed { index, text ->
                val begin = 1000L + index * 6000L
                LyricRow(begin, begin + 4500, text.mapIndexed { i, char -> LyricWord(begin + i * 500L, begin + (i + 1) * 500L, char.toString()) },
                    if (index == 0) "The evening breeze passes through the street" else "", if (index == 0) "wan feng jing guo chang jie" else "")
            }, "TTML"))
            val saved = SavedLyrics(sample, "AMLL", chinese = ChineseMode.SIMPLIFIED)
            val state = StudioState(com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem("晚风", "本地音乐", "旅途", "", "sample.flac", id = "sample", duration = 258000), saved, sample, page)
            var autoOpened = false
            state.openAuto = { autoOpened = true; state.enterAuto() }
            state.document = LyricsCodec.parse(sample)
            state.embedded = SavedLyrics(sample, "文件内嵌歌词"); state.embeddedLoading = false
            state.position = 1800
            state.timingPlayback = TestTimingPlayback().apply { value = TimingClock(1800, true) }
            state.existingCover = androidx.compose.ui.res.loadImageBitmap(sampleCover().bytes.inputStream())
            state.editorTab = tab
            state.coverTab = tab
            if (page == StudioPage.DISPLAY && tab == 2) state.updatePreferences(state.preferences.copy(interludeCountdown = true))
            state.results = listOf(
                Candidate(LyricSource.AMLL, "1", "晚风", "本地音乐", "旅途", 258000, loaded = DownloadedLyrics(sample, "AMLL")),
                Candidate(LyricSource.NETEASE, "2", "晚风", "本地音乐", "旅途", 258000),
                Candidate(LyricSource.QQ, "3", "晚风（Live）", "本地音乐", "现场", 300000))
            if (page == StudioPage.SEARCH && tab == 0) state.results = state.results + (4..18).map {
                Candidate(LyricSource.entries[it % LyricSource.entries.size], "$it", if (it % 3 == 0) "晚风 (Live)" else "晚风", "本地音乐", if (it % 3 == 0) "现场" else "旅途", 258000L + it * 500)
            }
            state.tagOriginal = MusicTags(mapOf(MusicTag.TITLE to "晚风", MusicTag.ARTIST to "本地音乐", MusicTag.ALBUM to "旅途"))
            state.tagValues = state.tagOriginal!!.values + (MusicTag.YEAR to "2026")
            state.tagFields = state.tagValues.keys
            state.tagCover = sampleCover()
            state.tagResults = state.results; state.tagSelected = state.results.first()
            state.tagChoicesExpanded = tab == 1
            state.selected = state.results.first()
            state.downloaded = DownloadedLyrics(sample, "AMLL")
            state.previewCurrent = false
            if (page == StudioPage.SEARCH && tab == 1) { state.results = emptyList(); state.downloaded = null; state.selected = null; state.previewCurrent = true; state.pending = 3 }
            if (page == StudioPage.SEARCH && tab == 2) { state.updatePreferences(state.preferences.copy(format = LyricsFormat.WORD)); state.results = listOf(state.results.first()) }
            var coverSearches = 0
            if (page == StudioPage.COVER) {
                check(state.coverQuery == "晚风" && state.coverArtist == "本地音乐") { "Cover search defaulted to the album" }
                state.picture = sampleCover()
                state.covers = (1..80).map { CoverCandidate(if (it == 1) "晚风" else "晚风（版本 $it）", "本地音乐", "http://127.0.0.1:${coverServer.localPort}/$it.png", CoverSource.entries[it % 4].label, "旅途", 258000) }
                state.selectedCoverUrl = state.covers.first().url
            }
            if (page == StudioPage.SEARCH && tab == 5) state.previewTiming = true
            if (page == StudioPage.DISPLAY && tab >= 3) {
                state.updatePreferences(state.preferences.copy(autoTags = AutoTagSettings(enabled = tab != 6)))
                state.autoProgress = AutoTagProgress("正在搜索", running = true, total = 238, processed = 42, updated = 8, protected = 19, skipped = 15, song = "晴天 · 周杰伦", path = "D:\\音乐\\周杰伦\\叶惠美\\晴天.flac")
                if (tab == 6) state.autoProgress = AutoTagProgress()
                if (tab == 7) state.autoProgress = AutoTagProgress("已完成 · 等待新歌曲", total = 238, processed = 238, updated = 48, protected = 105, skipped = 85, completedAt = System.currentTimeMillis())
                if (tab == 8) state.autoProgress = AutoTagProgress("扫描未完成", message = "请在插件管理中授权媒体库写入")
                if (tab == 9) state.autoProgress = state.autoProgress.copy(paused = true)
                state.pauseAuto = { state.autoProgress = state.autoProgress.copy(paused = !state.autoProgress.paused) }
            }
            var scansRequested = 0
            state.scanAuto = { scansRequested++ }
            var dialogVisible by mutableStateOf(true)
            var frameNumber = 0L
            fun drawFrames() { repeat(5) {
                val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
                java.awt.EventQueue.invokeLater { loop.exit() }
                loop.enter()
                Snapshot.sendApplyNotifications(); frame.performFrame(++frameNumber * 32_000_000); scene.measureAndLayout()
                surface.canvas.clear(0); scene.draw(surface.canvas.asComposeCanvas())
            } }
            fun click(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                drawFrames()
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), buttons = PointerButtons(), button = PointerButton.Primary)
                drawFrames()
            }
            fun type(value: String) {
                val key = scene.javaClass.getMethod("sendKeyEvent-ZmokQxo", Any::class.java)
                val source = java.awt.Canvas()
                fun send(id: Int, code: Int, char: Char, modifiers: Int = 0) {
                    val native = java.awt.event.KeyEvent(source, id, System.currentTimeMillis(), modifiers, code, char)
                    val compose = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java).invoke(null, native)
                    key.invoke(scene, compose)
                }
                send(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.VK_END, Char.MAX_VALUE)
                send(java.awt.event.KeyEvent.KEY_RELEASED, java.awt.event.KeyEvent.VK_END, Char.MAX_VALUE)
                repeat(12) {
                    send(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.VK_BACK_SPACE, '\b')
                    send(java.awt.event.KeyEvent.KEY_RELEASED, java.awt.event.KeyEvent.VK_BACK_SPACE, '\b')
                    send(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.VK_DELETE, Char.MAX_VALUE)
                    send(java.awt.event.KeyEvent.KEY_RELEASED, java.awt.event.KeyEvent.VK_DELETE, Char.MAX_VALUE)
                }
                value.forEach {
                    val code = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(it.code)
                    send(java.awt.event.KeyEvent.KEY_PRESSED, code, it)
                    send(java.awt.event.KeyEvent.KEY_TYPED, 0, it)
                    send(java.awt.event.KeyEvent.KEY_RELEASED, code, it)
                }
                drawFrames()
            }
            fun saveImage(name: String) {
                val image = surface.makeImageSnapshot()
                try { val data = checkNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG))
                    try { check(data.size > 5000); Files.write(output.resolve(name), data.bytes) } finally { data.close() }
                } finally { image.close() }
            }
            val timingWindowKeys = TimingWindowKeys(null, state)
            fun functionKey(code: Int, down: Boolean) {
                val native = java.awt.event.KeyEvent(java.awt.Canvas(), if (down) java.awt.event.KeyEvent.KEY_PRESSED else java.awt.event.KeyEvent.KEY_RELEASED,
                    System.currentTimeMillis(), 0, code, Char.MAX_VALUE)
                if (!timingWindowKeys.handle(native)) {
                    val compose = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java).invoke(null, native)
                    scene.javaClass.getMethod("sendKeyEvent-ZmokQxo", Any::class.java).invoke(scene, compose)
                }
                drawFrames()
            }
            fun clockTick() {
                val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
                javax.swing.Timer(80) { loop.exit() }.apply { isRepeats = false; start() }
                loop.enter(); drawFrames()
            }
            try {
                scene.setContent { if (page == StudioPage.DISPLAY && tab >= 3) StandaloneLyricsTheme {
                    val background = NativeUi.palette().background
                    SideEffect { renderedDark = background.luminance() < 0.5f }
                    AutoTagsScreen(state.preferences.autoTags, state.autoProgress, { state.updatePreferences(state.preferences.copy(autoTags = it)) }, state.scanAuto, state.pauseAuto, {})
                } else nativeTheme {
                    val themedContent: @Composable () -> Unit = { NativeUi.AppTheme {
                        val background = NativeUi.palette().background
                        SideEffect { renderedDark = background.luminance() < 0.5f }
                        StudioBody(state, { _, change -> state.committed(change(state.saved), change) }, {}, {}, { coverSearches++ }, {}, {}, {}, {}, {}, {}, {})
                        if (dialogVisible && tab == 4 && page == StudioPage.SEARCH) NativeUi.Dialog("放弃更改？", "当前内容有未保存的编辑。", "放弃更改", { dialogVisible = false }, { dialogVisible = false }) { }
                        if (tab == 3 && page == StudioPage.SEARCH) LyricsContextMenu(object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: IntSize) = androidx.compose.ui.unit.IntOffset(20, 20)
                        }, {}, { if (it == StudioPage.AUTO) autoOpened = true; state.page = it }, { change -> state.saved = change(state.saved) }, {}, { error -> failures.add(IllegalStateException(error)) }, state.saved.offset)
                    }
                    }
                    val theme = HostNames.resolver.composable("ChangeSaltThemeIsDark", 4, Boolean::class.javaPrimitiveType)
                    NativeHost.invoke(theme, null, !dark, themedContent, currentComposer, 0)
                } }
                drawFrames()
                if (page == StudioPage.COVER && tab == 0) {
                    check(coverSearches == 1) { "Opening cover search did not search automatically" }
                    check(coverRequests.get() < 80) { "All cover thumbnails were loaded eagerly" }
                }
                check(renderedDark == dark) { "The dialog inherited the playback theme instead of SPW settings" }
                saveImage("${if (dark) "dark" else "light"}-${page.name.lowercase()}-$width-$tab.png")
                check(failures.isEmpty()) { "Studio composition failed: ${failures.firstOrNull()}" }
                if (!interactions || scale != 1f) return
                if (!dark && width == 1080 && page == StudioPage.EDIT && tab == 1) {
                    val playback = state.timingPlayback as TestTimingPlayback
                    val initial = state.draft
                    click(830f, 503f)
                    check(state.timing.started == 1800L && state.draft == initial) { "Mouse start did not read the raw clock" }
                    playback.value = playback.value.copy(position = 2500)
                    click(830f, 503f)
                    check(state.line == 1 && state.document!!.rows[0].start == 1800L && state.document!!.rows[0].end == 2500L)
                    check(state.timing.started == 2500L) { "Mouse next-line action did not continue at the exact boundary" }
                    playback.value = playback.value.copy(position = 3100)
                    click(990f, 503f)
                    check(state.timing.started == null && state.document!!.rows[1].end == 3100L && playback.value.playing) { "Mouse finish did not leave an interlude gap with audio playing" }
                    saveImage("timing-interlude-finished.png")
                    click(660f, 540f)
                    check(state.line == 1 && state.timing.started == 2500L) { "Undo after finish: line=${state.line}, start=${state.timing.started}, error=${state.error}" }
                    click(660f, 540f)
                    check(state.line == 0 && state.draft == initial && !state.hasUnsavedLyrics())
                    check(state.timing.started == 1800L)
                    functionKey(java.awt.event.KeyEvent.VK_F8, false)
                    check(state.timing.started == 1800L) { "An orphan F8 release ended a continuous capture" }
                    click(140f, 200f)
                    check(playback.value.position == 0L && state.timingClock.position == 0L && playback.value.playing) { "Lyric row click did not seek the transport to its lead-in" }
                    playback.value = playback.value.copy(position = 2800)
                    functionKey(java.awt.event.KeyEvent.VK_F8, true)
                    functionKey(java.awt.event.KeyEvent.VK_F8, true)
                    check(state.timing.started == 2800L) { "F8/repeat did not start a single capture" }
                    playback.value = playback.value.copy(position = 3300)
                    functionKey(java.awt.event.KeyEvent.VK_F8, false)
                    check(state.line == 1 && state.document!!.rows[0].end == 3300L)
                    functionKey(java.awt.event.KeyEvent.VK_F7, true); functionKey(java.awt.event.KeyEvent.VK_F7, false)
                    check(state.draft == initial && !state.hasUnsavedLyrics())
                    functionKey(java.awt.event.KeyEvent.VK_F5, true); functionKey(java.awt.event.KeyEvent.VK_F5, false)
                    check(!playback.value.playing)
                    functionKey(java.awt.event.KeyEvent.VK_F8, true); functionKey(java.awt.event.KeyEvent.VK_F8, false)
                    check(state.timing.started == null && state.draft == initial)
                    functionKey(java.awt.event.KeyEvent.VK_F6, true); functionKey(java.awt.event.KeyEvent.VK_F6, false)
                    check(playback.value.playing && playback.value.position == 0L)
                    playback.value = playback.value.copy(position = 6000); clockTick()
                    check(!playback.value.playing) { "Single-line replay did not stop at the end" }
                    click(280f, 678f)
                    check(state.timingLoop && playback.value.playing)
                    playback.value = playback.value.copy(position = 6000); clockTick()
                    check(playback.value.position == 0L && playback.value.playing) { "Line loop did not return to its lead-in" }
                    state.timingLoop = false; state.timingStopAt = null; drawFrames()
                    click(1000f, 172f)
                    val modeLayers = scene.javaClass.getDeclaredField("layers").apply { isAccessible = true }.get(scene) as List<*>
                    val modePopup = modeLayers.single()!!
                    val modeBounds = modePopup.javaClass.getDeclaredMethod("getBoundsInWindow").apply { isAccessible = true }.invoke(modePopup) as androidx.compose.ui.unit.IntRect
                    click((modeBounds.left + modeBounds.right) / 2f, modeBounds.bottom - 20f)
                    check(state.timing.mode == TimingMode.WORD)
                    click(140f, 200f)
                    playback.value = playback.value.copy(position = 7000)
                    functionKey(java.awt.event.KeyEvent.VK_F8, true)
                    playback.value = playback.value.copy(position = 7200)
                    functionKey(java.awt.event.KeyEvent.VK_F8, false)
                    saveImage("word-timing-captured.png")
                    check(state.line == 0 && state.timing.word == 1 && state.document!!.rows[0].words[0].start == 7000L) { "Word UI: line=${state.line}, word=${state.timing.word}, mode=${state.timing.mode}, started=${state.timing.started}, first=${state.document!!.rows[0].words[0]}, error=${state.error}, playing=${playback.value.playing}" }
                    functionKey(java.awt.event.KeyEvent.VK_F7, true); functionKey(java.awt.event.KeyEvent.VK_F7, false)
                    clockTick()
                    saveImage("word-timing-editor.png")
                    check(state.draft == initial)
                    val originalRow = state.document!!.rows.first()
                    click(980f, 444f)
                    check(state.document!!.rows.first().words.first().text == "晚风") { "Visible word grouping did not merge selected words" }
                    functionKey(java.awt.event.KeyEvent.VK_BACK_SPACE, true); functionKey(java.awt.event.KeyEvent.VK_BACK_SPACE, false)
                    check(state.document!!.rows.first() == originalRow && !state.hasUnsavedLyrics())
                    playback.value = playback.value.copy(position = 8000)
                    functionKey(java.awt.event.KeyEvent.VK_SPACE, true); functionKey(java.awt.event.KeyEvent.VK_SPACE, false)
                    playback.value = playback.value.copy(position = 8500)
                    functionKey(java.awt.event.KeyEvent.VK_SPACE, true); functionKey(java.awt.event.KeyEvent.VK_SPACE, false)
                    check(state.timing.word == 1 && state.timing.started == 8500L && state.document!!.rows.first().words.first().start == 8000L)
                    functionKey(java.awt.event.KeyEvent.VK_BACK_SPACE, true); functionKey(java.awt.event.KeyEvent.VK_BACK_SPACE, false)
                    check(!state.hasUnsavedLyrics() && state.timing.started == 8000L) { "Continuous undo did not restore the original start without changing lyrics" }
                    println("PASS: native next-line/finish buttons, interlude gap with audio playing, continuous undo, held F8 with key-repeat/orphan release, F5 pause, F6 replay, F7 undo, loop and untouched source content.")
                }
                if (!dark && width == 1080 && page == StudioPage.DISPLAY && tab == 3) {
                    click(926f, 204f)
                    check(state.autoProgress.paused) { "Background pause button did not pause" }
                    click(926f, 204f)
                    check(!state.autoProgress.paused) { "Background continue button did not resume" }
                    click(1010f, 86f)
                    check(!state.preferences.autoTags.enabled) { "Background switch did not disable" }
                    click(1010f, 86f)
                    check(state.preferences.autoTags.enabled) { "Background switch did not enable" }
                    click(500f, 536f); saveImage("background-fields-expanded.png")
                    click(500f, 536f)
                    click(500f, 598f); saveImage("background-sources-expanded.png")
                    println("PASS: native background scan switch and pause/continue controls respond directly.")
                }
                if (!dark && page == StudioPage.DISPLAY && tab == 7) {
                    click(990f, 204f)
                    check(scansRequested == 1) { "Scan-now button did not request a new pass after completion" }
                }
                if (!dark && width == 1080 && page == StudioPage.TAGS && tab == 0) {
                    click(1008f, 276f)
                    check(autoOpened) { "Music tags background entry did not open its monitor" }
                    saveImage("interaction-auto-entry.png")
                    click(30f, 28f)
                    check(state.page == StudioPage.TAGS)
                }
                if (!dark && page == StudioPage.SEARCH && tab == 4) {
                    click(610f, 433f)
                    check(!dialogVisible) { "Unsaved-dialog cancel did not dismiss the modal" }
                }
                if (!dark && page == StudioPage.SEARCH && tab == 5) {
                    click(948f, 712f)
                    check(state.previewOffset == 100L && state.saved.offset == 0L) { "Preview calibration changed the applied lyric before confirmation" }
                    click(1015f, 665f)
                    check(state.saved.offset == 100L)
                    check(LyricsCodec.render(LyricsCodec.source(state.saved, sample), state.saved).rows.first().start == 1100L) { "Applied calibration shifted the timestamp twice" }
                    println("PASS: inline preview calibration waits for Apply; saved line/word offsets change exactly once.")
                }
                if (!dark && tab == 3 && page == StudioPage.SEARCH) {
                    val oldSize = NativeHost.fontSize(); val oldWeight = NativeHost.fontWeight(); val oldAlignment = NativeHost.alignment()
                    try {
                        click(299f, 173f)
                        check(NativeHost.fontSize() == oldSize + 1) { "Inline menu size adjustment did not persist" }
                        click(299f, 209f)
                        check(NativeHost.fontWeight() == 500) { "Inline menu weight adjustment did not persist" }
                        click(258f, 245f)
                        check(NativeHost.alignment() == 0) { "Inline menu alignment adjustment did not persist" }
                        val layers = scene.javaClass.getDeclaredField("layers").apply { isAccessible = true }.get(scene) as List<*>
                        check(layers.isNotEmpty()) { "Inline typography unexpectedly opened a new page or closed its menu" }
                        saveImage("inline-typography-adjusted.png")
                        click(224f, 293f); type("-275")
                        val editLoop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
                        javax.swing.Timer(500) { editLoop.exit() }.apply { isRepeats = false; start() }
                        editLoop.enter(); drawFrames()
                        check(state.saved.offset == -275L) { "Typed custom timing value was not applied" }
                        click(297f, 293f)
                        check(state.saved.offset == -175L) { "Increment was not the fixed 100 ms: ${state.saved.offset}" }
                        click(170f, 293f)
                        check(state.saved.offset == -275L)
                        check((scene.javaClass.getDeclaredField("layers").apply { isAccessible = true }.get(scene) as List<*>).isNotEmpty())
                        saveImage("inline-custom-timing.png")
                        click(120f, 132f)
                        check(autoOpened) { "Lyrics context background entry did not respond" }
                    } finally { NativeHost.fontSize(oldSize); NativeHost.fontWeight(oldWeight); NativeHost.alignment(oldAlignment) }
                    println("PASS: centered native timing input saves automatically; fixed 100 ms controls work without unit/apply/reset row while menu stays open.")
                }
                if (!dark && width == 1080 && page == StudioPage.SEARCH && tab == 2) {
                    state.position = 32000; drawFrames()
                    saveImage("word-timeline-following.png")
                    check(activeLyricRow(checkNotNull(state.document), state.position) == 5)
                    click(894f, 28f)
                    check(state.page == StudioPage.DISPLAY) { "Search settings entry did not open the dedicated settings menu" }
                    click(30f, 28f)
                    check(state.page == StudioPage.SEARCH) { "Settings back did not return to search" }
                    check(state.preferences.format == LyricsFormat.WORD)
                    click(941f, 715f)
                    check(state.page == StudioPage.EDIT && !state.hasUnsavedLyrics()) { "Opening a result created a false unsaved edit" }
                    check(state.flushEditor?.invoke() != false && !state.hasUnsavedLyrics())
                    click(30f, 28f)
                    check(state.page == StudioPage.SEARCH && state.selected != null && !state.hasUnsavedLyrics()) { "Editor back lost the search or prompted on untouched content" }
                    click(987f, 589f)
                    check(state.previewCurrent && state.previewLyrics()?.source == "文件内嵌歌词")
                    click(941f, 715f)
                    check(state.page == StudioPage.EDIT && !state.hasUnsavedLyrics())
                    click(30f, 28f)
                    check(state.page == StudioPage.SEARCH && !state.hasUnsavedLyrics())
                    saveImage("embedded-preview-after-editor-back.png")
                    println("PASS: real timestamp text follows current lyrics; dedicated search settings return to the originating page.")
                }
                if (!dark && width == 1080 && page == StudioPage.DISPLAY && tab == 0) {
                    setTheme(true); drawFrames(); check(renderedDark == true)
                    setTheme(false); drawFrames(); check(renderedDark == false)
                    click(1000f, 338f)
                    val popupLayers = scene.javaClass.getDeclaredField("layers").apply { isAccessible = true }.get(scene) as List<*>
                    check(popupLayers.size == 1) { "The native format selector did not open" }
                    val popup = popupLayers.single()!!
                    val bounds = popup.javaClass.getDeclaredMethod("getBoundsInWindow").apply { isAccessible = true }.invoke(popup) as androidx.compose.ui.unit.IntRect
                    saveImage("interaction-format-menu.png")
                    click((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
                    check(state.preferences.format == LyricsFormat.WORD) { "Selecting word lyrics did not update the shared preference" }
                    click(960f, 28f)
                    check(state.page == StudioPage.SEARCH && state.preferences.format == LyricsFormat.WORD)
                    println("PASS: SPW theme changes, dedicated settings, native format selection and shared persistent search preference.")
                }
                if (!dark && width == 1080 && tab == 0 && page != StudioPage.AUTO) {
                    val origin = state.page
                    autoOpened = false; click(90f, 774f)
                    check(autoOpened && state.page == StudioPage.AUTO) { "Persistent background status entry did not respond on $page" }
                    click(30f, 28f)
                    check(state.page == origin) { "Background back did not return to $origin" }
                    if (page == StudioPage.SEARCH) {
                        state.syncAutoSettings(AutoTagSettings(enabled = true))
                        state.autoProgress = AutoTagProgress("正在写入", running = true, total = 238, processed = 42, updated = 8)
                        drawFrames(); saveImage("background-status-live.png")
                    }
                }
                check(failures.isEmpty()) { "Studio composition failed: ${failures.firstOrNull()}" }
            } finally { scene.close(); frame.close(); surface.close() }
        }
        try { for (dark in listOf(false, true)) {
            StudioPage.entries.forEach { render(dark, 1080, it) }
            render(dark, 1080, StudioPage.EDIT, 1)
            render(dark, 1080, StudioPage.EDIT, 2)
        }
        render(false, 1080, StudioPage.DISPLAY, 3)
        render(false, 1080, StudioPage.DISPLAY, 2, height = 1700)
        render(true, 800, StudioPage.DISPLAY, 3, height = 640)
        render(false, 1080, StudioPage.DISPLAY, 6)
        render(false, 1080, StudioPage.DISPLAY, 7)
        render(false, 800, StudioPage.DISPLAY, 8, height = 640)
        render(true, 800, StudioPage.DISPLAY, 9, height = 640)
        render(false, 1620, StudioPage.AUTO, height = 1185, scale = 1.5f)
        render(false, 2160, StudioPage.AUTO, height = 1580, scale = 2f)
        render(false, 800, StudioPage.DISPLAY, height = 640)
        render(true, 800, StudioPage.COVER, height = 640)
        render(false, 800, StudioPage.SEARCH, height = 640)
        render(true, 800, StudioPage.EDIT, height = 640)
        render(false, 800, StudioPage.EDIT, 1, height = 640)
        render(false, 1620, StudioPage.EDIT, 1, height = 1185, scale = 1.5f)
        render(false, 2160, StudioPage.EDIT, height = 1580, scale = 2f)
        render(false, 800, StudioPage.TAGS, height = 640)
        render(false, 1080, StudioPage.SEARCH, 3)
        render(true, 1080, StudioPage.SEARCH, 3)
        render(false, 1080, StudioPage.SEARCH, 4)
        render(true, 1080, StudioPage.SEARCH, 4)
        render(false, 1080, StudioPage.SEARCH, 5)
        render(false, 1080, StudioPage.SEARCH, 1)
        render(false, 1080, StudioPage.SEARCH, 2)
        render(false, 1080, StudioPage.COVER, 1)
        render(true, 1080, StudioPage.COVER, 2)
        } finally { themeState.value = oldTheme }
    }
    } finally { coverServer.close(); coverServing.join(3000) }
    println("PASS: all studio sections, line/timing/source tabs, light/dark and narrow layouts rendered; previews saved.")
}

