package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.resolveTheme
import atlas.core.Log
import atlas.core.SourceQuestions
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.roundToInt

/** 录屏图形界面脚本位置（随 Summary 仓库分发，见 tools/screen_recorder/README.md）。 */
private val RECORD_GUI =
    File(System.getProperty("user.home"), "Project/MyProject/Summary/tools/screen_recorder/record-gui").absolutePath

@Composable
fun SettingsView(store: AppStore, onOpenColors: () -> Unit = {}) {
    val ui = atlasUiTokens()
    var localOnly by remember { mutableStateOf(store.settings.localOnlyExtra.joinToString("\n")) }
    var ignored by remember { mutableStateOf(store.settings.ignoredExtra.joinToString("\n")) }
    var sourceQuestionPaths by remember { mutableStateOf(store.settings.sourceQuestionPaths.joinToString("\n")) }
    var fontScale by remember { mutableStateOf(store.settings.fontScale) }
    var clickAnswerToEdit by remember { mutableStateOf(store.settings.clickAnswerToEdit) }
    var markdownStyle by remember { mutableStateOf(store.settings.markdownStyle) }
    var showParams by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ui.spacing.page),
        verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(ui.spacing.section)) {
            Text("设置", style = ui.typography.pageTitle)

            // 外观与阅读：主题、字号、Markdown 样式、答案点击行为
            SettingsCard("外观与阅读") {
                Text("配色主题", fontWeight = FontWeight.SemiBold)
                SettingsEntryRow(
                    title = "配色主题",
                    subtitle = store.settings.themeName.ifBlank { AtlasThemes.DEFAULT.name },
                    swatch = resolveTheme(store.settings).accent,
                    onClick = { onOpenColors() },
                )
                Text("深浅色", fontWeight = FontWeight.SemiBold)
                ChipSelector(
                    options = listOf("light" to "浅色", "dark" to "深色"),
                    selected = store.settings.theme,
                ) { value, label ->
                    Log.i("切换深浅色 → $label")
                    store.settings = store.settings.copy(theme = value)
                    store.saveSettings()
                }

                Text("全局文字大小", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Slider(
                        value = fontScale,
                        onValueChange = { value ->
                            fontScale = value
                            store.settings = store.settings.copy(fontScale = value)
                        },
                        onValueChangeFinished = { store.saveSettings() },
                        valueRange = 0.8f..1.4f,
                        steps = 5,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(fontScale * 100).roundToInt()}%", Modifier.width(48.dp), color = Theme.Accent)
                }
                Text("调整应用内所有文字大小，范围 80%–140%。", fontSize = 11.sp, color = Theme.Muted)

                Text("Markdown 展示", fontWeight = FontWeight.SemiBold)
                ChipSelector(
                    options = listOf("reader" to "阅读优化", "classic" to "经典样式"),
                    selected = markdownStyle,
                ) { value, _ ->
                    markdownStyle = value
                    store.settings = store.settings.copy(markdownStyle = value)
                    store.saveSettings()
                }
                Text("阅读优化强调层级、留白和代码可读性；经典样式保留旧版 Markdown 外观。", fontSize = 11.sp, color = Theme.Muted)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clickAnswerToEdit,
                        onCheckedChange = { enabled ->
                            clickAnswerToEdit = enabled
                            store.settings = store.settings.copy(clickAnswerToEdit = enabled)
                            store.saveSettings()
                        },
                    )
                    Text("点击答案内容打开编辑弹窗")
                }
                Text("关闭后，答案仍可划词选择；需要编辑时点击“编辑”按钮。", fontSize = 11.sp, color = Theme.Muted)
            }

            // 复习：记忆参数
            SettingsCard("复习") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Log.d("打开 FSRS 参数"); showParams = true }) { Text("记忆参数（FSRS）") }
                }
            }

            // 知识库：目录与扫描 + 题目源文档
            SettingsCard("知识库") {
                Text("知识库目录", fontWeight = FontWeight.SemiBold)
                Text(store.settings.libraryPath, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val chooser = JFileChooser(File(store.settings.libraryPath.ifBlank { System.getProperty("user.home") }))
                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        chooser.dialogTitle = "选择知识库目录"
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            Log.i("设置页更换知识库目录 → ${chooser.selectedFile.absolutePath}")
                            store.openLibrary(chooser.selectedFile.absolutePath, rescanIfNeeded = true)
                        }
                    }) { Text("更换知识库目录（会重新打开）") }
                    OutlinedButton(onClick = { Log.i("手动触发增量扫描"); store.rescan(full = false) }) { Text("重新扫描") }
                }
                Text(store.scanMessage.value, fontSize = 11.sp, color = Theme.Muted)

                Text("题目源文档", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "每行一个相对知识库根目录的 Markdown 路径；路径以 / 结尾时表示整个目录。保存后立即重新解析题库。",
                    fontSize = 11.sp, color = Theme.Muted,
                )
                OutlinedTextField(
                    sourceQuestionPaths,
                    { sourceQuestionPaths = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("支持解析的文档或目录（每行一个）") },
                    minLines = 4,
                )
                Button(onClick = {
                    val configured = sourceQuestionPaths.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    Log.i("保存题目源文档配置：${configured.size}项")
                    store.settings = store.settings.copy(
                        sourceQuestionPaths = configured.ifEmpty { SourceQuestions.DEFAULT_SUPPORTED_PATHS },
                    )
                    store.saveSettings()
                    val effective = store.settings.sourceQuestionPaths
                    if (!SourceQuestions.isSupportedPath(store.selectedSourcePath, effective)) {
                        val firstSupportedDocument = store.knowledgeDocuments.firstOrNull {
                            SourceQuestions.isSupportedPath(it, effective)
                        }
                        if (firstSupportedDocument != null) {
                            store.selectSourceDocument(firstSupportedDocument)
                        } else {
                            store.reloadKnowledgeFiles()
                        }
                    } else {
                        store.reloadKnowledgeFiles()
                    }
                }) { Text("保存题目源文档配置") }
            }

            // 隐私边界：仅本地 / 额外忽略
            SettingsCard("隐私边界") {
                Text(
                    "「仅本地」目录里的内容只在本机检索，永远不会作为上下文发给 AI——工作敏感仓库放这里。",
                    fontSize = 11.sp, color = Theme.Muted,
                )
                OutlinedTextField(localOnly, { localOnly = it }, Modifier.fillMaxWidth(), label = { Text("仅本地目录（每行一个）") }, minLines = 3)
                OutlinedTextField(ignored, { ignored = it }, Modifier.fillMaxWidth(), label = { Text("额外忽略目录（每行一个）") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Log.i("保存隐私边界设置：仅本地=${localOnly.lines().count { it.isNotBlank() }}行 忽略额外=${ignored.lines().count { it.isNotBlank() }}行，触发全量重建")
                        store.settings = store.settings.copy(
                            localOnlyExtra = localOnly.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            ignoredExtra = ignored.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        )
                        store.saveSettings()
                        store.rescan(full = true)
                    }) { Text("保存") }
                }
                // 档位图例
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip("全索引", Theme.OkGreen); StatusChip("仅本地 🔒", Theme.WarnOrange); StatusChip("忽略", Theme.Muted)
                }
            }

            // 屏幕录制：保存目录 / 帧率 / 码率（保存时导出给 tools/screen_recorder 脚本）
            SettingsCard("屏幕录制") {
                val recDir = store.settings.recordingSaveDir.ifBlank {
                    File(System.getProperty("user.home"), "Videos/Screencasts").absolutePath
                }
                Text("保存目录", fontWeight = FontWeight.SemiBold)
                Text(recDir, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val chooser = JFileChooser(File(recDir))
                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        chooser.dialogTitle = "选择录屏保存目录"
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val path = chooser.selectedFile.absolutePath
                            Log.i("设置录屏保存目录 → $path")
                            store.settings = store.settings.copy(recordingSaveDir = path)
                            store.saveSettings()
                        }
                    }) { Text("选择目录") }
                    OutlinedButton(onClick = {
                        if (!File(RECORD_GUI).isFile) {
                            Log.e("录屏脚本不存在：$RECORD_GUI")
                            return@OutlinedButton
                        }
                        try {
                            ProcessBuilder(RECORD_GUI)
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                            Log.i("已启动录屏界面")
                        } catch (e: Exception) {
                            Log.e("启动录屏界面失败", e)
                        }
                    }) { Text("打开录屏界面") }
                }

                Text("默认帧率（喂 AI 分析 15 足够，更高只增加文件体积）", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                ChipSelector(
                    options = listOf("10" to "10 fps", "15" to "15 fps", "24" to "24 fps", "30" to "30 fps"),
                    selected = store.settings.recordingFps.toString(),
                ) { value, _ ->
                    store.settings = store.settings.copy(recordingFps = value.toInt())
                    store.saveSettings()
                }

                var bitrate by remember { mutableStateOf(store.settings.recordingBitrate.toString()) }
                Text("x264 码率（kbps，决定清晰度与文件大小）", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(bitrate, { bitrate = it }, Modifier.width(160.dp), label = { Text("码率 kbps") })
                    Button(onClick = {
                        val v = bitrate.toIntOrNull()?.coerceIn(500, 20000) ?: 4000
                        bitrate = v.toString()
                        Log.i("设置录屏码率 → ${v}kbps")
                        store.settings = store.settings.copy(recordingBitrate = v)
                        store.saveSettings()
                    }) { Text("保存") }
                }
                Text("参数保存时写入 ~/.local/share/atlas/screen-recorder.json，录屏脚本启动时读取。", fontSize = 11.sp, color = Theme.Muted)
            }

            // 关于：数据位置与产品说明
            SettingsCard("关于") {
                Text("应用数据", fontWeight = FontWeight.SemiBold)
                Text(
                    "配置与数据库：${System.getProperty("user.home")}/.local/share/atlas（缓存可随时删除重建，你的笔记只在你库里）",
                    fontSize = 12.sp,
                )
                Text("Atlas · 零模型 · 零网络 · AGPL-3.0", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "出题、批改等 AI 能力由你的编码代理完成；Atlas 负责检索、复习调度与学习纪律。",
                    fontSize = 12.sp, color = Theme.Muted,
                )
            }
        }
    }
    if (showParams) FsrsParamsDialog { showParams = false }
}

