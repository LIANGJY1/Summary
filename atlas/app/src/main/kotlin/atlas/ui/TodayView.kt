package atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore

/** 工作台：只保留需要处理的事项，不承担统计看板或内容浏览。 */
@Composable
fun TodayView(store: AppStore, onNavigate: (String) -> Unit) {
    val untested = store.sourceQuestions.size
    val retest = 0
    val inbox = store.candidates.size
    val actions = workbenchActions(0, untested, retest, inbox)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 摘要区和详情区共用同一个页面滚动容器。
            Text("工作台", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("只显示现在需要处理的事项。", fontSize = 12.sp, color = Theme.Muted)
            if (actions.isEmpty()) {
                Text("没有待处理事项。", fontSize = 14.sp, color = Theme.Muted)
            } else {
                actions.forEach { item ->
                    WorkbenchRow(item) { onNavigate(item.target) }
                }
            }
        VDivider()
            if (inbox > 0) {
                Text("待确认内容", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                CandidateList(store)
            }
    }
}

@Composable
private fun WorkbenchRow(item: WorkbenchAction, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.label, Modifier.weight(1f), fontSize = 14.sp)
        StatusChip("${item.count}", if (item.target == "题库") Theme.Accent else Theme.WarnOrange)
        OutlinedButton(onClick = onClick) { Text(item.action) }
    }
}
