package atlas.platform

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.DpOffset
import atlas.core.DEFAULT_LIBRARY_PATH
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * 桌面端平台实现。共享源码里的平台差异点（剪贴板、外部打开、悬停光标、Tooltip、默认库路径）
 * 统一收敛到本对象；Android 构建整体排除 `atlas/platform/` 目录，改用 app-android 里的同名实现。
 * 两侧签名必须保持一致（同名同名参），否则共享调用点在某一端编译失败。
 */
object Platform {

    /** 首次启动兜底库路径：桌面沿用用户 home 约定（Settings.DEFAULT_LIBRARY_PATH） */
    val defaultLibraryPath: String = DEFAULT_LIBRARY_PATH

    fun setClipboardText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    fun getClipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    /** 用系统默认程序打开文件；不支持/失败时抛异常，由调用方既有 catch 降级为 toast */
    fun openFile(file: File) {
        Desktop.getDesktop().open(file)
    }

    /** 拖拽分隔条等场景的横向调整光标 */
    fun resizePointerIcon(): PointerIcon =
        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR))

    /** 子进程输出丢弃（模拟器启动、git 探测等） */
    fun discardRedirect(): ProcessBuilder.Redirect = ProcessBuilder.Redirect.DISCARD

    /** 终止整个进程树（AAOS 命令停止）：桌面用 ProcessHandle 按 descendants 逆序杀 */
    fun killProcessTree(process: Process) {
        process.toHandle().descendants().toList().asReversed().forEach { runCatching { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }

    /** 动作投递到 UI 线程（EDT 卡顿看门狗用） */
    fun runOnUi(block: () -> Unit) {
        javax.swing.SwingUtilities.invokeLater(block)
    }
}

/**
 * 悬停提示：桌面原样走 TooltipArea。cursorOffset 为空时保持 TooltipArea 默认对齐，
 * 非空时等价旧的 TooltipPlacement.CursorPoint(offset)。Android 端同名实现直接渲染内容本体。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlatformTooltip(
    tooltip: @Composable () -> Unit,
    delayMillis: Int = 400,
    cursorOffset: DpOffset? = null,
    content: @Composable () -> Unit,
) {
    if (cursorOffset == null) {
        TooltipArea(tooltip = tooltip, delayMillis = delayMillis) { content() }
    } else {
        TooltipArea(
            tooltip = tooltip,
            delayMillis = delayMillis,
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = cursorOffset),
        ) { content() }
    }
}
