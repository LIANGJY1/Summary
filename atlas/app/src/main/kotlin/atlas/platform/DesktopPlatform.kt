package atlas.platform

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import atlas.core.DEFAULT_LIBRARY_PATH
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 桌面端平台实现。共享源码里的平台差异点（剪贴板、外部打开、悬停光标、Tooltip、默认库路径）
 * 统一收敛到本对象；Android 构建整体排除 `atlas/platform/` 目录，改用 app-android 里的同名实现。
 * 两侧签名必须保持一致（同名同名参），否则共享调用点在某一端编译失败。
 */
object Platform {

    /** 首次启动兜底库路径：桌面沿用用户 home 约定（Settings.DEFAULT_LIBRARY_PATH） */
    val defaultLibraryPath: String = DEFAULT_LIBRARY_PATH
    val supportsQuestionImageActions: Boolean = true

    fun setClipboardText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    /** 将图片像素写入系统剪贴板，支持粘贴到文档或图片编辑器。 */
    fun setClipboardImage(file: File): Boolean {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return false
        return runCatching {
            val transferable = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
                override fun getTransferData(flavor: DataFlavor): Any {
                    if (!isDataFlavorSupported(flavor)) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                    return image
                }
            }
            Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
            true
        }.getOrDefault(false)
    }

    fun getClipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    /** 读取系统剪贴板中的截图并规范编码为 PNG；非图片剪贴板返回 null。 */
    fun getClipboardImagePng(): ByteArray? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) return null
        val source = clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val width = source.getWidth(null)
        val height = source.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { graphics.drawImage(source, 0, 0, null) } finally { graphics.dispose() }
        ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) return null
            output.toByteArray()
        }
    }.getOrNull()

    fun loadImage(file: File): ImageBitmap? = runCatching {
        ImageIO.read(file)?.toComposeImageBitmap()
    }.getOrNull()

    fun normalizeImagePng(bytes: ByteArray): ByteArray? = runCatching {
        val decoded = ImageIO.read(bytes.inputStream()) ?: return null
        ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(decoded, "png", output)) return null
            output.toByteArray()
        }
    }.getOrNull()

    @Composable
    fun windowWidthDp(): Dp = LocalWindowInfo.current.containerDpSize.width

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
