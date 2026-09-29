@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.DeviceTools
import atlas.core.FeishuCheckin
import atlas.core.Log
import atlas.core.Prompts
import atlas.core.Tools
import atlas.core.WmsParser
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 工具页：把日常本地小工具图形化集成，拖入文件即可运行（全部本地执行，零网络）。
 * 首个工具：27HM 车机日志解密（脚本随 Summary 仓分发）。
 */
/** 工具页分区（信息架构与设置中心同构：左侧分区导航 + 右侧内容区） */
enum class ToolsDestination(val title: String, val description: String) {
    LOG_DECRYPT("日志解密", "27HM 日志压缩包 / 目录 → 解压·解密·解压，一键出可读日志"),
    PHONE_AUTO("任务自动化", "adb 控制手机一键执行常用流程，无线优先、USB 兜底"),
    DEVICE_TOOLS("设备工具箱", "推送 · 重启 · 截屏 · 模拟器 · 日志，全部本地执行"),
    WMS_VIEWER("WMS 查看器", "窗口容器树查看与对比，排查窗口层级问题"),
    PROMPTS("提示词库", "常用提示词集中管理，一键复制给任意 AI"),
}

@Composable
fun ToolsView(store: AppStore, destination: ToolsDestination, onDestinationChange: (ToolsDestination) -> Unit) {
    val ui = atlasUiTokens()
    Row(Modifier.fillMaxSize()) {
        ToolsSidebar(destination, onDestinationChange)
        Box(
            Modifier.fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
        )
        Column(
            Modifier.weight(1f).fillMaxHeight()
                // 提示词库是工作台型工具：占满右侧可用区域，不做限宽与竖向滚动
                .then(if (destination == ToolsDestination.PROMPTS) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(ui.spacing.page),
            verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
        ) {
            Column(
                Modifier
                    .then(
                        if (destination == ToolsDestination.PROMPTS) Modifier.fillMaxSize()
                        else Modifier.widthIn(max = 980.dp).fillMaxWidth(),
                    )
                    .align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // 提示词库是工作台：不显示页头三行（工具/标题/描述），把空间全留给编辑区
                if (destination != ToolsDestination.PROMPTS) {
                    Text("工具", fontSize = 12.sp, color = Theme.Accent, fontWeight = FontWeight.SemiBold)
                    Text(destination.title, style = ui.typography.pageTitle)
                    Text(destination.description, fontSize = 13.sp, color = Theme.Muted)
                }
                when (destination) {
                    ToolsDestination.LOG_DECRYPT -> HcLogDecryptCard(store)
                    ToolsDestination.PHONE_AUTO -> FeishuCheckinCard(store)
                    ToolsDestination.DEVICE_TOOLS -> DeviceToolboxCard(store)
                    ToolsDestination.WMS_VIEWER -> WmsViewerCard(store)
                    ToolsDestination.PROMPTS -> PromptsCard(store, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 工具页左侧分区导航（样式同设置中心侧栏） */
@Composable
private fun ToolsSidebar(selected: ToolsDestination, onSelect: (ToolsDestination) -> Unit) {
    Column(Modifier.width(236.dp).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("工具", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Theme.MdH1)
            Text("Atlas 本地工具", fontSize = 12.sp, color = Theme.Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolsDestination.entries.forEach { item ->
                val active = item == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(item) }
                        .background(if (active) Theme.Selected else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (active) Theme.Accent else Theme.Muted.copy(alpha = 0.5f)))
                    Text(item.title, fontSize = 13.sp, color = if (active) Theme.Accent else Theme.Muted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("全部本地执行 · 零网络", fontSize = 11.sp, color = Theme.Muted)
    }
}

@Composable
private fun HcLogDecryptCard(store: AppStore) {
    val ui = atlasUiTokens()
    val dragHover = remember { mutableStateOf(false) }
    val run = store.hcToolRun.value

    // 系统文件拖入接收：走 CMP 官方外部拖放链路——框架的 AwtDragAndDropManager 已在窗口内容
    // 组件上挂了 AWT DropTarget，XDND 事件按组件边界分发到这里的 DragAndDropTarget。
    // 不能自挂 java.awt.dnd.DropTarget：会被框架在内容组件上的 DropTarget 拦下，收不到任何事件。
    val dropReceiver = remember(store) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragHover.value = true }
            override fun onExited(event: DragAndDropEvent) { dragHover.value = false }
            override fun onEnded(event: DragAndDropEvent) { dragHover.value = false }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragHover.value = false
                if (store.hcToolRun.value?.running == true) return false
                val paths = (event.dragData() as? androidx.compose.ui.draganddrop.DragData.FilesList)
                    ?.readFiles().orEmpty()
                val input = paths.firstOrNull()?.let { File(Tools.normalizeDroppedPath(it)) }
                return when {
                    input == null -> false
                    !Tools.isAcceptableInput(input) -> {
                        store.showToast("仅支持压缩包（zip/7z/tar/rar）或文件夹")
                        false
                    }
                    else -> {
                        Log.i("工具页：拖入 ${input.absolutePath}")
                        store.runHcLogTool(input.absolutePath)
                        true
                    }
                }
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 卡片头：绘制图标（避免字体缺字）+ 标题 + 一句话说明
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Theme.Selected) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { DropInIcon() }
                }
                Column {
                    Text("车机日志解密", style = ui.typography.itemTitle)
                    Text("27HM 日志压缩包 / 目录 → 解压·解密·解压，一键出可读日志", style = ui.typography.secondary, color = Theme.Muted)
                }
            }

            // 拖入区：运行中显示进度，否则显示拖放提示 + 文件选择
            val zoneColor = if (dragHover.value) Theme.Selected else Color.Transparent
            val borderColor = if (dragHover.value) Theme.Focus else MaterialTheme.colorScheme.outlineVariant
            Column(
                Modifier.fillMaxWidth()
                    .height(120.dp)
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { true },
                        target = dropReceiver,
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(zoneColor)
                    .drawBehind {
                        val radiusPx = 14.dp.toPx()
                        val path = Path().apply {
                            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radiusPx)))
                        }
                        drawPath(
                            path,
                            color = borderColor,
                            style = Stroke(
                                width = if (dragHover.value) 2.dp.toPx() else 1.2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 7.dp.toPx())),
                            ),
                        )
                    }
                    .clickable {
                        val chosen = chooseLogFile()
                        if (chosen != null) {
                            Log.i("工具页：选择 ${chosen.absolutePath}")
                            store.runHcLogTool(chosen.absolutePath)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (run?.running == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.Accent)
                        Text("正在处理（解压 → 解密 → 解压）…", style = ui.typography.body, color = Theme.Accent)
                    }
                    Text(shortenHome(run.inputPath), style = ui.typography.caption, color = Theme.Muted)
                } else {
                    Text(
                        if (dragHover.value) "松开即可开始" else "把日志压缩包或文件夹拖到这里",
                        style = ui.typography.body,
                        fontWeight = FontWeight.SemiBold,
                        color = if (dragHover.value) Theme.Accent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "支持 zip · 7z · tar · rar，或直接拖已解压的目录；点击此处也可选择",
                        style = ui.typography.caption,
                        color = Theme.Muted,
                    )
                }
            }

            // 运行结果：状态 + 摘要 + 打开输出目录 + 脚本输出尾部
            run?.let { r ->
                if (!r.running) {
                    val ok = r.exitCode == 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Theme.OkGreen else Theme.BadRed))
                        Text(
                            r.summary ?: if (ok) "完成" else "未成功",
                            style = ui.typography.secondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        r.outputDir?.let { dir ->
                            QuietTextAction("打开输出目录", onClick = { openDirectory(store, dir) })
                        }
                    }
                    Text(shortenHome(r.inputPath), style = ui.typography.caption, color = Theme.Muted)
                    if (r.tail.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBlock, tonalElevation = 1.dp) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                r.tail.forEach { line ->
                                    Text(
                                        line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = if (ok) Theme.Muted else Theme.BadRed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 任务自动化卡片（UI 一律中性文案，不出现具体业务字样）：
 * 一键执行，无线优先、USB 兜底；链路状态自动检测；配置与帮助默认折叠。
 * 逻辑在 core/FeishuCheckin；PIN 存独立 0600 文件，不经 settings.properties。
 */
@Composable
private fun FeishuCheckinCard(store: AppStore) {
    val ui = atlasUiTokens()
    val run = store.feishuRun.value
    val running = run?.running == true
    val link = store.feishuLink.value

    var advanced by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var manualChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(store.settings.feishuMode) }
    // ip 以 settings 为 key：USB 转无线在 store 侧落新地址后，输入框跟随刷新（不残留旧值）
    var ip by remember(store.settings.feishuIp) { mutableStateOf(store.settings.feishuIp) }
    var port by remember { mutableStateOf(store.settings.feishuPort.toString()) }
    var pin by remember { mutableStateOf(FeishuCheckin.readPin(store.configDir)) }
    val pinSet = pin.isNotBlank()

    // 卡片可见期间每 10s 刷新链路状态（离开工具页自动停止）
    LaunchedEffect(Unit) {
        while (isActive) {
            store.refreshFeishuLink()
            delay(10_000)
        }
    }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 卡片头：图标 + 标题 + 具体能力说明（不重复分区页头「任务自动化」）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Theme.Selected) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { RunTaskIcon() }
                }
                Column {
                    Text("一键执行", style = ui.typography.itemTitle)
                    Text("唤醒 · 解锁 · 进入目标页面 · 截图，全部本地执行", style = ui.typography.secondary, color = Theme.Muted)
                }
            }

            // 链路状态行：纯状态读数；重新检测归入下方动作行，避免右缘两行散排
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dotColor = when {
                    running -> Theme.Accent
                    link?.connected == true -> Theme.OkGreen
                    link == null -> Theme.Muted
                    else -> Theme.BadRed
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Text(
                    link?.describe() ?: "正在检测手机连接…",
                    style = ui.typography.secondary,
                    color = if (link?.connected == true) MaterialTheme.colorScheme.onSurface else Theme.Muted,
                )
            }

            // 无线一键配置：免理解无线调试的 IP/端口配对流程，插 USB 点一下即可（§6.4.18）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietTextAction(
                    if (store.feishuWirelessBusy.value) "转换中…" else "USB 一键转无线",
                    onClick = { store.feishuUsbToWireless() },
                    enabled = !running && !store.feishuWirelessBusy.value,
                )
                Text(
                    "没配过无线？插上 USB 数据线点一下：自动读取手机 Wi-Fi 地址并切到无线，无需手动填写",
                    style = ui.typography.caption,
                    color = Theme.Muted,
                    modifier = Modifier.weight(1f),
                )
            }

            // 首次使用：PIN 未配置时内联提示输入
            if (!pinSet) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinField(pin) { pin = it }
                    QuietTextAction("保存", onClick = {
                        store.saveFeishuPin(pin)
                        store.showToast("配置已保存")
                    })
                }
            }

            // 一键执行
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { store.runFeishuCheckin() }, enabled = !running) {
                    Text(if (running) "执行中…" else "开始执行")
                }
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.Accent)
                QuietTextAction(
                    "测试通路",
                    onClick = { store.runFeishuLinkTest() },
                    enabled = !running,
                )
                Text(
                    "只验证唤醒与应用启动，不进入目标页面",
                    style = ui.typography.caption,
                    color = Theme.Muted,
                )
                Spacer(Modifier.weight(1f))
                QuietTextAction(
                    if (manualChecking) "检测中…" else "重新检测",
                    onClick = {
                        manualChecking = true
                        scope.launch {
                            val link = store.refreshFeishuLinkNow()
                            // 无设备时检测结果与原状态相同，状态行不会变化：用 toast 显式报告结果
                            delay(450)
                            manualChecking = false
                            store.showToast(
                                when {
                                    link == null -> "检测失败：无法运行 adb"
                                    link.connected -> "检测完成：${link.describe()}"
                                    else -> "检测完成：未检测到手机"
                                },
                            )
                        }
                    },
                    enabled = !running && !manualChecking,
                )
                QuietTextAction(if (help) "收起帮助" else "帮助", onClick = { help = !help })
                QuietTextAction(if (advanced) "收起设置" else "设置", onClick = { advanced = !advanced })
            }

            // 高级设置（默认折叠）：连接方式 / 无线地址 / PIN
            if (advanced) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("连接方式", style = ui.typography.secondary, color = Theme.Muted)
                        listOf(
                            FeishuCheckin.MODE_AUTO to "自动（无线优先）",
                            FeishuCheckin.MODE_WIRELESS to "仅无线",
                            FeishuCheckin.MODE_USB to "仅 USB",
                        ).forEach { (value, label) ->
                            // 底色走主题交互面：未选=可见静默底，选中=抬升底+强调字；去默认描边与近透明底。
                            // 切换即永久保存（默认 auto，2026-09-29 用户要求）：不必再点「保存配置」
                            FilterChip(
                                selected = mode == value,
                                onClick = {
                                    mode = value
                                    store.saveFeishuConfig(mode, ip, port.toIntOrNull() ?: 5555)
                                    store.showToast("连接方式已保存：$label")
                                },
                                label = { Text(label) },
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Theme.Hover,
                                    labelColor = Theme.Muted,
                                    selectedContainerColor = Theme.Selected,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                    if (mode != FeishuCheckin.MODE_USB) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(ip, { ip = it }, Modifier.weight(2f), label = { Text("手机 IP") }, singleLine = true)
                            OutlinedTextField(port, { port = it }, Modifier.weight(1f), label = { Text("端口") }, singleLine = true)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PinField(pin) { pin = it }
                        QuietTextAction("保存配置", onClick = {
                            store.saveFeishuConfig(mode, ip, port.toIntOrNull() ?: 5555)
                            store.saveFeishuPin(pin)
                            store.showToast("配置已保存")
                            store.refreshFeishuLink()
                        })
                    }
                }
            }

            // 执行结果状态行
            run?.let { r ->
                if (!r.running && r.exitCode != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.exitCode == 0) Theme.OkGreen else Theme.BadRed))
                        Text(r.summary ?: "", style = ui.typography.secondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 进度行（运行中逐行刷新，结束后保留尾部）
            val lines = run?.lines.orEmpty()
            if (lines.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBlock, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        lines.takeLast(6).forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = if (running || run?.exitCode == 0) Theme.Muted else Theme.BadRed,
                            )
                        }
                    }
                }
            }

            // 打开输出目录（结果截图只落盘不内嵌展示——大图占屏，2026-09-29 用户要求去掉）
            run?.screenshotPath?.let { path ->
                File(path).parent?.let { dir ->
                    QuietTextAction("打开输出目录（含结果截图）", onClick = { openDirectory(store, dir) })
                }
            }

            if (help) HelpSection()
        }
    }
}

