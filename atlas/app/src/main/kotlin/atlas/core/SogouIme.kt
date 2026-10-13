package atlas.core

import java.io.File

/**
 * 搜狗输入法（fcitx 架构）修复的纯逻辑（UI 无关，可单测）。
 *
 * 背景：搜狗 Linux 版 = fcitx 框架 + sogoupinyin-service（D-Bus 引擎）+ sogoupinyin-watchdog（看门狗）。
 * 引擎崩溃且看门狗不在时不会自愈，表现为"切不出中文、候选浮窗调不起来"而 fcitx 本身正常（2026-10-13 实修案例）。
 */
object SogouIme {

    const val BIN_DIR = "/opt/sogoupinyin/files/bin"
    const val SERVICE_PATH = "$BIN_DIR/sogoupinyin-service"
    const val WATCHDOG_PATH = "$BIN_DIR/sogoupinyin-watchdog"

    /** 一次状态检测的结论（installed=false 时其余字段无意义） */
    data class Status(
        val installed: Boolean,
        val serviceRunning: Boolean,
        val watchdogRunning: Boolean,
        val fcitxRunning: Boolean,
    ) {
        val healthy: Boolean
            get() = serviceRunning && watchdogRunning && fcitxRunning

        fun advice(): String = when {
            !installed -> "未检测到搜狗输入法（$SERVICE_PATH 不存在），本工具不适用"
            healthy -> "引擎、看门狗与 fcitx 框架均在运行，无需修复"
            else -> "诊断：" + buildList {
                if (!serviceRunning) add("引擎未运行（切不出中文的直接原因）")
                if (!watchdogRunning) add("看门狗未运行（引擎再崩将无法自愈）")
                if (!fcitxRunning) add("fcitx 框架未运行（输入法整体失效）")
            }.joinToString("；") + "。点「一键修复」按顺序拉起。"
        }
    }

    /** 一次命令序列在 UI 上的状态（单并发，跨页签保留） */
    data class ImeRun(
        val running: Boolean = false,
        val lines: List<String> = emptyList(),
        val exitCode: Int? = null,
    )

    fun isInstalled(binDir: String = BIN_DIR): Boolean = File(binDir, "sogoupinyin-service").isFile

    // pgrep/pkill -f 会按完整命令行匹配，把包裹 bash 自己的 -c 参数文本也匹配进去，
    // 造成"永远在运行 / 自杀"；模式写成 [s]ogou… 字符类后，参数文本不再命中正则。
    fun servicePgrepArgs(): List<String> = listOf("pgrep", "-f", "[s]ogoupinyin-service")
    fun watchdogPgrepArgs(): List<String> = listOf("pgrep", "-f", "[s]ogoupinyin-watchdog")
    fun fcitxPgrepArgs(): List<String> = listOf("pgrep", "-x", "fcitx")

    /** pgrep 输出出现非空行即有匹配进程（每行一个 PID） */
    fun pgrepHasMatch(output: String): Boolean = output.lineSequence().any { it.isNotBlank() }

    fun pkillCommand(): String = "pkill -f '[s]ogoupinyin-(service|watchdog)' || true"
    fun startWatchdogCommand(): String = "nohup $WATCHDOG_PATH >/dev/null 2>&1 &"
    fun startServiceCommand(): String = "nohup $SERVICE_PATH >/dev/null 2>&1 &"

    /** fcitx 在跑用 -r 原地重启（刷新与引擎的 D-Bus 连接），没跑用 -d 启动 */
    fun restartFcitxCommand(fcitxRunning: Boolean): String =
        if (fcitxRunning) "nohup fcitx -r >/dev/null 2>&1 &" else "nohup fcitx -d >/dev/null 2>&1 &"

    /**
     * 一键修复序列：清残留（引擎卡死而非退出时直接拉起会总线冲突）→ 拉看门狗 → 拉引擎 → 重启 fcitx。
     * nohup+& 后台拉起：命令本身秒回，拉起的进程不随 Atlas 退出。
     */
    fun repairCommands(status: Status?): List<String> {
        if (status != null && !status.installed) return emptyList()
        return buildList {
            add(pkillCommand())
            add("sleep 1")
            add(startWatchdogCommand())
            add(startServiceCommand())
            add(restartFcitxCommand(status?.fcitxRunning ?: true))
        }
    }

    fun statusLines(status: Status): List<String> = listOf(
        "引擎 service          : " + if (status.serviceRunning) "运行中" else "未运行",
        "看门狗 watchdog       : " + if (status.watchdogRunning) "运行中" else "未运行",
        "fcitx 框架            : " + if (status.fcitxRunning) "运行中" else "未运行",
    )
}
