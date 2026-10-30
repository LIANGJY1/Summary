package atlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

        // 命令控制区（验收卡 + 按钮组）整体收进滚动区，只占 1/3 高；
        // 详细验收列表用紧凑手风琴行：默认一行一条，点行展开判定标准，15 条不再被厚卡淹没。
        // 改造前先跑一遍存基线，改造后逐条对照。命令输出控制台固定占 2/3（输出才是本页主角）。
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val detailed = AaosDebug.presets.filter { it.expectation != null }
            if (detailed.isNotEmpty()) {
                var expandedPreset by remember { mutableStateOf<String?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${detailed.first().group} · 验收", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Surface(shape = RoundedCornerShape(999.dp), color = Theme.Selected) {
                            Text(
                                "${detailed.size} 条",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = Theme.Accent,
                            )
                        }
                        Text("建议顺序：分辨率基数 → 遮挡标志翻转 → SF 图层列表 → 生命周期/内存 → 条带取证", fontSize = 10.sp, color = Theme.Muted)
                    }
                    detailed.forEachIndexed { index, preset ->
                        val expanded = expandedPreset == preset.label
                        AcceptanceRow(
                            index = index + 1,
                            preset = preset,
                            expanded = expanded,
                            running = run.running,
                            onToggle = { expandedPreset = if (expanded) null else preset.label },
                            onRun = {
                                command = preset.command
                                store.runAaosCommand(preset.command)
                            },
                            onFill = { command = preset.command },
                        )
                    }
                }
            }

            AaosDebug.presets.filter { it.expectation == null }.groupBy { it.group }.forEach { (group, presets) ->
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
        }

        // 命令输入行固定在滚动区与输出台之间：滚动看验收卡/翻输出时输入不消失
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
            modifier = Modifier.weight(2f).fillMaxWidth(),
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
                LazyColumn(state = outputScroll, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(run.lines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

/** 详细预设紧凑行（手风琴）：序号 + 名称 + 一句话说明 + 运行/填入；点行展开判定标准（改造前/后对照按语义着色）。 */
@Composable
private fun AcceptanceRow(
    index: Int,
    preset: AaosDebug.Preset,
    expanded: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onFill: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable(onClick = onToggle),
        shape = RoundedCornerShape(9.dp),
        color = if (expanded) Theme.Selected.copy(alpha = 0.5f) else Theme.CodeBlock.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("%02d".format(index), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Theme.Accent)
                Text(
                    preset.label,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = onRun,
                    enabled = !running,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp),
                ) { Text("运行", fontSize = 11.sp) }
                TextButton(
                    onClick = onFill,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
                ) { Text("填入", fontSize = 11.sp) }
                Text(if (expanded) "▾" else "▸", fontSize = 10.sp, color = Theme.Muted)
            }
            Text(
                preset.description,
                modifier = Modifier.padding(start = 22.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Theme.Muted,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                preset.expectation?.let { expectation ->
                    Surface(shape = RoundedCornerShape(7.dp), color = Theme.CodeBlock) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            expectation.lineSequence().forEach { line ->
                                val color = when {
                                    line.startsWith("改造后") || line.startsWith("通过") -> Theme.OkGreen
                                    line.startsWith("异常") || line.startsWith("若") -> Theme.BadRed
                                    else -> Theme.Muted
                                }
                                Text(
                                    line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = color,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
