package atlas.core

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 飞书打卡的纯逻辑（UI 无关，可单测）。
 *
 * 自 Summary 仓 tools/launcher_tool/feishu_checkin.py 原生移植：adb 控制手机完成考勤打卡
 * （唤醒 → PIN 解锁 → 飞书 → 工作台 → 考勤系统 → 考勤签到 → 截图 → 熄屏）。
 * 硬化点（对应 launcher_tool/README.md 的故障排查 playbook）：
 * - uiautomator dump 被系统 kill（137，考勤 WebView 加载中）按失败走静默重试，不中断流程；
 * - 「USB 已连接」系统弹窗自动点「取消」；
 * - 点「考勤系统」后等 5s（小程序加载慢），其余步骤 1.5s；
 * - 「签到超时，请重新签到」= 考勤页面会话过期：点「我知道了」→ 重启飞书整轮重试一次；
 * - 单条命令 waitFor(30s) 超时强杀、整体 120s 兜底，杜绝进程悬挂。
 */
object FeishuCheckin {

    const val FEISHU_PACKAGE = "com.ss.android.lark"

    const val MODE_AUTO = "auto"
    const val MODE_USB = "usb"
    const val MODE_WIRELESS = "wireless"

    /** 单条 adb 命令超时 */
    private const val CMD_TIMEOUT_MS = 30_000L

    /** 整体流程兜底超时 */
    const val OVERALL_TIMEOUT_MS = 120_000L

    private const val RETRY_DELAY_MS = 1_000L
    private const val TAP_RETRIES = 5

    private const val REMOTE_DUMP = "/sdcard/atlas_window_dump.xml"
    private const val REMOTE_SHOT = "/sdcard/atlas_feishu_checkin.png"

    private val DEVICE_STATES = setOf("device", "offline", "unauthorized")

    /** 打卡配置：mode = MODE_USB（自动枚举第一台 USB 设备）或 MODE_WIRELESS（直连 ip:port） */
    data class FeishuConfig(
        val mode: String,
        val deviceIp: String = "",
        val devicePort: Int = 5555,
        val pin: String,
    )

    /** 一次打卡运行暴露给 UI 的状态（跨页签保留） */
    data class FeishuRun(
        val running: Boolean,
        val lines: List<String> = emptyList(),
        val exitCode: Int? = null,
        val summary: String? = null,
        val screenshotPath: String? = null,
    )

    /** uiautomator dump 里的一个节点（bounds 已换算为中心点坐标） */
    data class UiNode(val text: String, val desc: String, val cx: Int, val cy: Int)

    class AdbCommandFailedException(val command: String, val exitCode: Int) :
        Exception("adb 命令失败(exit $exitCode)：$command")

    // ---------- 纯函数 ----------