/** 黑曜深色下 TextButton 自带状态层在近黑底上几乎不可见；文字操作统一走主题悬停/按压面给显式反馈。 */
@Composable
private fun QuietTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Text(
        text,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    !enabled -> Color.Transparent
                    pressed -> Theme.Pressed
                    hovered -> Theme.Selected
                    else -> Theme.Hover // 静止态也给可见底色：全透明读不出"这是按钮"
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else Theme.Muted,
    )
}

/** PIN 输入（密码变换 + 数字键盘） */
@Composable
private fun PinField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        Modifier.width(180.dp),
        label = { Text("手机锁屏 PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

/** 帮助说明：连接准备与常见问题（launcher_tool/README playbook 的用户向精简版，措辞中性） */
@Composable
private fun HelpSection() {
    val ui = atlasUiTokens()
    val items = listOf(
        "执行前把手机连到电脑：无线（推荐，手机与电脑同一网络）或任意能传数据的线；上方状态行变绿即可执行。",
        "首次使用在「设置」里保存手机锁屏 PIN；执行会自动亮屏、解锁、进入目标页面并截图。",
        "手机重启后无线连不上：无线调试端口会变，到「开发者选项 → 无线调试」查看新的「IP 地址和端口」，在「设置」里更新。",
        "IP 会漂移：连不上时先看手机 WLAN 详情里的当前 IP，更新「设置」里的手机 IP。",
        "线接触不良、设备频繁掉线：重新插稳几秒，等状态行变绿；自动模式优先走无线、USB 兜底。",
        "中断或超时：多为页面加载慢或会话过期，流程会自动重试、必要时重启应用重来；仍失败就再执行一次。",
        "全程由本机 adb 完成，结果截图可在下方预览，或用「打开输出目录」查看历史。",
    )
    Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBlock, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("使用说明", style = ui.typography.secondary, fontWeight = FontWeight.SemiBold)
            items.forEach { item ->
                Text("· $item", style = ui.typography.caption, color = Theme.Muted, lineHeight = 18.sp)
            }
        }
    }
}