/** 主设置页通往子页的一行入口：左侧色块 + 标题 + 当前值 + 右箭头。 */
@Composable
internal fun SettingsEntryRow(
    title: String,
    subtitle: String,
    swatch: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp)
                .background(swatch, RoundedCornerShape(6.dp))
                .border(1.dp, Theme.Muted.copy(alpha = 0.45f), RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = Theme.Muted)
        }
        Text("›", fontSize = 20.sp, color = Theme.Muted)
    }
}

/** 设置分区卡片：统一的标题与容器，替代裸排的分区标题 + VDivider。 */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val ui = atlasUiTokens()
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Elevated,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = ui.typography.sectionTitle, color = Theme.Accent)
            content()
        }
    }
}

/** 单选芯片组：选中项高亮，再次点击已选中项不触发回调。 */
@Composable
private fun ChipSelector(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (value: String, label: String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = selected == value
            Text(
                label,
                Modifier
                    .clickable { if (!active) onSelect(value, label) }
                    .background(if (active) Theme.Accent.copy(alpha = 0.18f) else Color.Transparent, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                fontSize = 13.sp,
                color = if (active) Theme.Accent else Theme.Muted,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}


/** 主题色板：一块双色预览 + 名称。 */
@Composable
internal fun ThemeSwatch(
    label: String,
    sub: String,
    spec: ThemeSpec,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(150.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (active) Theme.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp).clip(MaterialTheme.shapes.small)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(spec.background))
            Box(Modifier.weight(1f).fillMaxHeight().background(spec.surface))
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small)) {
            listOf(spec.accent, spec.okGreen, spec.warnOrange, spec.badRed, spec.inlineCodeFg)
                .forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(c)) }
        }
        Text(
            label,
            fontSize = 12.sp,
            color = if (active) Theme.Accent else Theme.Muted,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, color = Theme.Muted, maxLines = 1)
    }
}

