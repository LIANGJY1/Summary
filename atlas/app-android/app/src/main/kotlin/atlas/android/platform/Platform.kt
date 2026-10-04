package atlas.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.DpOffset
import java.io.File

/**
 * Android 端平台实现，与桌面端 app/src/main/kotlin/atlas/platform/DesktopPlatform.kt 是
 * 同一 FQCN 的两份实现：Android 构建排除桌面文件、桌面构建排除本目录（目录名故意不同于
 * 包名，避免 exclude 模式误伤共享调用方）。MainActivity 必须最先调 init()。
 */
object Platform {
    @Volatile private var appContext: Context? = null

    /** 默认库路径：约定手机端知识库放在 /sdcard/Atlas/knowledge-base，可在设置页改 */
    val defaultLibraryPath: String = "/sdcard/Atlas/knowledge-base"

    fun init(context: Context) {
        appContext = context.applicationContext
        // 共享 Indexer/AppStore 走 java.sql；Android 无内置 SQLite JDBC 驱动，
        // 显式注册 SQLDroid（DriverManager 的 ServiceLoader 发现机制在部分运行时不可靠）
        runCatching { Class.forName("org.sqldroid.SQLDroidDriver") }
            .onFailure { Log.w("atlas-platform", "SQLDroid 注册失败", it) }
    }

    fun setClipboardText(text: String) {
        val cm = appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("atlas", text))
    }

    fun getClipboardText(): String? {
        val cm = appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    /** 手机端无「系统默认程序打开」语义；抛出让调用方既有 catch 降级为 toast */
    fun openFile(file: File) {
        throw UnsupportedOperationException("手机端暂不支持外部编辑器打开文件")
    }

    /** 触屏无悬停光标语义 */
    fun resizePointerIcon(): PointerIcon = PointerIcon.Default

    /** 子进程输出丢弃：android.jar 无 Redirect.DISCARD，重定向 /dev/null 语义等价 */
    fun discardRedirect(): ProcessBuilder.Redirect = ProcessBuilder.Redirect.to(java.io.File("/dev/null"))

    /** Android 无 ProcessHandle；adb 子进程树等 PC 工具场景手机端不出现，尽力杀根进程 */
    fun killProcessTree(process: Process) {
        runCatching { process.destroy() }
    }

    /** 动作投递到主线程（Log 的 UI 卡顿看门狗用） */
    fun runOnUi(block: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(block)
    }
}

/** 触屏没有悬停提示：直接渲染内容本体（桌面端为 TooltipArea） */
@Composable
fun PlatformTooltip(
    tooltip: @Composable () -> Unit,
    delayMillis: Int = 400,
    cursorOffset: DpOffset? = null,
    content: @Composable () -> Unit,
) {
    Box { content() }
}
