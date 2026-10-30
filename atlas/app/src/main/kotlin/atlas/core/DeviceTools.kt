package atlas.core

import java.io.File

/**
 * 设备工具箱的纯逻辑（UI 无关，可单测）：launcher_tool 一次性命令工具的原生移植。
 * adb 路径复用 FeishuCheckin.findAdb；多命令序列（推送 APK）按顺序执行，其余即发即忘。
 * logcat 流式输出在 AppStore 层单独托管（无限流，需 stop）。
 */
object DeviceTools {

    const val LAUNCHER_PACKAGE = "com.yadea.launcher"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val DEFAULT_PUSH_DEST = "/system_ext/priv-app/NsrLauncher"
    /** 车机实测（pm path）：SystemUI apk 在 system_ext 同分区，remount 覆盖写后 kill 自启即加载 */
    const val SYSTEMUI_APK_DEST = "/system_ext/priv-app/NsrSystemUI/NsrSystemUI.apk"
    const val SETTING_APK_DEST = "/system_ext/priv-app/NsrSetting/NsrSetting.apk"
    const val DEFAULT_AVD = "3DAA"
    private val PCI_ADDRESS = Regex("[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-7]")

    /** 一次性工具清单（顺序即 UI 展示顺序；logcat 流式工具不在此列） */
    enum class Tool(val id: String, val label: String, val group: String, val description: String) {
        PUSH_LAUNCHER("push_launcher", "推送 Launcher APK", "设备", "root + remount + push 到系统分区后重启设备"),
        PUSH_SYSTEMUI("push_systemui", "推送 SystemUI APK", "设备", "remount 覆盖写 NsrSystemUI.apk 后 sync 刷盘并整机重启"),
        PUSH_SETTING("push_setting", "推送 Setting APK", "设备", "remount 覆盖写 NsrSetting.apk 后 sync 刷盘并整机重启"),
        REBOOT_LAUNCHER("reboot_launcher", "重启 Launcher 进程", "设备", "kill 设备上的 Launcher 进程让其自拉起"),
        REBOOT_SYSTEMUI("reboot_systemui", "重启 SystemUI", "设备", "kill SystemUI（persistent）秒级自启，加载新 push 的 apk/资源"),
        SCREENSHOT("ui_screenshot", "截取设备屏幕", "调试", "当前设备截图，按序号存入截图目录"),
        CLEAR_LOGCAT("adb_logcat_clear", "清空日志缓冲", "调试", "adb logcat -c"),
        DARK_MODE("ui_mode_dark", "深色模式", "深浅色", "adb shell cmd uimode night yes，立即生效"),
        LIGHT_MODE("ui_mode_light", "浅色模式", "深浅色", "adb shell cmd uimode night no，立即生效"),
        START_EMULATOR("start_3daa", "启动模拟器", "模拟器", "启动配置的 AVD（带快照）"),
        COLD_BOOT_EMULATOR("cold_boot_3daa", "冷启动模拟器", "模拟器", "不加载快照冷启动 AVD"),
        STRIP_SLASHES("remove_comment_slashes", "清理剪贴板行首斜杠", "实用", "读剪贴板去掉行首 // 并写回，可直接粘贴"),
        REPAIR_LOCAL_ADB_USB("repair_local_adb_usb", "修复本机 ADB/USB", "本机", "重启 ADB 并重绑定 xHCI 控制器，随后验证设备"),
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

    fun isLinuxHost(): Boolean = System.getProperty("os.name").contains("linux", ignoreCase = true)

    // ---------- 命令构造 ----------

    /** 目标设备前缀：serial 非空插 -s（多设备在线——如任务自动化的无线手机——没有它 adb 全部报错）；null 不插 */
    private fun withSerial(adbPath: String, serial: String?, vararg args: String): List<String> =
        if (serial == null) listOf(adbPath) + args
        else listOf(adbPath, "-s", serial) + args

    /** 推送 APK 序列（push_launcher.sh 原生移植：root → wait-for-device → remount → push → reboot） */
    fun pushLauncherCommands(
        adbPath: String,
        apkPath: String,
        serial: String? = null,
        destDir: String = DEFAULT_PUSH_DEST,
    ): List<List<String>> =
        listOf(
            withSerial(adbPath, serial, "root"),
            withSerial(adbPath, serial, "wait-for-device"),
            withSerial(adbPath, serial, "remount"),
            withSerial(adbPath, serial, "push", apkPath, destDir),
            withSerial(adbPath, serial, "reboot"),
        )

