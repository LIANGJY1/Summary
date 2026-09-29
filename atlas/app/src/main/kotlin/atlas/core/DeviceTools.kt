package atlas.core

import java.io.File

/**
 * 设备工具箱的纯逻辑（UI 无关，可单测）：launcher_tool 一次性命令工具的原生移植。
 * adb 路径复用 FeishuCheckin.findAdb；多命令序列（推送 APK）按顺序执行，其余即发即忘。
 * logcat 流式输出在 AppStore 层单独托管（无限流，需 stop）。
 */
object DeviceTools {

    const val LAUNCHER_PACKAGE = "com.yadea.launcher"
    const val DEFAULT_PUSH_DEST = "/system_ext/priv-app/NsrLauncher"
    const val DEFAULT_AVD = "3DAA"

    /** 一次性工具清单（顺序即 UI 展示顺序；logcat 流式工具不在此列） */
    enum class Tool(val id: String, val label: String, val group: String, val description: String) {
        PUSH_LAUNCHER("push_launcher", "推送 Launcher APK", "设备", "root + remount + push 到系统分区后重启设备"),
        REBOOT_LAUNCHER("reboot_launcher", "重启 Launcher 进程", "设备", "kill 设备上的 Launcher 进程让其自拉起"),
        SCREENSHOT("ui_screenshot", "截取设备屏幕", "调试", "当前设备截图，按序号存入截图目录"),
        CLEAR_LOGCAT("adb_logcat_clear", "清空日志缓冲", "调试", "adb logcat -c"),
        START_EMULATOR("start_3daa", "启动模拟器", "模拟器", "启动配置的 AVD（带快照）"),
        COLD_BOOT_EMULATOR("cold_boot_3daa", "冷启动模拟器", "模拟器", "不加载快照冷启动 AVD"),
        STRIP_SLASHES("remove_comment_slashes", "清理剪贴板行首斜杠", "实用", "读剪贴板去掉行首 // 并写回，可直接粘贴"),
    }

    /** 一次工具运行在 UI 上的状态（单并发，跨页签保留） */
    data class ToolRun(
        val toolId: String? = null,
        val running: Boolean = false,
        val lines: List<String> = emptyList(),
        val exitCode: Int? = null,
        val summary: String? = null,
    )

    fun findEmulator(home: String = System.getProperty("user.home")): String? {
        val f = File(home, "Android/Sdk/emulator/emulator")
        return if (f.isFile && f.canExecute()) f.absolutePath else null
    }

    // ---------- 命令构造 ----------

    /** 推送 APK 序列（push_launcher.sh 原生移植：root → wait-for-device → remount → push → reboot） */
    fun pushLauncherCommands(adbPath: String, apkPath: String, destDir: String = DEFAULT_PUSH_DEST): List<List<String>> =
        listOf(
            listOf(adbPath, "root"),
            listOf(adbPath, "wait-for-device"),
            listOf(adbPath, "remount"),
            listOf(adbPath, "push", apkPath, destDir),
            listOf(adbPath, "reboot"),
        )

    fun listProcessesArgs(adbPath: String): List<String> = listOf(adbPath, "shell", "ps", "-A")

    fun killPidArgs(adbPath: String, pid: String): List<String> = listOf(adbPath, "shell", "kill", pid)

    fun screenshotArgs(adbPath: String): List<String> = listOf(adbPath, "exec-out", "screencap", "-p")

    fun clearLogcatArgs(adbPath: String): List<String> = listOf(adbPath, "logcat", "-c")

    fun logcatArgs(adbPath: String): List<String> = listOf(adbPath, "logcat")

    fun emulatorArgs(emulatorPath: String, avd: String, coldBoot: Boolean): List<String> =
        buildList {
            add(emulatorPath)
            add("-avd"); add(avd)
            add("-writable-system")
            if (coldBoot) add("-no-snapshot")
        }

    // ---------- 输出解析 / 纯函数 ----------

    /** `ps -A` 输出 → 目标包名所在行的第二列（PID，python 版 awk '{print $2}' 同语义）；找不到返回 null */
    fun parsePid(psOutput: String, packageName: String): String? =
        psOutput.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { cols -> cols.size >= 2 && cols.any { it.contains(packageName) } }
            ?.get(1)

    /** 截图目录下一个可用文件名 screenshot_001.png（自增不覆盖） */
    fun nextScreenshotFile(dir: File): File {
        dir.mkdirs()
        var n = 1
        while (true) {
            val f = File(dir, "screenshot_%03d.png".format(n))
            if (!f.exists()) return f
            n++
        }
    }

    /** 去掉每行行首 //（保留缩进，// 后单个空格一并去掉）——remove_comment_slashes.py 移植 */
    fun stripCommentSlashes(text: String): String =
        text.lines().joinToString("\n") { line ->
            val stripped = line.trimStart()
            val indent = line.substring(0, line.length - stripped.length)
            if (stripped.startsWith("//")) {
                var rest = stripped.substring(2)
                if (rest.startsWith(" ")) rest = rest.substring(1)
                indent + rest
            } else {
                line
            }
        }
}