    /** adb 可执行文件查找：用户 SDK 优先，其次常见系统路径与 PATH（顺序照抄 python 版 _find_adb） */
    fun findAdb(home: String = System.getProperty("user.home")): String? {
        val fixed = listOf(
            File(home, "Android/Sdk/platform-tools/adb"),
            File("/usr/bin/adb"),
            File("/usr/local/bin/adb"),
        )
        fixed.firstOrNull { it.isFile && it.canExecute() }?.let { return it.absolutePath }
        System.getenv("PATH")?.split(":")?.forEach { dir ->
            val f = File(dir, "adb")
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    /** 解析 `adb devices` 输出为 (序列号, 状态) 列表 */
    fun parseDevices(output: String): List<Pair<String, String>> =
        output.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] in DEVICE_STATES) parts[0] to parts[1] else null
        }

    /** 取第一台在线的 USB 设备序列号（排除 ip:port 无线条目，语义同 python 版） */
    fun usbSerial(output: String): String? =
        parseDevices(output).firstOrNull { (serial, state) -> state == "device" && ':' !in serial }?.first

    private val IPV4 = Regex("""(\d{1,3}\.){3}\d{1,3}""")

    /** 从 `ip route` 输出解析 wlan0 的 src 地址（手机 Wi-Fi 内网 IP）；无 Wi-Fi 路由返回 null */
    fun parseWlanIp(ipRouteOutput: String): String? =
        ipRouteOutput.lineSequence()
            .filter { "wlan0" in it && " src " in it }
            .map { it.substringAfter(" src ").trim().substringBefore(' ') }
            .firstOrNull { IPV4.matches(it) }

    /** "[x1,y1][x2,y2]" → 中心坐标；格式不符返回 null */
    fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        val m = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(bounds.trim()) ?: return null
        val v = m.groupValues
        return (v[1].toInt() + v[3].toInt()) / 2 to (v[2].toInt() + v[4].toInt()) / 2
    }

    /** 解析 uiautomator dump 的 XML（只取 node 元素的 text/content-desc/bounds）；坏 XML 返回空表 */
    fun parseDump(xml: String): List<UiNode> = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("node")
        (0 until nodes.length).mapNotNull { i ->
            val el = nodes.item(i) as? Element ?: return@mapNotNull null
            val (cx, cy) = parseBoundsCenter(el.getAttribute("bounds")) ?: return@mapNotNull null
            UiNode(el.getAttribute("text"), el.getAttribute("content-desc"), cx, cy)
        }
    }.getOrDefault(emptyList())

    /** 语义照抄 python _tap_node：精确与包含在同一次遍历里按文档序取第一个命中 */
    fun findNode(nodes: List<UiNode>, text: String, partial: Boolean = true): UiNode? =
        nodes.firstOrNull { n ->
            n.text == text || n.desc == text || (partial && (text in n.text || text in n.desc))
        }

    /** 系统弹窗「USB 已连接」挡界检测（精确文本，避免误伤） */
    fun hasUsbConnectedPopup(nodes: List<UiNode>): Boolean = nodes.any { it.text == "USB 已连接" }

    /** 「签到超时，请重新签到」= 考勤 WebView 会话过期，重跑流程才能恢复 */
    fun hasCheckinTimeout(nodes: List<UiNode>): Boolean = nodes.any { "签到超时" in it.text }

    /** 日志/进度脱敏：KEYCODE_数字 序列可还原 PIN，统一打码 */
    fun maskPinInCommand(command: String): String =
        command.replace(Regex("KEYCODE_\\d"), "KEYCODE_*")

    // ---------- PIN 独立文件（0600；不进 settings.properties，守 PRD「配置=无密钥」） ----------

    fun pinFile(configDir: File): File = File(configDir, "feishu-checkin.pin")

    fun readPin(configDir: File): String =
        pinFile(configDir).takeIf { it.isFile }?.readText()?.trim().orEmpty()

    fun writePin(configDir: File, pin: String) {
        val f = pinFile(configDir)
        f.parentFile?.mkdirs()
        f.writeText(pin)
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                f.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

    /** 链路连通性预检结果（UI 状态展示 + 执行前探测共用） */
    data class LinkStatus(
        /** 配置的无线目标 ip:port；未配置为 null */
        val wirelessTarget: String?,
        /** 无线链路是否在线 */
        val wirelessOnline: Boolean,
        /** 在线 USB 设备序列号 */
        val usbSerial: String?,
    ) {
        val connected: Boolean get() = wirelessOnline || usbSerial != null

        /** 面向用户的一句话状态（中性措辞，不提具体业务） */
        fun describe(): String = when {
            wirelessOnline && usbSerial != null -> "无线 $wirelessTarget 与 USB ($usbSerial) 均在线，优先走无线"
            wirelessOnline -> "无线已连接 $wirelessTarget"
            usbSerial != null -> "USB 已连接 ($usbSerial)"
            wirelessTarget != null -> "未检测到手机：无线 $wirelessTarget 不可达，也未插 USB"
            else -> "未检测到手机：未配置无线地址，也未插 USB"
        }
    }

    // ---------- 执行 ----------

    private class CmdResult(val exitCode: Int, val output: String)

    /** 阻塞式 adb 会话：所有命令走它，统一超时与失败上抛 */
    private class Adb(private val adbPath: String, private val serial: String?) {

        fun run(vararg args: String, check: Boolean = true, timeoutMs: Long = CMD_TIMEOUT_MS): CmdResult {
            val cmd = mutableListOf(adbPath)
            if (serial != null) { cmd += "-s"; cmd += serial }
            cmd += args
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // 先异步读满输出再等退出：readText 会阻塞到 EOF，若先读后等，进程挂死时超时永远不触发
            val outputFuture = CompletableFuture.supplyAsync { proc.inputStream.bufferedReader().readText() }
            val exited = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                proc.destroyForcibly()
                outputFuture.cancel(true)
                val masked = maskPinInCommand(cmd.joinToString(" "))
                if (check) throw AdbCommandFailedException("$masked（超时 ${timeoutMs}ms 强杀）", -1)
                return CmdResult(-1, "")
            }
            val output = runCatching { outputFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            if (check && proc.exitValue() != 0) {
                throw AdbCommandFailedException(maskPinInCommand(cmd.joinToString(" ")), proc.exitValue())
            }
            return CmdResult(proc.exitValue(), output)
        }

        fun shell(vararg args: String, check: Boolean = true) = run("shell", *args, check = check)

        /** dump 当前界面；失败（被 kill/137、pull 失败、坏 XML）返回 null 交给调用方重试 */
        fun dumpUi(localDump: File): List<UiNode>? {
            if (run("shell", "uiautomator", "dump", REMOTE_DUMP, check = false).exitCode != 0) return null
            if (run("pull", REMOTE_DUMP, localDump.absolutePath, check = false).exitCode != 0) return null
            return runCatching { parseDump(localDump.readText()) }.getOrNull()
        }

        fun screenshot(target: File): Boolean =
            run("shell", "screencap", "-p", REMOTE_SHOT, check = false).exitCode == 0 &&
                run("pull", REMOTE_SHOT, target.absolutePath, check = false).exitCode == 0

        fun tap(node: UiNode) = shell("input", "tap", node.cx.toString(), node.cy.toString())
    }

    /** 链路预检（幂等：无线已在线时 `adb connect` 秒回，无副作用）。UI 状态展示与执行前探测共用 */
    fun checkLink(config: FeishuConfig): LinkStatus {
        val target = config.deviceIp.trim().takeIf { it.isNotEmpty() }?.let { "$it:${config.devicePort}" }
        val adbPath = findAdb() ?: return LinkStatus(target, wirelessOnline = false, usbSerial = null)
        return probeLink(config, Adb(adbPath, null))
    }

    private fun probeLink(config: FeishuConfig, adb: Adb): LinkStatus {
        val target = config.deviceIp.trim().takeIf { it.isNotEmpty() }?.let { "$it:${config.devicePort}" }
        var wirelessOnline = false
        if (target != null) {
            val conn = adb.run("connect", target, check = false)
            val devices = adb.run("devices", check = false)
            wirelessOnline = conn.exitCode == 0 && "failed" !in conn.output.lowercase() &&
                parseDevices(devices.output).any { it.first == target && it.second == "device" } &&
                "offline" !in devices.output
        }
        return LinkStatus(target, wirelessOnline, usbSerial(adb.run("devices", check = false).output))
    }

    /** USB 一键转无线结果：ok = 无线目标已可连；ip = 读到的手机 Wi-Fi 地址（成功时由调用方落配置） */
    data class UsbWirelessResult(val ok: Boolean, val message: String, val ip: String? = null)

    /**
     * USB 一键转无线：要求手机经 USB 在线。读取 wlan0 地址 → `tcpip` 切端口 → `connect`。
     * 用户不再需要理解无线调试的 IP/端口配对流程。阻塞调用，放 IO 协程。
     */
    fun usbToWireless(config: FeishuConfig): UsbWirelessResult {
        val adbPath = findAdb()
            ?: return UsbWirelessResult(false, "未找到 adb，请确认 Android platform-tools 已安装")
        val host = Adb(adbPath, null)
        val serial = usbSerial(host.run("devices", check = false).output)
            ?: return UsbWirelessResult(false, "未找到 USB 连接的设备：请先用数据线连接手机（需已开 USB 调试）")
        val usb = Adb(adbPath, serial)
        val ip = parseWlanIp(usb.shell("ip", "route", check = false).output)
            ?: return UsbWirelessResult(false, "无法读取手机 Wi-Fi 地址：请确认手机已连上 Wi-Fi")
        usb.run("tcpip", config.devicePort.toString(), check = false)
        val target = "$ip:${config.devicePort}"
        host.run("connect", target, check = false)
        val online = parseDevices(host.run("devices", check = false).output)
            .any { it.first == target && it.second == "device" }
        return if (online) UsbWirelessResult(true, "无线已连接 $target，之后可拔掉 USB 线", ip)
        else UsbWirelessResult(false, "无线连接失败：$target（手机与电脑需在同一网络）", ip)
    }

    /** 设备接入解析结果：Resolved=已选定 serial；Failed=带原因失败（消息已可直出） */
    private sealed class SerialResult {
        data class Resolved(val serial: String) : SerialResult()
        data class Failed(val message: String) : SerialResult()
    }

    /** 自动（无线优先、USB 兜底）或强制通道的设备接入，探测 + 选择 + 进度输出一体 */
    private fun resolveSerial(config: FeishuConfig, noSerialAdb: Adb, progress: (String) -> Unit): SerialResult {
        val link = probeLink(config, noSerialAdb)
        return when (config.mode) {
            MODE_WIRELESS -> {
                val t = link.wirelessTarget ?: return SerialResult.Failed("未配置手机 IP，请先在设置里填写")
                if (!link.wirelessOnline) return SerialResult.Failed("无线连接失败：$t")
                progress("已连接无线设备: $t")
                SerialResult.Resolved(t)
            }
            MODE_USB -> {
                val s = link.usbSerial ?: return SerialResult.Failed("未找到 USB 连接的设备")
                progress("已选择 USB 设备: $s")
                SerialResult.Resolved(s)
            }
            else -> {
                if (link.wirelessOnline) {
                    progress("已连接无线设备: ${link.wirelessTarget}")
                    SerialResult.Resolved(link.wirelessTarget!!)
                } else if (link.usbSerial != null) {
                    progress("无线不可达，改用 USB 设备: ${link.usbSerial}")
                    SerialResult.Resolved(link.usbSerial!!)
                } else {
                    SerialResult.Failed("${link.describe()}，插线或在设置里更新无线地址后重试")
                }
            }
        }
    }

    /**
     * 链路测试（阻塞，放 IO 协程）：设备接入 → 唤醒 →（有 PIN 时）解锁 → 启动应用 → 截图。
     * 只验证主流程的前半段通路，不 force-stop、不进入目标页面；结束后不熄屏（留在应用页面供人工确认）。
     * PIN 可空：空则跳过解锁步骤（锁屏下启动只能验证到应用进程拉起）。
     */
    fun runLinkTest(
        config: FeishuConfig,
        configDir: File,
        onProgress: (String) -> Unit,
    ): FeishuRun {
        val lines = mutableListOf<String>()
        val outDir = File(configDir, "feishu-checkin").apply { mkdirs() }
        fun progress(msg: String) { lines += msg; onProgress(msg) }
        fun finish(exitCode: Int, summary: String, screenshot: String? = null) = FeishuRun(
            running = false, lines = lines.toList(), exitCode = exitCode,
            summary = summary, screenshotPath = screenshot,
        )

        val adbPath = findAdb() ?: return finish(-1, "未找到 adb，请确认 Android platform-tools 已安装")
        progress("使用 adb: $adbPath")
        val noSerialAdb = Adb(adbPath, null)
        val serial = when (val r = resolveSerial(config, noSerialAdb, ::progress)) {
            is SerialResult.Resolved -> r.serial
            is SerialResult.Failed -> return finish(1, r.message)
        }
        val adb = Adb(adbPath, serial)

        try {
            progress("测试通路：唤醒 → 解锁 → 启动应用")
            val awake = adb.shell("dumpsys", "power", check = false)
                .output.contains("mWakefulness=Awake")
            if (awake) {
                progress("屏幕已亮，跳过唤醒")
            } else {
                adb.shell("input", "keyevent", "26", check = false)
                sleepMs(800)
                progress("已唤醒屏幕")
            }
            if (config.pin.isBlank()) {
                progress("未配置 PIN，跳过解锁（锁屏下仅验证到应用拉起）")
            } else {
                progress("输入 PIN 解锁")
                adb.shell("input", "swipe", "540", "1800", "540", "800", "300")
                sleepMs(800)
                config.pin.forEach { d -> adb.shell("input", "keyevent", "KEYCODE_$d"); sleepMs(100) }
                adb.shell("input", "keyevent", "66")
                sleepMs(1500)
            }
            adb.shell("monkey", "-p", FEISHU_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
            sleepMs(2500)
            progress("应用已启动")
            val shot = File(outDir, "last-linktest.png")
            adb.screenshot(shot)
            progress("结果截图 ${shot.absolutePath}")
            return finish(0, "通路正常：已唤醒并启动应用，请看手机或下方截图", shot.absolutePath)
        } catch (e: AdbCommandFailedException) {
            val shot = File(outDir, "last-failed.png")
            adb.screenshot(shot)
            return finish(1, e.message ?: "adb 命令失败", shot.absolutePath.takeIf { shot.isFile })
        }
    }

    /**
     * 执行一次完整打卡（阻塞，建议放 IO 协程）。进度经 onProgress 逐行上报；
     * PIN 只进 adb 命令，不进 onProgress。
     */
    fun runCheckin(
        config: FeishuConfig,
        configDir: File,
        onProgress: (String) -> Unit,
    ): FeishuRun {
        val lines = mutableListOf<String>()
        val outDir = File(configDir, "feishu-checkin").apply { mkdirs() }
        val localDump = File(outDir, "window_dump.xml")
        fun progress(msg: String) { lines += msg; onProgress(msg) }
        fun finish(exitCode: Int, summary: String, screenshot: String? = null) = FeishuRun(
            running = false, lines = lines.toList(), exitCode = exitCode,
            summary = summary, screenshotPath = screenshot,
        )

        if (config.pin.isBlank()) return finish(-1, "未设置手机 PIN，请先在卡片里配置")
        val adbPath = findAdb() ?: return finish(-1, "未找到 adb，请确认 Android platform-tools 已安装")
        progress("使用 adb: $adbPath")
        val startedAt = System.currentTimeMillis()
        fun outOfTime() = System.currentTimeMillis() - startedAt > OVERALL_TIMEOUT_MS

        // 设备接入：自动（无线优先、USB 兜底）或强制指定通道
        val noSerialAdb = Adb(adbPath, null)
        val serial = when (val r = resolveSerial(config, noSerialAdb, ::progress)) {
            is SerialResult.Resolved -> r.serial
            is SerialResult.Failed -> return finish(1, r.message)
        }
        val adb = Adb(adbPath, serial)

        try {
            if (outOfTime()) return finish(1, "流程整体超时中止")
            adb.shell("svc", "power", "stayon", "true", check = false)
            progress("唤醒屏幕")
            adb.shell("input", "keyevent", "26", check = false)
            sleepMs(500)
            progress("输入 PIN 解锁")
            adb.shell("input", "swipe", "540", "1800", "540", "800", "300")
            sleepMs(800)
            config.pin.forEach { d -> adb.shell("input", "keyevent", "KEYCODE_$d"); sleepMs(100) }
            adb.shell("input", "keyevent", "66")
            sleepMs(1500)

            progress("启动应用")
            adb.shell("am", "force-stop", FEISHU_PACKAGE, check = false)
            sleepMs(500)
            adb.shell("monkey", "-p", FEISHU_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
            sleepMs(2500)

            if (!navigateSteps(adb, ::progress, ::outOfTime, localDump)) {
                val shot = File(outDir, "last-failed.png")
                adb.screenshot(shot)
                return finish(1, "流程中断：界面元素未找到，请看失败截图", shot.absolutePath)
            }

            // 「签到超时，请重新签到」= 会话过期：确认后重启应用整轮重试一次
            sleepMs(3000)
            if (outOfTime()) return finish(1, "流程整体超时中止")
            val after = adb.dumpUi(localDump)
            if (after != null && hasCheckinTimeout(after)) {
                progress("页面会话过期，重启应用重试")
                findNode(after, "我知道了")?.let { adb.tap(it) }
                sleepMs(1000)
                adb.shell("am", "force-stop", FEISHU_PACKAGE, check = false)
                sleepMs(500)
                adb.shell("monkey", "-p", FEISHU_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
                sleepMs(2500)
                if (!navigateSteps(adb, ::progress, ::outOfTime, localDump)) {
                    val shot = File(outDir, "last-failed.png")
                    adb.screenshot(shot)
                    return finish(1, "重试后仍未完成，请看失败截图", shot.absolutePath)
                }
                sleepMs(3000)
            }

            progress("流程执行完毕")
            val shot = File(outDir, "last-result.png")
            adb.screenshot(shot)
            progress("结果截图 ${shot.absolutePath}")
            return finish(0, "流程执行完毕，请在截图中确认结果", shot.absolutePath)
        } catch (e: AdbCommandFailedException) {
            val shot = File(outDir, "last-failed.png")
            adb.screenshot(shot)
            return finish(1, e.message ?: "adb 命令失败", shot.absolutePath.takeIf { shot.isFile })
        } finally {
            // 无论成败都恢复供电并熄屏（python 版失败路径会漏掉这两步，这里补上）
            adb.shell("svc", "power", "stayon", "false", check = false)
            adb.shell("input", "keyevent", "26", check = false)
        }
    }

    /** 打卡步骤：target=界面匹配文本（内部），label=中性化显示名（进 UI/日志），settleMs=点击后等待 */
    private data class Step(val target: String, val label: String, val settleMs: Long)

    /** 依次点击三步的内部匹配文本与对外中性显示名 */
    private val STEPS = listOf(
        Step("工作台", "应用首页", 1500L),
        Step("考勤系统", "目标页面", 5000L),
        Step("考勤签到", "执行操作", 1500L),
    )

    /** 依次点击 应用首页 → 目标页面 → 执行操作；步后等待：目标页面是小程序加载慢，给 5s */
    private fun navigateSteps(
        adb: Adb,
        progress: (String) -> Unit,
        outOfTime: () -> Boolean,
        localDump: File,
    ): Boolean {
        for (step in STEPS) {
            if (!tapText(adb, step, progress, outOfTime, localDump)) return false
            sleepMs(step.settleMs)
        }
        return true
    }

    /** 语义照抄 python tap_text：dump 失败静默重试；USB 弹窗先关；5 次不中放弃。进度只出中性显示名 */
    private fun tapText(
        adb: Adb,
        step: Step,
        progress: (String) -> Unit,
        outOfTime: () -> Boolean,
        localDump: File,
    ): Boolean {
        repeat(TAP_RETRIES) { attempt ->
            if (outOfTime()) return false
            val nodes = adb.dumpUi(localDump)
            if (nodes == null) { sleepMs(RETRY_DELAY_MS); return@repeat }
            if (hasUsbConnectedPopup(nodes)) {
                findNode(nodes, "取消", partial = false)?.let { cancel ->
                    adb.tap(cancel)
                    progress("已关闭「USB 已连接」弹窗")
                    sleepMs(1500)
                    return@repeat
                }
            }
            val hit = findNode(nodes, step.target)
            if (hit != null) {
                progress("点击 '${step.label}' 坐标 (${hit.cx}, ${hit.cy})")
                adb.tap(hit)
                return true
            }
            progress("未找到 '${step.label}'，重试 ${attempt + 1}/$TAP_RETRIES")
            sleepMs(RETRY_DELAY_MS)
        }
        return false
    }

    private fun sleepMs(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
