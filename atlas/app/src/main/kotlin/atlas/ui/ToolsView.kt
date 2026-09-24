@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Log
import atlas.core.Tools
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import java.io.File
import javax.swing.JFileChooser

/**
 * 工具页：把日常本地小工具图形化集成，拖入文件即可运行（全部本地执行，零网络）。
 * 首个工具：27HM 车机日志解密（脚本随 Summary 仓分发）。
 */
@Composable
fun ToolsView(store: AppStore) {
    val ui = atlasUiTokens()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ui.spacing.page),
        verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(ui.spacing.section)) {
            Text("工具", style = ui.typography.pageTitle)
            Text("把日常小工具收进 Atlas：拖入文件即可运行，全部本地执行。", style = ui.typography.secondary, color = Theme.Muted)
            HcLogDecryptCard(store)
        }
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 卡片头：绘制图标（避免字体缺字）+ 标题 + 一句话说明
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Theme.Accent.copy(alpha = 0.14f)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { DropInIcon() }
                }
                Column {
                    Text("车机日志解密", style = ui.typography.itemTitle)
                    Text("27HM 日志压缩包 / 目录 → 解压·解密·解压，一键出可读日志", style = ui.typography.secondary, color = Theme.Muted)
                }
            }

            // 拖入区：运行中显示进度，否则显示拖放提示 + 文件选择
            val zoneColor = if (dragHover.value) Theme.Accent.copy(alpha = 0.07f) else Color.Transparent
            val borderColor = if (dragHover.value) Theme.Accent else MaterialTheme.colorScheme.outlineVariant
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
                            TextButton(onClick = { openDirectory(store, dir) }) { Text("打开输出目录") }
                        }
                    }
                    Text(shortenHome(r.inputPath), style = ui.typography.caption, color = Theme.Muted)
                    if (r.tail.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBg, tonalElevation = 1.dp) {
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
