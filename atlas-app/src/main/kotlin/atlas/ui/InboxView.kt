package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Inbox
import atlas.core.Log

/**
 * 待确认候选列表（原收件箱页主体，现内嵌于「工作台」页）。
 * 确认 = 将题目与答案写入 questions.md 并从候选文件移除该块；丢弃 = 仅移除不入库。
 */
@Composable
fun CandidateList(store: AppStore) {
    var editing by remember { mutableStateOf<Inbox.Candidate?>(null) }
    if (store.candidates.isEmpty()) {
        Text("没有待确认的题目。让 AI 出题后会出现在这里，逐条过目才入库。", color = Theme.Muted, fontSize = 12.sp)
        return
    }
    // 按文件分组，支持整批忽略（PRD FR-E1：删除整个候选文件）
    val byFile = store.candidates.groupBy { it.file }
    val skills = Inbox.skillNames(store.candidates)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.Accent.copy(alpha = 0.08f), MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("生成 Skill", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Theme.Accent)
            skills.forEach { skill ->
                Text(skill, fontSize = 12.sp, color = Theme.Accent)
                Text("打开编辑", Modifier.clickable { store.openSkillEditor(skill) }, fontSize = 11.sp, color = Theme.Accent)
            }
            if (skills.isEmpty()) Text("未标注", fontSize = 11.sp, color = Theme.Muted)
        }
        byFile.forEach { (file, cs) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${file.name}（${cs.size} 条）", fontSize = 11.sp, color = Theme.Muted, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text("全部忽略", Modifier.clickable {
                        Log.i("收件箱整批忽略 ${file.name}（${cs.size} 条）")
                        file.delete(); store.scanInbox()
                    }, fontSize = 11.sp, color = Theme.BadRed)
                }
            cs.forEach { c ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), MaterialTheme.shapes.small).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip("题目", Theme.Accent)
                        Text(c.file.name, fontSize = 10.sp, color = Theme.Muted, maxLines = 1)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("题目：${candidateQuestion(c)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("答案：${candidateAnswer(c).take(200)}", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("✓ 确认入库", Modifier.clickable {
                            Log.i("收件箱确认 kind=${c.kind} file=${c.file.name}")
                            store.confirmCandidateAsQuestion(c)
                            store.scanInbox()
                        }, fontSize = 13.sp, color = Theme.OkGreen, fontWeight = FontWeight.Bold)
                        Text("✎ 编辑", Modifier.clickable { Log.d("打开候选编辑对话框 kind=${c.kind}"); editing = c }, fontSize = 13.sp, color = Theme.Accent)
                        Text("✕ 丢弃", Modifier.clickable { Log.i("收件箱丢弃 kind=${c.kind} file=${c.file.name}"); Inbox.removeBlock(c); store.scanInbox() }, fontSize = 13.sp, color = Theme.BadRed)
                    }
                    VDivider()
                }
            }
        }
    }
    editing?.let { c ->
        EditCandidateDialog(store, c) { editing = null; store.scanInbox() }
    }
}

private fun candidateQuestion(c: Inbox.Candidate): String =
    c.s("q").ifBlank { c.s("front") }.ifBlank { c.s("title") }.ifBlank { "（未填写题目）" }

private fun candidateAnswer(c: Inbox.Candidate): String =
    c.s("answer").ifBlank { c.s("back") }.ifBlank { c.s("ref") }.ifBlank { "（未填写答案）" }

@Composable
fun EditCandidateDialog(store: AppStore, c: Inbox.Candidate, onDone: () -> Unit) {
    var title by remember { mutableStateOf(candidateQuestion(c)) }
    var answer by remember { mutableStateOf(candidateAnswer(c)) }
    var ref by remember { mutableStateOf(c.s("ref")) }
    var tags by remember { mutableStateOf(c.s("tags")) }
    AlertDialog(
        onDismissRequest = onDone, title = { Text("编辑题目") },
        confirmButton = {
            Button(onClick = {
                val edited = c.copy(fields = c.fields.toMutableMap().apply {
                    put("kind", "question")
                    put("q", title)
                    put("answer", answer)
                    put("ref", ref)
                    put("tags", tags)
                })
                store.confirmCandidateAsQuestion(edited)
                onDone()
            }) { Text("确认入库") }
        },
        dismissButton = { OutlinedButton(onClick = { Inbox.removeBlock(c); onDone() }) { Text("丢弃") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("题目") })
                OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), label = { Text("答案") }, minLines = 3)
                OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth(), label = { Text("参考要点") }, minLines = 2)
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔）") })
            }
        },
    )
}