    /** 推送 SystemUI 序列：同 Launcher 前四步 + sync 刷盘；sync 确认 overlay 落盘后再 reboot 整机加载新 apk */
    fun pushSystemuiCommands(
        adbPath: String,
        apkPath: String,
        serial: String? = null,
        destApk: String = SYSTEMUI_APK_DEST,
    ): List<List<String>> =
        listOf(
            withSerial(adbPath, serial, "root"),
            withSerial(adbPath, serial, "wait-for-device"),
            withSerial(adbPath, serial, "remount"),
            withSerial(adbPath, serial, "push", apkPath, destApk),
            withSerial(adbPath, serial, "shell", "sync"),
            withSerial(adbPath, serial, "reboot"),
        )

    fun pushSettingCommands(adbPath: String, apkPath: String, serial: String? = null): List<List<String>> =
        pushSystemuiCommands(adbPath, apkPath, serial, destApk = SETTING_APK_DEST)

    fun listProcessesArgs(adbPath: String, serial: String? = null): List<String> =
        withSerial(adbPath, serial, "shell", "ps", "-A")

    fun killPidArgs(adbPath: String, pid: String, serial: String? = null): List<String> =
        withSerial(adbPath, serial, "shell", "kill", pid)

    fun screenshotArgs(adbPath: String, serial: String? = null): List<String> =
        withSerial(adbPath, serial, "exec-out", "screencap", "-p")

    fun clearLogcatArgs(adbPath: String, serial: String? = null): List<String> =
        withSerial(adbPath, serial, "logcat", "-c")

    /** 深浅色切换（Android 10+，立即生效）；命令本身回显 "Night mode: yes/no" 可直接进进度行 */
    fun uiModeArgs(adbPath: String, dark: Boolean, serial: String? = null): List<String> =
        withSerial(adbPath, serial, "shell", "cmd", "uimode", "night", if (dark) "yes" else "no")

    fun logcatArgs(adbPath: String, serial: String? = null): List<String> =
        withSerial(adbPath, serial, "logcat")

    fun devicesArgs(adbPath: String): List<String> = listOf(adbPath, "devices")

    /** Linux 上由内核 xhci_hcd 驱动绑定的 PCI 控制器；返回动态发现的 PCI BDF。 */
    fun xhciControllerAddresses(
        driverPath: File = File("/sys/bus/pci/drivers/xhci_hcd"),
    ): List<String> = driverPath.listFiles().orEmpty()
        .filter { it.name.matches(PCI_ADDRESS) && it.isDirectory }
        .map { it.name }
        .sorted()

    /** 以 positional arg 传 PCI 地址，避免将选择值拼入 shell 脚本。 */
    fun xhciResetArgs(controllerAddress: String): List<String> {
        require(controllerAddress.matches(PCI_ADDRESS)) { "无效的 PCI 控制器地址" }
        return listOf("pkexec", "/bin/bash", "-c", XHCI_RESET_SCRIPT, "atlas-usb-repair", controllerAddress)
    }

    /** 多设备默认选择：优先 USB/模拟器条目（serial 无冒号，语义同 FeishuCheckin.usbSerial），无线 ip:port 靠后；无在线设备返回 null */
    fun pickDefaultSerial(onlineSerials: List<String>): String? =
        onlineSerials.firstOrNull { ':' !in it } ?: onlineSerials.firstOrNull()

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

    private val XHCI_RESET_SCRIPT = """
        set -euo pipefail
        address="${'$'}1"
        driver="/sys/bus/pci/drivers/xhci_hcd"
        device="/sys/bus/pci/devices/${'$'}address"
        if [[ ! -L "${'$'}device/driver" || "${'$'}(readlink -f "${'$'}device/driver")" != "${'$'}(readlink -f "${'$'}driver")" ]]; then
          echo "控制器已不存在或不再由 xhci_hcd 管理：${'$'}address"
          exit 1
        fi
        rebind() {
          if [[ -e "${'$'}device" && ! -L "${'$'}device/driver" ]]; then
            printf '%s\n' "${'$'}address" > "${'$'}driver/bind"
          fi
        }
        trap rebind EXIT
        echo "重置 USB 控制器 ${'$'}address；其 USB 设备会暂时断开。"
        printf '%s\n' "${'$'}address" > "${'$'}driver/unbind"
        sleep 2
        rebind
        trap - EXIT
        if command -v udevadm >/dev/null 2>&1; then udevadm settle --timeout=10 || true; fi
        echo "USB 控制器已重新绑定。"
    """.trimIndent()
}
