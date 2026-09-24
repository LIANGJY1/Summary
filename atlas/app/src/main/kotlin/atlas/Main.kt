package atlas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import atlas.ui.AtlasTheme
import atlas.ui.IndexerHit
import atlas.ui.LearningView
import atlas.ui.NavBadge
import atlas.ui.PreviewDialog
import atlas.ui.QuestionSection
import atlas.ui.SettingsView
import atlas.ui.Theme
import atlas.ui.TodayView
import atlas.ui.ToolsView
import atlas.ui.VDivider
import atlas.ui.WindowControlButtons
import atlas.ui.WindowResizeBorders
import atlas.ui.appTitleBarDrag
import atlas.ui.atlasUiTokens
import atlas.ui.settingsTabLabel
import atlas.ui.topLevelTabs
import kotlinx.coroutines.delay
import atlas.core.Log
import java.io.File
import javax.swing.JFileChooser

fun main() {
    Log.init(File(System.getProperty("user.home"), ".local/share/atlas"))
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        Log.e("未捕获异常（thread=${t.name}）——应用可能崩溃或 EDT 挂掉导致界面卡死", e)
    }
    Runtime.getRuntime().addShutdownHook(Thread { Log.i("JVM 退出") })
    Log.i(
        "应用启动 java=${System.getProperty("java.version")} os=${System.getProperty("os.name")}/${System.getProperty("os.arch")} " +
            "heapMax=${Runtime.getRuntime().maxMemory() / 1048576}MB user.home=${System.getProperty("user.home")}"
    )
    val store = AppStore()
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        Window(
            onCloseRequest = {
                Log.i("窗口关闭请求，应用退出")
                exitApplication()
            },
            title = "Atlas",
            state = windowState,
            undecorated = true,
        ) {
            AtlasTheme(dark = store.settings.darkTheme, fontScale = store.settings.fontScale) {
                AppRoot(store, windowState) { exitApplication() }
            }
        }
    }
}

@Composable
fun AppRoot(store: AppStore, windowState: WindowState, onClose: () -> Unit) {
    val ui = atlasUiTokens()
    LaunchedEffect(Unit) {
        Log.timed("应用 boot()", warnMs = 1000) { runCatching { store.boot() }.onFailure { Log.e("boot() 异常", it) } }
    }
    var tab by remember { mutableStateOf("工作台") }
    var learnSection by remember { mutableStateOf("复习") }
    var showPalette by remember { mutableStateOf(false) }
    val rootFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(store.libraryReady) {
        if (store.libraryReady) {
            // 等焦点节点挂载稳定再请求，避免 SetupView→主界面切换帧的竞态警告
            delay(100)
            runCatching { rootFocus.requestFocus() }
        }
    }
    val inbox = store.candidates.size

    if (!store.libraryReady) {
        // 建库页同样使用自定义标题栏（窗口已无系统装饰）
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .appTitleBarDrag(windowState)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .padding(horizontal = ui.spacing.page, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Theme.Accent)
                Spacer(Modifier.weight(1f))
                WindowControlButtons(windowState, onClose)
            }
            VDivider()
            SetupView(store)
        }
        return
    }
    WindowResizeBorders(windowState) {
    Column(
        Modifier.fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.K && e.isCtrlPressed) {
                    Log.d("Ctrl+K 打开命令面板"); showPalette = true; true
                } else false
            },
    ) {
        // 顶栏（兼自定义标题栏：拖动移动窗口，双击最大化）
        Row(
            Modifier.fillMaxWidth()
                .appTitleBarDrag(windowState)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                .padding(horizontal = ui.spacing.page, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Theme.Accent)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                topLevelTabs().forEach { t ->
                    val active = tab == t
                    NavTab(label = t, active = active, onClick = { Log.i("页签切换 → $t"); tab = t }) {
                        when (t) {
                            "工作台" -> if (inbox > 0) NavBadge("$inbox", Theme.WarnOrange)
                            "题库" -> if (store.sourceQuestions.isNotEmpty()) NavBadge("${store.sourceQuestions.size}", Theme.Accent)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Ctrl+K 搜索", fontSize = 11.sp, color = Theme.Muted)
            Text(
                "⚙",
                Modifier
                    .clickable { Log.i("设置图标点击"); tab = settingsTabLabel() }
                    .semantics { contentDescription = settingsTabLabel() },
                fontSize = 20.sp,
                color = if (tab == settingsTabLabel()) Theme.Accent else MaterialTheme.colorScheme.onSurface,
            )
            // undecorated 窗口的窗口控制按钮
            WindowControlButtons(windowState, onClose)
        }
        VDivider()
        // 内容
        Box(Modifier.weight(1f)) {
            when (tab) {
                "学习" -> LearningView(store, learnSection) { learnSection = it }
                "题库" -> QuestionSection(store)
                "工具" -> ToolsView(store)
                "设置" -> SettingsView(store)
                else -> TodayView(store) { destination ->
                    when (destination) {
                        "学习" -> { learnSection = "复习"; tab = "学习" }
                        "题库" -> { tab = "题库" }
                        else -> tab = "工作台"
                    }
                }
            }
        }
        // 状态栏
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                .padding(horizontal = ui.spacing.page, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("条目 ${store.notes.size}", fontSize = 11.sp, color = Theme.Muted)
            Text("待确认 $inbox", fontSize = 11.sp, color = Theme.Muted)
            Spacer(Modifier.weight(1f))
            store.toast.value?.let { Text(it, fontSize = 11.sp, color = Theme.OkGreen) }
        }
    }
    }

    // 预览浮层：复习卡「跳回原文」、Ctrl+K 打开命中（原知识库页预览，现按需弹出）
    var previewRel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store.pendingPreview) {
        store.pendingPreview?.let { rel ->
            Log.i("打开预览浮层 → $rel")
            previewRel = rel
            store.pendingPreview = null
        }
    }
    previewRel?.let { rel -> PreviewDialog(store, rel) { previewRel = null } }

    if (showPalette) {
        CommandPalette(store) { showPalette = false }
    }
}

