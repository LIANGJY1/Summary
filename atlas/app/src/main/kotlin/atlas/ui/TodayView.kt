package atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.widthIn(max = ui.contentMaxWidth).fillMaxWidth().fillMaxHeight()
                .align(Alignment.TopCenter).verticalScroll(rememberScrollState()).padding(ui.spacing.page),
            verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
        ) {
            Text("工作台", style = ui.typography.pageTitle)
            Text("只显示现在需要处理的事项。", style = ui.typography.secondary, color = Theme.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("待处理", actions.sumOf { it.count }, Theme.Accent, Modifier.weight(1f))
                MetricTile("待确认", inbox, Theme.WarnOrange, Modifier.weight(1f))
                MetricTile("题目总数", store.sourceQuestions.size, Theme.MdH2, Modifier.weight(1f))
            }
            if (actions.isEmpty()) {
                AtlasPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("今天没有待处理事项", style = ui.typography.sectionTitle)
                        Text("新内容或题目进入队列后，会在这里显示下一步动作。", style = ui.typography.secondary, color = Theme.Muted)
                    }
                }
            } else {
                Text("下一步", style = ui.typography.sectionTitle)
                actions.forEach { item ->
                    WorkbenchRow(item) { onNavigate(item.target) }
                }
            }
            VDivider()
            if (inbox > 0) {
                Text("待确认内容", style = ui.typography.sectionTitle)
                CandidateList(store)
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    AtlasPanel(modifier, color = Theme.Panel, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontSize = 11.sp, color = Theme.Muted)
            Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun WorkbenchRow(item: WorkbenchAction, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
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
