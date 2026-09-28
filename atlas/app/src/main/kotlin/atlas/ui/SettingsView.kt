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
    var showParams by remember { mutableStateOf(false) }

    var destination by remember { mutableStateOf(SettingsDestination.OVERVIEW) }
    Row(Modifier.fillMaxSize()) {
        SettingsSidebar(destination) { destination = it }
        Box(
            Modifier.fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(ui.spacing.page),
            verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
        ) {
            Column(Modifier.widthIn(max = 980.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("设置中心", fontSize = 12.sp, color = Theme.Accent, fontWeight = FontWeight.SemiBold)
                Text(destination.title, style = ui.typography.pageTitle)
                Text(destination.description, fontSize = 13.sp, color = Theme.Muted)
                if (destination == SettingsDestination.OVERVIEW) {
                    SettingsSummary(store)
                    SettingsQuickLinks { destination = it }
                } else {
                    when (destination) {
                        SettingsDestination.APPEARANCE -> SettingsAppearance(store, onOpenColors, fontScale, { fontScale = it }, clickAnswerToEdit, { clickAnswerToEdit = it })
                        SettingsDestination.LIBRARY -> SettingsLibrary(store, sourceQuestionPaths, { sourceQuestionPaths = it })
                        SettingsDestination.PRIVACY -> SettingsPrivacy(store, localOnly, { localOnly = it }, ignored, { ignored = it })
                        SettingsDestination.REVIEW -> SettingsReview { showParams = true }
                        SettingsDestination.RECORDING -> SettingsRecording(store)
                        SettingsDestination.ABOUT -> SettingsAbout()
                        SettingsDestination.OVERVIEW -> Unit
                    }
                }
            }
        }
    }
    if (showParams) FsrsParamsDialog { showParams = false }
}

private enum class SettingsDestination(val title: String, val description: String) {
    OVERVIEW("设置概览", "把 Atlas 调整成适合你工作节奏的学习工作台。"),
    APPEARANCE("外观与阅读", "主题、字号和答案交互，决定每天使用 Atlas 的舒适度。"),
    LIBRARY("知识库", "选择知识库、控制题目来源，并在需要时重新扫描。"),
    PRIVACY("隐私边界", "明确哪些目录可被索引、哪些内容永远只留在本机。"),
    REVIEW("复习", "调整 FSRS 记忆参数，让复习节奏贴合你的目标。"),
    RECORDING("屏幕录制", "统一管理录屏目录、帧率与码率，方便制作复盘素材。"),
    ABOUT("关于 Atlas", "产品定位、数据位置与本地优先原则。"),
}

@Composable
private fun SettingsSidebar(selected: SettingsDestination, onSelect: (SettingsDestination) -> Unit) {
    Column(Modifier.width(236.dp).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("设置中心", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Theme.MdH1)
            Text("Atlas 工作站", fontSize = 12.sp, color = Theme.Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsDestination.values().forEach { item ->
                val active = item == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(item) }
                        .background(if (active) Theme.Selected else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (active) Theme.Accent else Theme.Muted.copy(alpha = 0.5f)))
                    Text(item.title, fontSize = 13.sp, color = if (active) Theme.Accent else Theme.Muted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("配置会立即保存到本机", fontSize = 11.sp, color = Theme.Muted)
    }
}

@Composable
private fun SettingsSummary(store: AppStore) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsMetric("当前主题", store.settings.themeName.ifBlank { AtlasThemes.DEFAULT.name }, Theme.Accent, Modifier.weight(1f))
        SettingsMetric("知识库", File(store.settings.libraryPath).name.ifBlank { "未设置" }, Theme.OkGreen, Modifier.weight(1f))
        SettingsMetric("索引状态", store.scanMessage.value.ifBlank { "就绪" }, Theme.WarnOrange, Modifier.weight(1f))
    }
}

