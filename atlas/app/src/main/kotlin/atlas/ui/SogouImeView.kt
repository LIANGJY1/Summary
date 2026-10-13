package atlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.SogouIme

/**
 * 输入法修复：处置搜狗输入法（fcitx 架构）引擎崩溃后"切不出中文、候选浮窗调不起来"的本机故障。
 * 状态检测与命令输出由 AppStore 持有（imeRun/imeStatus），进卡片自动检测一次。
 */
@Composable
fun SogouImeCard(store: AppStore) {
    val run = store.imeRun.value
    val status = store.imeStatus.value

    LaunchedEffect(Unit) { store.checkImeStatus() }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("运行状态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (run.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                OutlinedButton(onClick = store::checkImeStatus, enabled = !run.running) { Text("重新检测", fontSize = 12.sp) }
            }

            when {
                status == null -> Text("正在检测…", fontSize = 12.sp, color = Theme.Muted)
                !status.installed -> Text(SogouIme.Status(false, false, false, false).advice(), fontSize = 12.sp, color = Theme.WarnOrange)
                else -> {
                    StatusLine("搜狗引擎 sogoupinyin-service", status.serviceRunning)
                    StatusLine("看门狗 sogoupinyin-watchdog", status.watchdogRunning)
                    StatusLine("fcitx 输入法框架", status.fcitxRunning)
                    Text(status.advice(), fontSize = 12.sp, color = Theme.Muted)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = store::repairIme, enabled = !run.running && status?.installed != false) { Text("一键修复") }
                OutlinedButton(onClick = { store.runImeStep(SogouIme.startWatchdogCommand()) }, enabled = !run.running) { Text("仅拉起看门狗", fontSize = 12.sp) }
                OutlinedButton(onClick = { store.runImeStep(SogouIme.startServiceCommand()) }, enabled = !run.running) { Text("仅拉起引擎", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { store.runImeStep(SogouIme.restartFcitxCommand(status?.fcitxRunning ?: true)) },
                    enabled = !run.running,
                ) { Text("重启 fcitx 框架", fontSize = 12.sp) }
            }

            Text(
                "适用症状：切不出中文、候选浮窗调不起来（fcitx 活着但搜狗引擎崩了）。" +
                    "一键修复 = 清残留 → 拉看门狗 → 拉引擎 → 重启 fcitx；修复后无需重启 Atlas。",
                fontSize = 11.sp, color = Theme.Muted,
            )

            if (run.lines.isNotEmpty()) {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = Theme.CodeBlock,
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        run.lines.takeLast(14).forEach { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Theme.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Theme.OkGreen else Theme.BadRed))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (ok) "运行中" else "未运行", fontSize = 12.sp, color = if (ok) Theme.OkGreen else Theme.BadRed)
    }
}
