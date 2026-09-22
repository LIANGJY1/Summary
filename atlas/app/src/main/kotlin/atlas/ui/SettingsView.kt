package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import atlas.core.Log
import atlas.core.SourceQuestions
import atlas.core.Tier
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.roundToInt

@Composable
fun SettingsView(store: AppStore) {
    var localOnly by remember { mutableStateOf(store.settings.localOnlyExtra.joinToString("\n")) }
    var ignored by remember { mutableStateOf(store.settings.ignoredExtra.joinToString("\n")) }
    var sourceQuestionPaths by remember { mutableStateOf(store.settings.sourceQuestionPaths.joinToString("\n")) }
    var fontScale by remember { mutableStateOf(store.settings.fontScale) }
    var showParams by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        // 复习
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("复习", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Log.d("打开 FSRS 参数"); showParams = true }) { Text("记忆参数（FSRS）") }
            }
        }

        VDivider()

        // 外观
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("外观", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    val active = store.settings.theme == value
                    Text(
                        label,
                        Modifier.clickable {
                            if (!active) {
                                Log.i("切换主题 → $label")
                                store.settings = store.settings.copy(theme = value)
                                store.saveSettings()
                            }
                        }
                            .background(if (active) Theme.Accent.copy(alpha = 0.18f) else Color.Transparent, MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        fontSize = 13.sp,
                        color = if (active) Theme.Accent else Theme.Muted,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Text("即点即生效，选择会记住；下次启动沿用。", fontSize = 11.sp, color = Theme.Muted)
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
        }

        VDivider()

        // 库
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }

        VDivider()

        // 题目源文档
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("题目源文档", fontWeight = FontWeight.SemiBold)
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

        VDivider()

        // 三档边界
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("隐私边界", fontWeight = FontWeight.SemiBold)
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

        VDivider()

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("应用数据", fontWeight = FontWeight.SemiBold)
            Text(
                "配置与数据库：${System.getProperty("user.home")}/.local/share/atlas（缓存可随时删除重建，你的笔记只在你库里）",
                fontSize = 12.sp,
            )
        }

        VDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("关于", fontWeight = FontWeight.SemiBold)
            Text(
                "Atlas · 零模型 · 零网络 · AGPL-3.0\n" +
                    "出题、批改等 AI 能力由你的编码代理完成；Atlas 负责检索、复习调度与学习纪律。",
                fontSize = 12.sp, color = Theme.Muted,
            )
        }
    }
    if (showParams) FsrsParamsDialog { showParams = false }
}