/** 顶栏导航页签：激活态为强调色胶囊、悬停有轻反馈；去掉旧版「胶囊+下划线」双重指示。 */
@Composable
private fun NavTab(label: String, active: Boolean, onClick: () -> Unit, badge: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .background(
                when {
                    active -> Theme.Accent.copy(alpha = 0.16f)
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) Theme.Accent else Theme.Muted,
        )
        badge?.invoke()
    }
}

/** Ctrl+K 全局搜索浮层（PRD FR-B3）：输入即搜，↑↓ 选择，回车打开预览浮层 */
@Composable
fun CommandPalette(store: AppStore, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var idx by remember { mutableStateOf(0) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val hits = remember(query) {
        if (query.isBlank()) emptyList()
        else Log.timed("Ctrl+K 即时搜索 q=$query", warnMs = 300, logAlways = false) { store.search(query) }.take(20)
    }
    Dialog(onDismissRequest = { Log.d("命令面板关闭（点击外部）"); onDismiss() }) {
        Surface(
            Modifier.fillMaxWidth(0.6f).heightIn(min = 120.dp, max = 480.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown) {
                        when (e.key) {
                            Key.DirectionDown -> { if (hits.isNotEmpty()) idx = (idx + 1) % hits.size; true }
                            Key.DirectionUp -> { if (hits.isNotEmpty()) idx = (idx - 1 + hits.size) % hits.size; true }
                            Key.Enter -> {
                                hits.getOrNull(idx)?.let {
                                    Log.i("命令面板回车打开 → ${it.path}##${it.section}")
                                    store.requestPreview(it.path)
                                }
                                onDismiss(); true
                            }
                            else -> false
                        }
                    } else false
                },
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it; idx = 0 }, Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索全库") }, singleLine = true,
                )
                if (query.isNotBlank() && hits.isEmpty()) Text("库中没有找到相关内容。", color = Theme.Muted, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(hits.withIndex().toList(), key = { it.value.hashCode() }) { (i, h) ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    Log.i("命令面板点击打开 → ${h.path}##${h.section}")
                                    store.requestPreview(h.path); onDismiss()
                                }
                                .background(if (i == idx) Theme.Accent.copy(alpha = 0.12f) else Color.Transparent)
                                .padding(6.dp),
                        ) {
                            Text("${h.path}  ##${h.section}", fontSize = 11.sp, color = Theme.Accent, fontFamily = FontFamily.Monospace, maxLines = 1)
                            Text(h.snippet, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val pack = Log.timed("上下文包 q=$query", warnMs = 300) { store.contextPack(query) }
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(pack))
                        Log.i("上下文包已复制到剪贴板 ${pack.length} 字符")
                        store.showToast("已复制问题 + 相关笔记全文，粘贴给 AI 即可")
                        onDismiss()
                    }, enabled = query.isNotBlank()) { Text("复制为上下文包") }
                    Text("回车打开 · Esc 关闭", fontSize = 11.sp, color = Theme.Muted)
                }
            }
        }
    }
}

@Composable
fun SetupView(store: AppStore) {
    var path by remember { mutableStateOf(store.settings.libraryPath) }
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 28.sp)
        Text(
            "把一个 markdown 目录变成：可检索的知识库 + 闪卡复习 + 学习任务队列。\n" +
                "零模型、零网络：检索与复习全部本地；出题、批改等 AI 能力由你的编码代理（ZCode/Codex）完成。",
            fontSize = 14.sp, color = Theme.Muted, lineHeight = 22.sp,
        )
        store.bootError?.let {
            Text(it, color = Theme.BadRed, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(path, { path = it }, Modifier.weight(1f), label = { Text("知识库目录（选一个 md 文件夹）") }, singleLine = true)
            Button(onClick = {
                val chooser = JFileChooser(File(path.ifBlank { System.getProperty("user.home") }))
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                chooser.dialogTitle = "选择知识库目录"
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) path = chooser.selectedFile.absolutePath
            }) { Text("选择目录") }
            Button(onClick = {
                try {
                    Log.i("SetupView 请求打开库 path=$path")
                    if (File(path).isDirectory) store.openLibrary(path, rescanIfNeeded = true)
                    else { Log.w("SetupView 打开失败：目录不存在 $path"); store.showToast("目录不存在") }
                } catch (e: Exception) {
                    Log.e("SetupView 打开库异常 path=$path", e)
                    store.showToast("打开失败：${e.message}")
                }
            }) { Text("打开这个库") }
        }
        Text(
            "提示：Atlas 会在库里创建 atlas/ 目录存放卡片与题目（cards.md、questions.md、inbox、outbox），" +
                "这些文件人可直接编辑。建库后请把该目录登记进你知识库的 AGENTS.md 知识地图。",
            fontSize = 12.sp, color = Theme.Muted, lineHeight = 18.sp,
        )
    }
}
