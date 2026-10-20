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

/**
 * Codex 登录修复：处置桌面启动器钉死的代理失效后「Token exchange failed:
 * error sending request」登录失败、界面转圈的本机故障。
 * 检测与修复逻辑在 atlas/scripts/fix_codex_proxy.py（实测候选代理 → 同步
 * chatgpt/trae 启动器 → 带新代理重启 ChatGPT），此处只展示输出。
 */
@Composable
fun CodexProxyCard(store: AppStore) {
    val run = store.codexProxyRun.value

    // 进卡片自动检测一次（仅首次：检测要对每个失效候选等满超时，约半分钟，不随切页签反复跑）
    LaunchedEffect(Unit) {
        if (!run.running && run.exitCode == null) store.checkCodexProxy()
    }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("运行状态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (run.running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(if (run.checkOnly) "检测中…" else "修复中…", fontSize = 12.sp, color = Theme.Accent)
                }
                OutlinedButton(onClick = store::checkCodexProxy, enabled = !run.running) { Text("重新检测", fontSize = 12.sp) }
            }

            if (!run.running && run.exitCode != null) {
                val ok = run.exitCode == 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Theme.OkGreen else Theme.BadRed))
                    Text(
                        run.lines.lastOrNull { it.startsWith("结论:") }?.removePrefix("结论:")?.trim()
                            ?: if (ok) "完成" else "未成功",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = store::repairCodexProxy, enabled = !run.running) { Text("一键修复") }
                OutlinedButton(onClick = store::checkCodexProxy, enabled = !run.running) { Text("仅检测", fontSize = 12.sp) }
            }

            Text(
                "适用症状：Codex/ChatGPT 登录报「Token exchange failed: error sending request」、" +
                    "或界面一直转圈——桌面启动器里钉死的旧代理失效所致。" +
                    "一键修复 = 实测各候选代理 → 同步 chatgpt/trae 启动器 → 带新代理重启 ChatGPT；" +
                    "重启会关闭当前 ChatGPT 窗口，会话不受影响。",
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
