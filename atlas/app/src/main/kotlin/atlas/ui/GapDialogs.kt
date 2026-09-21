package atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.MdStores.QuestionEntry
import atlas.core.QuestionTags

@Composable
fun ImportQuestionsDialog(store: AppStore, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("导入题单（每行一题）") },
        confirmButton = { Button(onClick = { store.addQuestionsFromText(text, ref); onDismiss() }) { Text("入库") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("题目列表（换行分隔）") }, minLines = 6)
                OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth(), label = { Text("参考要点/出处（可选）") })
            }
        },
    )
}

@Composable
fun EditQuestionDialog(store: AppStore, q: QuestionEntry, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(q.q) }
    var answer by remember { mutableStateOf(q.answer) }
    var ref by remember { mutableStateOf(q.ref) }
    var status by remember { mutableStateOf(q.status) }
    var tagsText by remember { mutableStateOf(q.tags.joinToString(", ")) }
    val statuses = listOf("未测", "待复测", "已稳定")
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("编辑题目") },
        confirmButton = {
            Button(onClick = {
                if (text.isNotBlank()) store.updateQuestion(q.copy(q = text.trim(), answer = answer.trim(), ref = ref.trim(), status = status, tags = QuestionTags.normalize(tagsText.split(',', '，'))))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("题面") }, minLines = 2)
                OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), label = { Text("答案（完整答案，复习与转闪卡用它）") }, minLines = 4)
                OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth(), label = { Text("参考要点（可选）") }, minLines = 2)
                OutlinedTextField(tagsText, { tagsText = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔，可自定义）") }, placeholder = { Text("Android系统启动, Handler") })
                Text("常用标签", fontSize = 11.sp, color = atlas.ui.Theme.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    QuestionTags.BUILT_IN.forEach { tag ->
                        val selectedTag = tag in QuestionTags.normalize(tagsText.split(',', '，'))
                        Text(tag, Modifier
                            .clickable {
                                val current = QuestionTags.normalize(tagsText.split(',', '，')).toMutableList()
                                if (selectedTag) current.remove(tag) else current.add(tag)
                                tagsText = current.joinToString(", ")
                            }
                            .padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 10.sp,
                            color = if (selectedTag) atlas.ui.Theme.Accent else atlas.ui.Theme.Muted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("状态:", fontSize = 12.sp)
                    statuses.forEach { s ->
                        RadioButton(selected = status == s, onClick = { status = s })
                        Text(s, fontSize = 11.sp)
                    }
                }
            }
        },
    )
}
