package atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.AaosDebug

/** AAOS 设备命令调试；输出与运行状态由 AppStore 持有。 */
@Composable
fun AaosDebugView(store: AppStore, modifier: Modifier = Modifier) {
    val run = store.aaosRunner.state.value
    var command by remember { mutableStateOf("") }
    var deviceMenu by remember { mutableStateOf(false) }
    var followOutput by remember { mutableStateOf(true) }
    val outputScroll = rememberLazyListState()

    LaunchedEffect(Unit) { store.refreshToolboxDevices() }
    LaunchedEffect(run.lines, followOutput) {
        if (followOutput && run.lines.isNotEmpty()) outputScroll.scrollToItem(run.lines.lastIndex)
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("目标设备", fontSize = 12.sp, color = Theme.Muted)
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { deviceMenu = true }) {
                    Text(store.toolboxSerial ?: "未检测到设备", fontSize = 12.sp)
                }
                DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                    if (store.toolboxDevices.isEmpty()) {
                        DropdownMenuItem(text = { Text("无在线设备") }, onClick = { deviceMenu = false })
                    }
                    store.toolboxDevices.forEach { serial ->
                        DropdownMenuItem(
                            text = { Text(serial) },
                            onClick = { store.toolboxSerial = serial; deviceMenu = false },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { store.refreshToolboxDevices() }) { Text("刷新设备") }
            Text("命令自动绑定所选设备", fontSize = 12.sp, color = Theme.Muted)
        }

        AaosDebug.presets.groupBy { it.group }.forEach { (group, presets) ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(group, modifier = Modifier.width(64.dp).padding(top = 8.dp), fontSize = 12.sp, color = Theme.Muted)
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                command = preset.command
                                store.runAaosCommand(preset.command)
                            },
                            enabled = !run.running,
                        ) {
                            Text(preset.label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 72.dp),
            label = { Text("设备调试命令（可编辑）") },
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { store.runAaosCommand(command) },
                enabled = !run.running && command.isNotBlank(),
            ) { Text("运行自定义命令") }
            OutlinedButton(onClick = store::stopAaosCommand, enabled = run.running) { Text("停止") }
            if (run.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                when {
                    run.running -> "执行中…"
                    run.exitCode == 0 -> "已完成 · exit 0"
                    run.exitCode == -2 -> "已停止"
                    run.exitCode != null -> "执行失败 · exit ${run.exitCode}"
                    else -> "尚未运行"
                },
                fontSize = 12.sp,
                color = if (run.exitCode != null && run.exitCode !in listOf(0, -2)) Theme.BadRed else Theme.Muted,
            )
        }

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            color = Theme.CodeBlock,
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (run.command.isBlank()) "命令输出" else "命令输出 · ${run.command.lineSequence().first().take(90)}",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.Muted,
                        maxLines = 1,
                    )
                    TextButton(onClick = { followOutput = !followOutput }) {
                        Text(if (followOutput) "暂停跟随" else "跟随输出", fontSize = 11.sp)
                    }
                }
                LazyColumn(state = outputScroll, modifier = Modifier.fillMaxSize()) {
                    items(run.lines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