@Composable
private fun SettingsMetric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = Theme.Elevated, tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.width(24.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
            Text(label, fontSize = 11.sp, color = Theme.Muted)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsQuickLinks(onSelect: (SettingsDestination) -> Unit) {
    SettingsSection("常用入口", "从这里快速进入最常调整的设置") {
        listOf(SettingsDestination.APPEARANCE, SettingsDestination.LIBRARY, SettingsDestination.PRIVACY).forEach { item ->
            SettingsEntryRow(item.title, item.description, if (item == SettingsDestination.PRIVACY) Theme.WarnOrange else Theme.Accent, { onSelect(item) })
        }
    }
}

@Composable
private fun SettingsAppearance(store: AppStore, onOpenColors: () -> Unit, fontScale: Float, setFontScale: (Float) -> Unit, clickAnswerToEdit: Boolean, setClickAnswerToEdit: (Boolean) -> Unit) {
    SettingsSection("主题与阅读", "让界面在长时间阅读时保持清晰、克制") {
        SettingsEntryRow("配色主题", store.settings.themeName.ifBlank { AtlasThemes.DEFAULT.name }, resolveTheme(store.settings).accent, onOpenColors)
        SettingsGroup("深浅色") {
            ChipSelector(listOf("light" to "浅色", "dark" to "深色"), store.settings.theme) { value, label ->
                Log.i("切换深浅色 → $label"); store.settings = store.settings.copy(theme = value); store.saveSettings()
            }
        }
        SettingsGroup("全局文字大小", "80%–140%，拖动后立即预览") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Slider(value = fontScale, onValueChange = { setFontScale(it); store.settings = store.settings.copy(fontScale = it) }, onValueChangeFinished = { store.saveSettings() }, valueRange = 0.8f..1.4f, steps = 5, modifier = Modifier.weight(1f))
                Text("${(fontScale * 100).roundToInt()}%", Modifier.width(48.dp), color = Theme.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = clickAnswerToEdit, onCheckedChange = { setClickAnswerToEdit(it); store.settings = store.settings.copy(clickAnswerToEdit = it); store.saveSettings() })
            Text("点击答案内容打开编辑弹窗")
        }
        Text("关闭后仍可划词选择；需要编辑时使用答案卡片中的“编辑”按钮。", fontSize = 11.sp, color = Theme.Muted)
    }
}

@Composable
private fun SettingsLibrary(store: AppStore, sourceQuestionPaths: String, setSourceQuestionPaths: (String) -> Unit) {
    SettingsSection("知识库目录", "默认知识库和已有设置会保留，不会因界面重构被清空") {
        Text(store.settings.libraryPath, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Theme.MdH2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val chooser = JFileChooser(File(store.settings.libraryPath.ifBlank { System.getProperty("user.home") })); chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; chooser.dialogTitle = "选择知识库目录"
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) { Log.i("设置页更换知识库目录 → ${chooser.selectedFile.absolutePath}"); store.openLibrary(chooser.selectedFile.absolutePath, rescanIfNeeded = true) }
            }) { Text("更换目录") }
            OutlinedButton(onClick = { Log.i("手动触发增量扫描"); store.rescan(full = false) }) { Text("重新扫描") }
        }
        Text(store.scanMessage.value, fontSize = 11.sp, color = Theme.Muted)
    }
    SettingsSection("题目源文档", "每行一个相对知识库根目录的 Markdown 路径；以 / 结尾表示整个目录") {
        OutlinedTextField(sourceQuestionPaths, setSourceQuestionPaths, Modifier.fillMaxWidth(), label = { Text("支持解析的文档或目录") }, minLines = 5)
        SettingsActionRow {
            Button(onClick = {
                val configured = sourceQuestionPaths.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct(); Log.i("保存题目源文档配置：${configured.size}项")
                store.settings = store.settings.copy(sourceQuestionPaths = configured.ifEmpty { SourceQuestions.DEFAULT_SUPPORTED_PATHS }); store.saveSettings()
                val effective = store.settings.sourceQuestionPaths
                if (!SourceQuestions.isSupportedPath(store.selectedSourcePath, effective)) {
                    val first = store.knowledgeDocuments.firstOrNull { SourceQuestions.isSupportedPath(it, effective) }
                    if (first != null) store.selectSourceDocument(first) else store.reloadKnowledgeFiles()
                } else store.reloadKnowledgeFiles()
            }) { Text("保存题目源文档配置") }
        }
    }
}

