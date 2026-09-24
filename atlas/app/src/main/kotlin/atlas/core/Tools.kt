package atlas.core

import java.io.File

/**
 * 工具页的纯逻辑（UI 无关，可单测）。
 *
 * 首个集成工具：27HM 车机日志解密（Summary 仓 tools/hc/27M/hc_log_auto.py）。
 * 脚本随 Summary 仓库分发，与设置页录屏工具同模式。
 */
object Tools {

    /** 解密脚本相对 user.home 的位置 */
    const val HC_LOG_SCRIPT = "Project/MyProject/Summary/tools/hc/27M/hc_log_auto.py"

    /** 拖拽/选择输入的预校验：目录直收；文件按扩展名放行（脚本自身支持这些格式） */
    private val ACCEPTED_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

    fun hcLogScriptFile(home: String = System.getProperty("user.home")): File =
        File(home, HC_LOG_SCRIPT)

    fun hcLogCommand(scriptPath: String, inputPath: String): List<String> =
        listOf("python3", scriptPath, inputPath)

    fun isAcceptableInput(file: File): Boolean =
        file.isDirectory || file.extension.lowercase() in ACCEPTED_EXTENSIONS

    /**
     * 规范化拖入的文件路径：XDND（text/uri-list）通道送来的是 file: URI 字符串
     * （`file:/home/x.zip`、`file:///home/a%20b.zip`），直接当路径用会以工作目录
     * 解析成不存在的输入；本地拖放则已是纯路径。统一转成绝对路径字符串。
     */
    fun normalizeDroppedPath(raw: String): String {
        val trimmed = raw.trim()
        return runCatching {
            val uri = java.net.URI(trimmed)
            if (uri.scheme?.lowercase() == "file") File(uri).absolutePath else null
        }.getOrNull() ?: trimmed
    }

    /** 一次工具运行在 UI 上的完整状态（跨页签保留，进程由 AppStore.scope 托管） */
    data class ToolRun(
        val running: Boolean,
        val inputPath: String,
        val exitCode: Int? = null,
        val outputDir: String? = null,
        val summary: String? = null,
        val tail: List<String> = emptyList(),
    )

    /**
     * 解析 hc_log_auto.py 的 stdout（stderr 已合流）：
     * 摘要行 `完成: 解密 N 个, 解压 M 个 .lz4, 失败 K 个`，输出目录行 `输出目录: <path>`。
     */
    fun parseToolOutput(text: String, exitCode: Int): ToolRun {
        val lines = text.lines().filter { it.isNotBlank() }
        val summary = lines.lastOrNull { it.startsWith("完成:") }?.trim()
        val outputDir = lines.lastOrNull { it.startsWith("输出目录:") }
            ?.removePrefix("输出目录:")?.trim()
        return ToolRun(
            running = false,
            inputPath = "",
            exitCode = exitCode,
            outputDir = outputDir,
            summary = summary,
            tail = lines.takeLast(6),
        )
    }
}
