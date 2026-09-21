package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore

@Composable
fun DashboardSection(store: AppStore) {
    val m = store.metrics2()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                "复习完成率", if (m.dueEvents30 == 0) "—" else "${(m.completionRate * 100).toInt()}%",
                "近 30 天完成 ${m.reviewed30} / 到期 ${m.dueEvents30}，目标 ≥80%",
            )
            MetricCard("笔记被引用", "${m.citations}", "检索后引用条目的次数")
            MetricCard("当前到期卡", "${m.dueNow}", "今天该复习的卡")
        }
        Spacer(Modifier.height(6.dp))
        Text("近 30 天每日复习量", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        DailyBars(m.daily)
        Text(
            "完成率 = 实际评分次数 ÷ 到期卡数（按卡去重）；全部为本地数据。",
            fontSize = 11.sp, color = Theme.Muted,
        )
    }
}

@Composable
private fun DailyBars(daily: List<Pair<String, Int>>) {
    val max = (daily.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            daily.forEach { (day, n) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(day.substring(5), fontSize = 10.sp, color = Theme.Muted, modifier = Modifier.width(40.dp))
                Box(
                    Modifier.weight(1f).height(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                ) {
                    if (n > 0) {
                        Box(
                            Modifier.fillMaxWidth(fraction = n.toFloat() / max).height(10.dp)
                                .background(Theme.Accent, MaterialTheme.shapes.extraSmall)
                        )
                    }
                }
                Text("$n", fontSize = 10.sp, color = Theme.Muted, modifier = Modifier.width(28.dp))
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, note: String) {
    Column(Modifier.width(200.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), MaterialTheme.shapes.small).padding(12.dp)) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Theme.Accent)
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(note, fontSize = 10.sp, color = Theme.Muted)
    }
}