/** 执行图标：圆圈 + 播放三角（Canvas 绘制，避免字体缺字） */
@Composable
private fun RunTaskIcon() {
    Canvas(Modifier.size(22.dp)) {
        val c = Theme.Accent
        val stroke = 2.dp.toPx()
        val cap = StrokeCap.Round
        drawCircle(c, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        drawPath(
            Path().apply {
                moveTo(size.width * 0.40f, size.height * 0.30f)
                lineTo(size.width * 0.72f, size.height * 0.50f)
                lineTo(size.width * 0.40f, size.height * 0.70f)
                close()
            },
            c,
        )
    }
}

/** 下箭头进托盘的工具图标（Canvas 绘制） */
@Composable
private fun DropInIcon() {
    Canvas(Modifier.size(22.dp)) {
        val c = Theme.Accent
        val stroke = 2.dp.toPx()
        val cap = StrokeCap.Round
        drawLine(c, Offset(size.width * 0.5f, size.height * 0.10f), Offset(size.width * 0.5f, size.height * 0.50f), stroke, cap)
        drawLine(c, Offset(size.width * 0.32f, size.height * 0.34f), Offset(size.width * 0.5f, size.height * 0.52f), stroke, cap)
        drawLine(c, Offset(size.width * 0.68f, size.height * 0.34f), Offset(size.width * 0.5f, size.height * 0.52f), stroke, cap)
        drawLine(c, Offset(size.width * 0.16f, size.height * 0.70f), Offset(size.width * 0.16f, size.height * 0.82f), stroke, cap)
        drawLine(c, Offset(size.width * 0.84f, size.height * 0.70f), Offset(size.width * 0.84f, size.height * 0.82f), stroke, cap)
        drawLine(c, Offset(size.width * 0.16f, size.height * 0.82f), Offset(size.width * 0.84f, size.height * 0.82f), stroke, cap)
    }
}

private fun chooseLogFile(): File? {
    val chooser = JFileChooser(File(System.getProperty("user.home")))
    chooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
    chooser.dialogTitle = "选择日志压缩包或目录"
    chooser.isAcceptAllFileFilterUsed = false
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun openDirectory(store: AppStore, path: String) {
    runCatching {
        java.awt.Desktop.getDesktop().open(File(path))
        Log.i("工具页：打开输出目录 $path")
    }.onFailure { e ->
        Log.e("工具页：打开输出目录失败 $path", e)
        store.showToast("无法打开目录：${path}")
    }
}

private fun shortenHome(path: String): String =
    path.replaceFirst(Regex("^" + Regex.escape(System.getProperty("user.home"))), "~")

/**
 * 设备工具箱卡片：launcher_tool 一次性工具 + logcat 流式捕获的原生集成。
 * 单并发执行（与原 ScriptRunner 约束一致）；APK 路径/截图目录/AVD 名在设置区配置。
 */
@Composable
private fun DeviceToolboxCard(store: AppStore) {
    val ui = atlasUiTokens()
    val run = store.toolRun.value
    val running = run.running
    val logcatOn = store.logcatRunning.value

    var advanced by remember { mutableStateOf(false) }
    var apkPath by remember { mutableStateOf(store.settings.pushApkPath) }
    var shotDir by remember { mutableStateOf(store.settings.screenshotSaveDir) }
    var avd by remember { mutableStateOf(store.settings.emulatorAvd) }
    var logcatFilter by remember { mutableStateOf("") }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 卡片头
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Theme.Selected) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { ToolboxIcon() }
                }
                Column(Modifier.weight(1f)) {
                    Text("设备工具箱", style = ui.typography.itemTitle)
                    Text("推送 · 重启 · 截屏 · 模拟器 · 日志，全部本地执行", style = ui.typography.secondary, color = Theme.Muted)
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起设置" else "设置") }
            }

            // 工具按钮按组分块
            DeviceTools.Tool.entries.groupBy { it.group }.forEach { (group, tools) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group, style = ui.typography.secondary, color = Theme.Muted, modifier = Modifier.width(44.dp))
                    tools.forEach { tool ->
                        OutlinedButton(onClick = { store.runTool(tool) }, enabled = !running) {
                            Text(tool.label)
                        }
                    }
                }
            }

            // logcat 流式捕获行
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (logcatOn) store.stopLogcat() else store.startLogcat(logcatFilter) },
                    enabled = !running,
                    colors = if (logcatOn) ButtonDefaults.buttonColors(containerColor = Theme.BadRed) else ButtonDefaults.buttonColors(),
                ) { Text(if (logcatOn) "停止日志" else "日志捕获") }
                OutlinedTextField(
                    logcatFilter, { logcatFilter = it },
                    Modifier.weight(1f),
                    label = { Text("日志过滤（可选，子串匹配）") },
                    singleLine = true,
                    enabled = !logcatOn,
                )
            }

            // 高级设置
            if (advanced) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(apkPath, { apkPath = it }, Modifier.weight(1f), label = { Text("Launcher APK 路径") }, singleLine = true)
                        TextButton(onClick = { choosePath(directory = false, "选择 APK 文件")?.let { apkPath = it } }) { Text("浏览") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(shotDir, { shotDir = it }, Modifier.weight(1f), label = { Text("截图保存目录") }, singleLine = true)
                        TextButton(onClick = { choosePath(directory = true, "选择截图目录")?.let { shotDir = it } }) { Text("浏览") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(avd, { avd = it }, Modifier.width(180.dp), label = { Text("AVD 名称") }, singleLine = true)
                        TextButton(onClick = {
                            store.settings = store.settings.copy(
                                pushApkPath = apkPath.trim(),
                                screenshotSaveDir = shotDir.trim(),
                                emulatorAvd = avd.trim().ifBlank { DeviceTools.DEFAULT_AVD },
                            )
                            store.saveSettings()
                            store.showToast("配置已保存")
                        }) { Text("保存配置") }
                    }
                }
            }

            // 运行状态
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Theme.Accent)
                    val label = DeviceTools.Tool.entries.firstOrNull { it.id == run.toolId }?.label ?: run.toolId
                    Text("${label ?: "工具"} 执行中…", style = ui.typography.secondary, color = Theme.Accent)
                }
            }

            // 工具输出尾部
            if (run.lines.isNotEmpty()) {
                MonoOutputBlock(run.lines.takeLast(8), color = if (run.exitCode == null || run.exitCode == 0) Theme.Muted else Theme.BadRed)
            }

            // logcat 输出尾部（保留最近 12 行，全量在内存缓冲 500 行）
            if (store.logcatLines.isNotEmpty()) {
                MonoOutputBlock(store.logcatLines.takeLast(12), color = Theme.Muted)
            }
        }
    }
}