@Composable
private fun SettingsPrivacy(store: AppStore, localOnly: String, setLocalOnly: (String) -> Unit, ignored: String, setIgnored: (String) -> Unit) {
    SettingsSection("隐私边界", "工作敏感仓库放入“仅本地”；忽略目录不会进入索引") {
        OutlinedTextField(localOnly, setLocalOnly, Modifier.fillMaxWidth(), label = { Text("仅本地目录（每行一个）") }, minLines = 4)
        OutlinedTextField(ignored, setIgnored, Modifier.fillMaxWidth(), label = { Text("额外忽略目录（每行一个）") }, minLines = 3)
        SettingsActionRow {
            Button(onClick = {
                Log.i("保存隐私边界设置：仅本地=${localOnly.lines().count { it.isNotBlank() }}行 忽略额外=${ignored.lines().count { it.isNotBlank() }}行，触发全量重建")
                store.settings = store.settings.copy(localOnlyExtra = localOnly.lines().map { it.trim() }.filter { it.isNotEmpty() }, ignoredExtra = ignored.lines().map { it.trim() }.filter { it.isNotEmpty() }); store.saveSettings(); store.rescan(full = true)
            }) { Text("保存并重建索引") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatusChip("全索引", Theme.OkGreen); StatusChip("仅本地 🔒", Theme.WarnOrange); StatusChip("忽略", Theme.Muted) }
    }
}

@Composable
private fun SettingsReview(onOpenParams: () -> Unit) {
    SettingsSection("复习节奏", "FSRS 参数影响每日新题与复习题的安排") {
        SettingsEntryRow("记忆参数", "FSRS 调度器", Theme.Accent, onOpenParams)
    }
}

@Composable
private fun SettingsRecording(store: AppStore) {
    val recDir = store.settings.recordingSaveDir.ifBlank { File(System.getProperty("user.home"), "Videos/Screencasts").absolutePath }
    var bitrate by remember { mutableStateOf(store.settings.recordingBitrate.toString()) }
    SettingsSection("保存位置", "录屏脚本会读取这里的目录和编码参数") {
        Text(recDir, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Theme.MdH2)
        SettingsActionRow {
            Button(onClick = {
                val chooser = JFileChooser(File(recDir)); chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; chooser.dialogTitle = "选择录屏保存目录"
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) { val path = chooser.selectedFile.absolutePath; Log.i("设置录屏保存目录 → $path"); store.settings = store.settings.copy(recordingSaveDir = path); store.saveSettings() }
            }) { Text("选择目录") }
            OutlinedButton(onClick = {
                if (!File(RECORD_GUI).isFile) { Log.e("录屏脚本不存在：$RECORD_GUI"); return@OutlinedButton }
                try { ProcessBuilder(RECORD_GUI).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); Log.i("已启动录屏界面") } catch (e: Exception) { Log.e("启动录屏界面失败", e) }
            }) { Text("打开录屏界面") }
        }
    }
    SettingsSection("编码参数", "默认 15 fps 更适合喂给 AI 分析；码率越高文件越大") {
        ChipSelector(listOf("10" to "10 fps", "15" to "15 fps", "24" to "24 fps", "30" to "30 fps"), store.settings.recordingFps.toString()) { value, _ -> store.settings = store.settings.copy(recordingFps = value.toInt()); store.saveSettings() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(bitrate, { bitrate = it }, Modifier.width(160.dp), label = { Text("码率 kbps") })
            Button(onClick = { val v = bitrate.toIntOrNull()?.coerceIn(500, 20000) ?: 4000; bitrate = v.toString(); Log.i("设置录屏码率 → ${v}kbps"); store.settings = store.settings.copy(recordingBitrate = v); store.saveSettings() }) { Text("保存码率") }
        }
    }
}

@Composable
private fun SettingsAbout() {
    SettingsSection("本地优先", "Atlas 不托管你的笔记，也不要求联网") {
        Text("配置与数据库：${System.getProperty("user.home")}/.local/share/atlas", fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text("缓存可随时删除重建，你的笔记只在自己的知识库里。", fontSize = 12.sp, color = Theme.Muted)
        Text("Atlas · 零模型 · 零网络 · AGPL-3.0", fontWeight = FontWeight.SemiBold, color = Theme.MdH2)
        Text("出题、批改等 AI 能力由你的编码代理完成；Atlas 负责检索、复习调度与学习纪律。", fontSize = 12.sp, color = Theme.Muted)
    }
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

/** 设置分区：用单层容器承载一组相关控件，避免设置页出现层层嵌套的卡片。 */
@Composable
internal fun SettingsSection(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val ui = atlasUiTokens()
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Elevated,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = ui.typography.sectionTitle, color = Theme.MdH2)
            if (!description.isNullOrBlank()) Text(description, fontSize = 12.sp, color = Theme.Muted)
            content()
        }
    }
}

@Composable
private fun SettingsGroup(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Theme.MdH2)
        if (!description.isNullOrBlank()) Text(description, fontSize = 11.sp, color = Theme.Muted)
        content()
    }
}

