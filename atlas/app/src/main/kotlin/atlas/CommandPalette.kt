package atlas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import atlas.core.Log
import atlas.ui.IndexerHit
import atlas.ui.Theme

/** Ctrl+K 全局搜索浮层（PRD FR-B3）：输入即搜，↑↓ 选择，回车打开预览浮层。
 *  平台无关（Android 端顶栏「搜索」按钮同样唤起），故从桌面 Main.kt 拆出为共享文件。 */
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
            color = Theme.Panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)),
            tonalElevation = 3.dp,
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
                                .background(if (i == idx) Theme.Selected else Color.Transparent)
                                .padding(6.dp),
                        ) {
                            Text("${h.path}  ##${h.section}", fontSize = 11.sp, color = Theme.Info, fontFamily = FontFamily.Monospace, maxLines = 1)
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
