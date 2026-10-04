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
fun SettingsView(store: AppStore, onOpenColors: () -> Unit = {}, onOpenTypography: () -> Unit = {}) {
    val ui = atlasUiTokens()
    var localOnly by remember { mutableStateOf(store.settings.localOnlyExtra.joinToString("\n")) }
    var ignored by remember { mutableStateOf(store.settings.ignoredExtra.joinToString("\n")) }
    var sourceQuestionPaths by remember { mutableStateOf(store.settings.sourceQuestionPaths.joinToString("\n")) }
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
                        SettingsDestination.APPEARANCE -> SettingsAppearance(store, onOpenColors, onOpenTypography, clickAnswerToEdit, { clickAnswerToEdit = it })
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
private fun SettingsAppearance(store: AppStore, onOpenColors: () -> Unit, onOpenTypography: () -> Unit, clickAnswerToEdit: Boolean, setClickAnswerToEdit: (Boolean) -> Unit) {
    SettingsSection("主题与阅读", "让界面在长时间阅读时保持清晰、克制") {
        SettingsEntryRow("配色主题", store.settings.themeName.ifBlank { AtlasThemes.DEFAULT.name }, resolveTheme(store.settings).accent, onOpenColors)
        SettingsGroup("深浅色") {
            ChipSelector(listOf("light" to "浅色", "dark" to "深色"), store.settings.theme) { value, label ->
                Log.i("切换深浅色 → $label"); store.settings = store.settings.copy(theme = value); store.saveSettings()
            }
        }
        // 字号/行距五组细调项封装进二级页，此处只留一个入口（§6.4.25）
        SettingsEntryRow(
            "字号与行距",
            "全局 ${store.settings.globalFontSize} sp · 内容 ${store.settings.contentFontSize} sp · 行距 ${store.settings.contentLineHeight}%",
            resolveTheme(store.settings).accent,
            onOpenTypography,
        )
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