/**
 * 自定义主题编辑弹窗：12 个语义色逐项改 hex，实时写回 settings。
 * 只允许编辑「我的主题」；内置主题不可改，要改就先复制一份。
 */
@Composable
internal fun ThemeEditDialog(store: AppStore, themeName: String, onDismiss: () -> Unit) {
    val custom = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }
    val target = custom.firstOrNull { it.name == themeName }
    if (target == null) {
        onDismiss()
        return
    }
    var spec by remember(target.name) { mutableStateOf(target.spec) }
    val ui = atlasUiTokens()
    // `editing` 优先于 spec：它承载尚未解析完成的输入中间态
    var editing by remember(target.name) { mutableStateOf<List<String>?>(null) }
    var problem by remember(target.name) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val hexes = editing ?: spec.hexList()

    fun put(index: Int, raw: String) {
        val next = hexes.toMutableList()
        next[index] = sanitizeHexInput(raw)
        editing = next
        val bad = next.withIndex().firstOrNull { (i, h) -> h.isNotEmpty() && parseHexColor(h) == null }
        if (bad != null) {
            problem = "${ThemeSpec.LABELS[bad.index]}：色值格式不对（需 #RRGGBB 或 #AARRGGBB）"
            return
        }
        val blankBase = (0 until ThemeSpec.BASE).firstOrNull { next[it].isBlank() }
        if (blankBase != null) {
            problem = "${ThemeSpec.LABELS[blankBase]} 是必填项，不能留空"
            return
        }
        val parsed = ThemeSpec.fromHexList(next)
        if (parsed == null) {
            problem = "配色不完整，暂未保存"
            return
        }
        problem = null
        spec = parsed
        store.settings = store.settings.copy(
            customThemes = store.settings.customThemes.map { raw2 ->
                if (CustomTheme.nameOf(raw2) == target.name) CustomTheme(target.name, parsed).encode() else raw2
            },
        )
        store.saveSettings()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(520.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("编辑主题 · ${target.name}", style = ui.typography.sectionTitle, color = Theme.Accent)
                Text("留空 = 跟随内置默认；改动立即生效并保存。", fontSize = 11.sp, color = Theme.Muted)
                var group by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("界面配色" to 0, "Markdown 配色" to 1).forEach { (label, idx) ->
                        Text(
                            label,
                            Modifier
                                .clickable { group = idx }
                                .background(
                                    if (group == idx) Theme.Accent.copy(alpha = 0.16f) else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            fontSize = 13.sp,
                            color = if (group == idx) Theme.Accent else Theme.Muted,
                        )
                    }
                }
                Text(
                    if (group == 0) "改动立即生效并保存。"
                    else "留空则按主题基色推导：标题/加粗/链接=强调色，引用=次要文字，行内代码=行内代码色。",
                    fontSize = 11.sp, color = Theme.Muted,
                )
                if (problem != null) {
                    Text(
                        problem!!,
                        fontSize = 12.sp,
                        color = Theme.BadRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Theme.BadRed.copy(alpha = 0.10f), MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                val from = if (group == 0) 0 else ThemeSpec.BASE
                val to = if (group == 0) ThemeSpec.BASE else ThemeSpec.SIZE
                Column(Modifier.height(300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (from until to).forEach { i ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(ThemeSpec.LABELS[i], Modifier.width(76.dp), fontSize = 13.sp)
                            val parsed = remember(hexes[i]) { parseHexColor(hexes[i]) }
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(parsed ?: Color.Transparent)
                                    .border(1.dp, Theme.Muted.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                    .clickable { picking = i },
                            )
                            OutlinedTextField(
                                value = hexes[i],
                                onValueChange = { put(i, it) },
                                singleLine = true,
                                placeholder = { Text("默认", fontSize = 12.sp, color = Theme.Muted) },
                                modifier = Modifier.width(132.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        store.settings = store.settings.copy(
                            customThemes = store.settings.customThemes.filterNot {
                                CustomTheme.nameOf(it) == target.name
                            },
                            themeName = if (store.settings.themeName == target.name) "" else store.settings.themeName,
                        )
                        store.saveSettings()
                        Log.i("删除自定义主题 → ${target.name}")
                        onDismiss()
                    }) { Text("删除该主题", color = Theme.BadRed) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
    picking?.let { idx ->
        val here = parseHexColor(hexes[idx]) ?: spec.hexListOfMd().getOrNull(idx - ThemeSpec.BASE)
            ?.let(::parseHexColor)
            ?: Color.White
        ColorPickerDialog(
            title = "${ThemeSpec.LABELS[idx]} · ${target.name}",
            initial = here,
            themeColors = spec.hexList().mapNotNull(::parseHexColor).distinct(),
            onPick = { picked ->
                put(idx, hexOf(picked).removePrefix("#"))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** 新建自定义主题：命名 + 选母版，命名唯一且永久保留。 */
@Composable
internal fun CreateThemeDialog(store: AppStore, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val dark = store.settings.darkTheme
    val existing = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }.map { it.name }.toSet()
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(store.settings.themeName.ifBlank { AtlasThemes.DEFAULT.name }) }
    val trimmed = name.trim()
    val dup = trimmed.isNotEmpty() && trimmed in existing
    val valid = trimmed.isNotEmpty() && !dup

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(460.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("新建自定义主题", style = atlasUiTokens().typography.sectionTitle, color = Theme.Accent)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("主题名称") },
                    isError = dup,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dup) Text("已有同名主题，换个名字", fontSize = 11.sp, color = Theme.BadRed)
                Text("基于哪套配色", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Column(Modifier.height(180.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (AtlasThemes.ALL.map { it.name } + existing).distinct().forEach { n ->
                        val spec = if (n in AtlasThemes.ALL.map { it.name }) {
                            AtlasThemes.specOf(n, dark)
                        } else {
                            CustomTheme.decode(
                                store.settings.customThemes.first { CustomTheme.nameOf(it) == n },
                            )?.spec ?: return@forEach
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (n == base) Theme.Accent.copy(alpha = 0.14f) else Color.Transparent,
                                    MaterialTheme.shapes.small)
                                .clickable { base = n }
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(16.dp).background(spec.accent, RoundedCornerShape(4.dp))
                                .border(1.dp, Theme.Muted.copy(alpha = 0.4f), RoundedCornerShape(4.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text(n, fontSize = 13.sp, color = if (n == base) Theme.Accent else Theme.Muted)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        enabled = valid,
                        onClick = {
                            val spec = if (base in AtlasThemes.ALL.map { it.name }) {
                                AtlasThemes.specOf(base, dark)
                            } else {
                                CustomTheme.decode(
                                    store.settings.customThemes.first { CustomTheme.nameOf(it) == base },
                                )?.spec ?: return@Button
                            }
                            store.settings = store.settings.copy(
                                customThemes = store.settings.customThemes + CustomTheme(trimmed, spec).encode(),
                                themeName = trimmed,
                            )
                            store.saveSettings()
                            Log.i("新建自定义主题 → $trimmed（母版 $base）")
                            onCreated(trimmed)
                            onDismiss()
                        },
                    ) { Text("创建") }
                }
            }
        }
    }
}

/** 删除自定义主题的二次确认。 */
@Composable
internal fun DeleteThemeDialog(store: AppStore, name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(400.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("删除主题", style = atlasUiTokens().typography.sectionTitle, color = Theme.BadRed)
                Text("确定删除「$name」？该操作不可撤销。", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        store.settings = store.settings.copy(
                            customThemes = store.settings.customThemes.filterNot {
                                CustomTheme.nameOf(it) == name
                            },
                            themeName = if (store.settings.themeName == name) "" else store.settings.themeName,
                        )
                        store.saveSettings()
                        Log.i("删除自定义主题 → $name")
                        onDismiss()
                    }) { Text("删除", color = Theme.BadRed) }
                }
            }
        }
    }
}
