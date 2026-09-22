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
    val ui = atlasUiTokens()
    val untested = store.sourceQuestions.size
    val retest = 0
    val inbox = store.candidates.size
    val actions = workbenchActions(0, untested, retest, inbox)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ui.spacing.page),
        verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
    ) {
        // 摘要区和详情区共用同一个页面滚动容器。
            Text("工作台", style = ui.typography.pageTitle)
            Text("只显示现在需要处理的事项。", style = ui.typography.secondary, color = Theme.Muted)
            if (actions.isEmpty()) {
                Text("没有待处理事项。", style = ui.typography.body, color = Theme.Muted)
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
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.label, Modifier.weight(1f), fontSize = 14.sp)
            StatusChip("${item.count}", if (item.target == "题库") Theme.Accent else Theme.WarnOrange)
            OutlinedButton(onClick = onClick) { Text(item.action) }
        }
    }
}