/** 等宽输出块（工具输出 / logcat 共用） */
@Composable
private fun MonoOutputBlock(lines: List<String>, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBlock, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            lines.forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = color, maxLines = 2)
            }
        }
    }
}

/** WMS 查看器卡片：窗口容器树查看与双栏对比（wms_viewer_module 原生移植） */
@Composable
private fun WmsViewerCard(store: AppStore) {
    val ui = atlasUiTokens()
    val loading = store.wmsLoading.value

    var cmdMenu by remember { mutableStateOf(false) }
    var cmd by remember { mutableStateOf(WmsParser.DEFAULT_COMMANDS.first()) }
    var dual by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateSetOf<Int>() }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 卡片头
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Theme.Selected) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { TreeIcon() }
                }
                Column(Modifier.weight(1f)) {
                    Text("WMS 查看器", style = ui.typography.itemTitle)
                    Text("窗口容器树查看与对比，排查窗口层级问题", style = ui.typography.secondary, color = Theme.Muted)
                }
                TextButton(onClick = { dual = !dual }) { Text(if (dual) "单栏" else "双栏") }
            }

            // 工具栏：命令下拉 + 载入/对比 + 展开级别
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { cmdMenu = true }, enabled = !loading) {
                        Text(cmd, maxLines = 1, fontSize = 11.sp, modifier = Modifier.widthIn(max = 300.dp))
                    }
                    DropdownMenu(expanded = cmdMenu, onDismissRequest = { cmdMenu = false }) {
                        WmsParser.DEFAULT_COMMANDS.forEach { c ->
                            DropdownMenuItem(text = { Text(c, fontSize = 12.sp) }, onClick = { cmd = c; cmdMenu = false })
                        }
                    }
                }
                OutlinedButton(onClick = { store.loadWmsDump(cmd, 0); collapsed.clear() }, enabled = !loading) {
                    if (dual) Text("载入左") else Text("载入")
                }
                if (dual) {
                    OutlinedButton(onClick = { store.loadWmsDump(cmd, 1) }, enabled = !loading) { Text("载入右") }
                    OutlinedButton(onClick = {
                        val hasDiff = store.compareWmsTrees()
                        when (hasDiff) {
                            null -> store.showToast("请先载入左右两栏")
                            false -> store.showToast("无差异")
                            true -> store.showToast("对比完成，差异已高亮")
                        }
                    }, enabled = !loading) { Text("对比") }
                }
                Spacer(Modifier.weight(1f))
                listOf("展开" to -1, "收起" to 1, "L2" to 2, "L3" to 3).forEach { (label, level) ->
                    TextButton(onClick = {
                        collapsed.clear()
                        if (level >= 0) {
                            (store.wmsLeftRoot?.let { WmsParser.flatten(it) }.orEmpty() +
                                store.wmsRightRoot?.let { WmsParser.flatten(it) }.orEmpty())
                                .filter { it.depth >= level }
                                .forEach { collapsed.add(System.identityHashCode(it)) }
                        }
                    }) { Text(label) }
                }
            }

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Theme.Accent)
                    Text("正在执行 dumpsys…", style = ui.typography.secondary, color = Theme.Accent)
                }
            }
            store.wmsError.value?.let { err ->
                Text(err, style = ui.typography.secondary, color = Theme.BadRed)
            }

            // 树区
            val left = store.wmsLeftRoot
            val right = store.wmsRightRoot
            if (left != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WmsTreePane(
                        title = "窗口树",
                        root = left,
                        diffByNode = store.wmsLeftDiff,
                        collapsed = collapsed,
                        onRowClick = { store.wmsDetail = it.rawText },
                        modifier = Modifier.weight(1f).heightIn(max = 420.dp),
                    )
                    if (dual && right != null) {
                        WmsTreePane(
                            title = "对比（右）",
                            root = right,
                            diffByNode = store.wmsRightDiff,
                            collapsed = collapsed,
                            onRowClick = { store.wmsDetail = it.rawText },
                            modifier = Modifier.weight(1f).heightIn(max = 420.dp),
                        )
                    }
                }
            } else if (!loading) {
                Text("点「载入」从当前设备拉取窗口树；载入过的输出也可对比。", style = ui.typography.secondary, color = Theme.Muted)
            }

            store.wmsDetail?.let { d ->
                Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBlock, tonalElevation = 1.dp) {
                    Text(
                        d, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                        color = Theme.Muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 单栏树：展平渲染 + 折叠集 + diff 底色（节点身份用 identityHashCode） */
@Composable
private fun WmsTreePane(
    title: String,
    root: WmsParser.Node,
    diffByNode: Map<Int, WmsParser.Diff>,
    collapsed: androidx.compose.runtime.snapshots.SnapshotStateSet<Int>,
    onRowClick: (WmsParser.Node) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = atlasUiTokens()
    // 可见性随 collapsed 变化即时重算（节点量百级，无需 remember）
    val rows = buildList {
        fun walk(parent: WmsParser.Node, parentVisible: Boolean) {
            for (c in parent.children) {
                val key = System.identityHashCode(c)
                val visible = parentVisible && key !in collapsed
                if (visible) add(c)
                walk(c, visible)
            }
        }
        walk(root, true)
    }
    Column(modifier) {
        Text(title, style = ui.typography.secondary, color = Theme.Muted)
        Surface(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            shape = RoundedCornerShape(8.dp), color = Theme.CodeBlock, tonalElevation = 1.dp,
        ) {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(rows, key = { _, n -> System.identityHashCode(n) }) { _, node ->
                    val key = System.identityHashCode(node)
                    val diff = diffByNode[key] ?: WmsParser.Diff.NONE
                    val bg = when (diff) {
                        WmsParser.Diff.ADD -> Theme.OkGreen.copy(alpha = 0.26f)
                        WmsParser.Diff.REMOVE -> Theme.BadRed.copy(alpha = 0.26f)
                        WmsParser.Diff.MODIFY -> Theme.WarnOrange.copy(alpha = 0.26f)
                        WmsParser.Diff.NONE -> Color.Transparent
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .clickable { onRowClick(node) }
                            .padding(start = (8 + node.depth * 14).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (node.children.isNotEmpty()) {
                            Text(
                                if (key in collapsed) "▸" else "▾",
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Theme.Muted,
                                modifier = Modifier.clickable {
                                    if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
                                },
                            )
                        }
                        Text(
                            node.shortName,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 1,
                            color = when (node.category) {
                                WmsParser.Category.WINDOW -> Theme.Accent
                                WmsParser.Category.ACTIVITY -> Theme.OkGreen
                                WmsParser.Category.TASK -> Theme.WarnOrange
                                WmsParser.Category.DISPLAY -> Theme.Focus
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 提示词库：双栏直编格局——左列标题列表、右列内容编辑区，顶部 Name 输入 + Add/Save/Delete/Copy。modifier 传入 fillMaxSize 体系使其占满工具页。 */
@Composable
private fun PromptsCard(store: AppStore, modifier: Modifier = Modifier) {
    val ui = atlasUiTokens()
    LaunchedEffect(Unit) { store.loadPrompts() }

    var selected by remember { mutableStateOf(-1) } // -1 = 新建草稿/未选中
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    fun loadEntry(index: Int) {
        selected = index
        store.prompts.getOrNull(index)?.let { name = it.title; content = it.body }
    }

    fun clearEditor() {
        selected = -1
        name = ""
        content = ""
    }

    fun saveCurrent() {
        if (name.isBlank()) {
            store.showToast("名称不能为空")
            return
        }
        if (selected in store.prompts.indices) {
            store.updatePrompt(selected, name, content)
            store.showToast("已保存「${name.trim()}」")
        } else {
            store.addPrompt(name, content)
            selected = store.prompts.lastIndex
            store.showToast("已添加「${name.trim()}」")
        }
    }

    fun deleteCurrent() {
        if (selected !in store.prompts.indices) {
            store.showToast("先在左侧选择一条提示词")
            return
        }
        val title = store.prompts[selected].title
        store.deletePrompt(selected)
        clearEditor()
        store.showToast("已删除「$title」")
    }

    // 所见即所复制：编辑缓冲直接进剪贴板，未保存的修改也会带上
    fun copyCurrent() {
        if (name.isBlank() && content.isBlank()) {
            store.showToast("没有可复制的内容")
            return
        }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(name.trim() + "\n\n" + content.trim()), null)
        store.showToast("已复制「${name.trim().ifBlank { "未命名提示词" }}」")
    }

    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 名称可折行到 3 行：按钮组贴顶对齐，避免多行时按钮被垂直居中甩到中间
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 下压 18dp 与输入框首行文字对齐（56dp 高的 OutlinedTextField 内文字垂直居中）；
                // 贴行顶会压在输入框上边框上（用户截图反馈）
                Text("Name:", style = ui.typography.secondary, color = Theme.Muted, modifier = Modifier.padding(top = 18.dp))
                OutlinedTextField(
                    name, { name = it },
                    // 40dp：OutlinedTextField 默认最小高 56dp，单行无 label 场景压到与操作按钮同高（40dp）
                    Modifier.weight(1f),
                    // 不限单行：长标题（如 handoff 文档名）折行完整显示；固定矮高度会把文字垂直裁掉
                    maxLines = 3,
                    textStyle = ui.typography.secondary,
                    placeholder = { Text("提示词名称", style = ui.typography.secondary) },
                )
                CompactActionButton("Add") { clearEditor() }
                CompactActionButton("Save") { saveCurrent() }
                CompactActionButton(
                    "Delete",
                    contentColor = Theme.BadRed,
                    enabled = selected in store.prompts.indices,
                ) { deleteCurrent() }
                CompactActionButton("Copy", contentColor = Theme.OkGreen) { copyCurrent() }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(240.dp).fillMaxHeight()) {
                    Text("Prompts", style = ui.typography.secondary, fontWeight = FontWeight.SemiBold, color = Theme.Muted)
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        store.prompts.forEachIndexed { index, entry ->
                            Text(
                                entry.title,
                                style = ui.typography.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (index == selected) Theme.Accent else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { loadEntry(index) }
                                    .background(
                                        if (index == selected) Theme.Selected else Color.Transparent,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                        }
                        if (store.prompts.isEmpty()) {
                            Text("还没有提示词，点 Add 新建。", style = ui.typography.caption, color = Theme.Muted)
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text("Content", style = ui.typography.secondary, fontWeight = FontWeight.SemiBold, color = Theme.Muted)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        content, { content = it },
                        Modifier.fillMaxWidth().weight(1f),
                        // 内容通常是大段提示词，用小一号正文样式提升一屏可见行数
                        textStyle = ui.typography.secondary,
                        placeholder = { Text("提示词内容（Markdown）", style = ui.typography.secondary) },
                    )
                }
            }
        }
    }
}

/** 提示词库顶部的紧凑操作块（Add/Save/Delete/Copy）：Delete 红字、Copy 绿字、失效置灰。 */
@Composable
private fun CompactActionButton(
    label: String,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = Theme.Muted,
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** 卡片头小图标（Canvas 绘制，避免字体缺字） */
@Composable
private fun ToolboxIcon() {
    Canvas(Modifier.size(22.dp)) {
        val c = Theme.Accent
        val stroke = 2.dp.toPx()
        val cap = StrokeCap.Round
        // 扳手轮廓：C 形 + 柄
        drawArc(c, startAngle = -60f, sweepAngle = 270f, useCenter = false, style = Stroke(stroke, cap = cap))
        drawLine(c, Offset(size.width * 0.72f, size.height * 0.72f), Offset(size.width * 0.18f, size.height * 0.18f), stroke, cap)
    }
}

@Composable
private fun TreeIcon() {
    Canvas(Modifier.size(22.dp)) {
        val c = Theme.Accent
        val stroke = 2.dp.toPx()
        val cap = StrokeCap.Round
        drawLine(c, Offset(size.width * 0.2f, size.height * 0.15f), Offset(size.width * 0.2f, size.height * 0.85f), stroke, cap)
        drawLine(c, Offset(size.width * 0.2f, size.height * 0.3f), Offset(size.width * 0.6f, size.height * 0.3f), stroke, cap)
        drawLine(c, Offset(size.width * 0.2f, size.height * 0.7f), Offset(size.width * 0.6f, size.height * 0.7f), stroke, cap)
        drawRect(c, topLeft = Offset(size.width * 0.6f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.24f), style = Stroke(stroke))
        drawRect(c, topLeft = Offset(size.width * 0.6f, size.height * 0.58f), size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.24f), style = Stroke(stroke))
    }
}

/** 文件/目录选择：文件走 AWT FileDialog（GTK 原生观感），目录走 JFileChooser */
private fun choosePath(directory: Boolean, title: String): String? {
    if (!directory) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file ?: return null
        val dir = dialog.directory ?: return file
        return dir + file
    }
    val chooser = JFileChooser(File(System.getProperty("user.home")))
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = title
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