@Composable
internal fun SettingsActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
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
                    .background(if (active) Theme.Selected else Color.Transparent, MaterialTheme.shapes.small)
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
            .background(if (active) Theme.Selected else Color.Transparent)
            .border(
                1.dp,
                if (active) Theme.Accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp).clip(MaterialTheme.shapes.small)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(spec.background))
            Box(Modifier.weight(1f).fillMaxHeight().background(spec.surface))
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small)) {
            listOf(spec.mdH1, spec.mdH2, spec.mdLink, spec.okGreen, spec.mdInlineCode)
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

/** 自定义主题编辑器：浅色、深色分别编辑，完整 V2 记录始终原子写回。 */
@Composable
internal fun ThemeEditDialog(store: AppStore, themeName: String, onDismiss: () -> Unit) {
    val custom = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }
    val target = custom.firstOrNull { it.name == themeName }
    if (target == null) {
        onDismiss()
        return
    }
    var draft by remember(target.name) { mutableStateOf(target) }
    var editingDark by remember(target.name) { mutableStateOf(store.settings.darkTheme) }
    val ui = atlasUiTokens()
    var editing by remember(target.name, editingDark) { mutableStateOf<List<String>?>(null) }
    var problem by remember(target.name, editingDark) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val spec = draft.spec(editingDark)
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
        val blank = next.indexOfFirst { it.isBlank() }
        if (blank >= 0) {
            problem = "${ThemeSpec.LABELS[blank]} 是必填项，不能留空"
            return
        }
        val parsed = ThemeSpec.fromHexList(next)
        if (parsed == null) {
            problem = "配色不完整，暂未保存"
            return
        }
        problem = null
        draft = if (editingDark) draft.copy(dark = parsed) else draft.copy(light = parsed)
        store.settings = store.settings.copy(
            customThemes = store.settings.customThemes.map { raw2 ->
                if (CustomTheme.nameOf(raw2) == target.name) draft.encode() else raw2
            },
        )
        store.saveSettings()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(520.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("编辑主题 · ${target.name}", style = ui.typography.sectionTitle, color = Theme.MdH1)
                Text("浅色与深色独立保存；改动立即生效。", fontSize = 11.sp, color = Theme.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "浅色", true to "深色").forEach { (dark, label) ->
                        val active = editingDark == dark
                        Text(
                            label,
                            Modifier
                                .clickable {
                                    editingDark = dark
                                    editing = null
                                    problem = null
                                }
                                .background(
                                    if (active) Theme.Selected else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            color = if (active) Theme.Accent else Theme.Muted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                var group by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("界面配色" to 0, "Markdown 配色" to 1).forEach { (label, idx) ->
                        Text(
                            label,
                            Modifier
                                .clickable { group = idx }
                                .background(
                                    if (group == idx) Theme.Selected else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            fontSize = 13.sp,
                            color = if (group == idx) Theme.Accent else Theme.Muted,
                        )
                    }
                }
                Text(
                    if (group == 0) "三级表面与状态色按用途命名。"
                    else "标题、链接、引用与代码分别使用独立语义色。",
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
                            themeName = if (store.settings.themeName == target.name) AtlasThemes.NAME else store.settings.themeName,
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
        val here = parseHexColor(hexes[idx]) ?: spec.hexList().getOrNull(idx)?.let(::parseHexColor) ?: Color.White
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

internal fun createCustomTheme(name: String): CustomTheme =
    CustomTheme(name.trim(), AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark)

/** 新建自定义主题：固定复制 Atlas 的完整浅深双模式。 */
@Composable
internal fun CreateThemeDialog(store: AppStore, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val existing = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }.map { it.name }.toSet()
    var name by remember { mutableStateOf("") }
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
                Text("将复制 Atlas 的浅色与深色配色，创建后可分别编辑。", fontSize = 12.sp, color = Theme.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeSwatch("Atlas", "浅色", AtlasThemes.ATLAS.light, false, {}, Modifier.weight(1f))
                    ThemeSwatch("Atlas", "深色", AtlasThemes.ATLAS.dark, false, {}, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        enabled = valid,
                        onClick = {
                            val theme = createCustomTheme(trimmed)
                            store.settings = store.settings.copy(
                                customThemes = store.settings.customThemes + theme.encode(),
                                themeName = trimmed,
                            )
                            store.saveSettings()
                            Log.i("基于 Atlas 新建自定义主题 → $trimmed")
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
                            themeName = if (store.settings.themeName == name) AtlasThemes.NAME else store.settings.themeName,
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
