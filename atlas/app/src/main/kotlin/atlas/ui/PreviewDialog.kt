package atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import atlas.AppStore
import atlas.core.DocMarker
import atlas.core.Log
import atlas.core.Tier
import java.awt.Desktop
import java.io.File

/** 检索命中（Ctrl+K 浮层与上下文包共用） */
data class IndexerHit(val path: String, val section: String, val snippet: String)

/**
 * 预览浮层（原知识库页的预览能力，现按需弹出）：
 * 复习卡「跳回原文」、Ctrl+K 打开命中时出现。提供 就地出题 / 复盘条目提议。
 */
@Composable
fun PreviewDialog(store: AppStore, relPath: String, onDismiss: () -> Unit) {
    val ui = atlasUiTokens()
    val note = store.notes.firstOrNull { it.relPath == relPath }
    var text by remember(relPath) { mutableStateOf("") }
    LaunchedEffect(relPath) {
        // UI 线程同步读整个文件；二进制/大文件是卡死高发点，日志记录大小与耗时
        text = Log.uiOp("预览浮层读取 $relPath", warnMs = 100) {
            store.indexer?.readFile(relPath, File(store.settings.libraryPath)) ?: ""
        }
        Log.i("预览待渲染 $relPath 共 ${text.length} 字符")
    }
    Dialog(onDismissRequest = { Log.d("预览浮层关闭 $relPath"); onDismiss() }) {
        Surface(
            Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.medium, tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(ui.spacing.page), verticalArrangement = Arrangement.spacedBy(ui.spacing.section)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(relPath, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    if (note?.tier == Tier.LOCAL_ONLY) StatusChip("仅本地 🔒", Theme.WarnOrange)
                    OutlinedButton(onClick = {
                        Log.i("系统编辑器打开 $relPath")
                        try { Desktop.getDesktop().open(File(store.settings.libraryPath, relPath)) }
                        catch (e: Exception) { Log.e("系统编辑器打开失败 $relPath", e); store.showToast("打开失败：${e.message}") }
                    }) { Text("编辑器打开") }
                    OutlinedButton(onClick = {
                        store.actionCardgen(relPath, "请通读该笔记，出 3–8 道题目候选，每题只写题目和答案")
                    }) { Text("让 AI 出题") }
                    Text("关闭", Modifier.clickable(onClick = onDismiss), fontSize = 13.sp, color = Theme.Muted)
                }
                if (note?.marker == DocMarker.RETROSPECTIVE || note?.marker == DocMarker.PROJECT_EXPERIENCE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusChip(if (note.marker == DocMarker.RETROSPECTIVE) "复盘" else "项目经验", Theme.OkGreen)
                        Text("让 AI 通读并提议题目", Modifier.clickable { store.proposeFromRetro(note, asCards = true) }, fontSize = 12.sp, color = Theme.Accent)
                    }
                }
                VDivider()
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    MarkdownText(text, style = store.settings.markdownStyle)
                }
            }
        }
    }
}
