package atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import atlas.core.AppSettings
import atlas.core.AaosCommandRunner
import atlas.core.DeviceTools
import atlas.core.DocMarker
import atlas.core.FeishuCheckin
import atlas.core.Inbox
import atlas.core.Log
import atlas.core.MdStores
import atlas.core.MdStores.CardEntry
import atlas.core.MdStores.QuestionEntry
import atlas.core.NoteFile
import atlas.core.OutboxTasks
import atlas.core.PetDebugTools
import atlas.core.Prompts
import atlas.core.QuestionStatus
import atlas.core.WmsParser
import atlas.core.SettingsStore
import atlas.core.SourceQuestions
import atlas.core.TextDiff
import atlas.core.Tools
import atlas.fsrs.FsrsEngine
import atlas.index.Indexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.awt.Desktop
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_TOOL_COMMAND_TIMEOUT_MS = 30_000L
private const val APK_PUSH_TIMEOUT_MS = 10 * 60_000L

/** 单道题相对 git HEAD 的内容差异，行内着色用：题面给字符区间，答案给行号集合。 */
data class SourceQuestionGitDiff(
    val changed: Boolean,
    /** 题面中变化的字符区间（当前题面文本坐标） */
    val questionRanges: List<IntRange> = emptyList(),
    /** 答案中变化的行下标（当前答案 lines() 坐标） */
    val answerDirtyLines: Set<Int> = emptySet(),
)

enum class ReadmeSaveResult { SAVED, CONFLICT, UNAVAILABLE, FAILED }

/** 当前题目列表读取 diff 的寻址 key。题号只用于定位当前条目，不用于和 HEAD 配对。 */
internal fun sourceQuestionGitKey(entry: SourceQuestions.Entry): String =
    "${entry.sourcePath}#${entry.number}"

/**
 * 应用中枢：持有全部状态与动作。UI 只读状态 + 调动作。
 */
class AppStore(val configDir: File = File(System.getProperty("user.home"), ".local/share/atlas")) {

    val scope = CoroutineScope(Dispatchers.IO)

    private val settingsStore = SettingsStore(File(configDir, "settings.properties"))

    var settings by mutableStateOf(AppSettings())
    var libraryReady by mutableStateOf(false)
    var bootError by mutableStateOf<String?>(null)

    var conn: java.sql.Connection? = null
    var indexer: Indexer? = null

    val scanning = mutableStateOf(false)
    val scanMessage = mutableStateOf("")

    val notes = mutableStateListOf<NoteFile>()
    val cards = mutableStateListOf<CardEntry>()
    val questions = mutableStateListOf<QuestionEntry>()
    /** 当前同源题目文档中的题目；首期只开放 SourceQuestions.TARGET_PATH。 */
    val sourceQuestions = mutableStateListOf<SourceQuestions.Entry>()
    /** 整棵知识库目录树中的 Q 题目，用于题库全局搜索。 */
    val allSourceQuestions = mutableStateListOf<SourceQuestions.Entry>()
    /** 题目内容相对 git HEAD 的差异；key = sourceQuestionGitKey，缺值 = 未知/未加载/git 不可用。 */
    val sourceQuestionGitDiffs = mutableStateMapOf<String, SourceQuestionGitDiff>()
    /** 当前同源题目文档中的章节标题，独立于题目答案展示。 */
    val sourceSections = mutableStateListOf<SourceQuestions.SectionHeading>()
    /** 题库左侧的知识库 Markdown 文档列表。 */
    val knowledgeDocuments = mutableStateListOf<String>()
    /** 题目源目录中额外纳入题库树的 README 文档。 */
    val sourceReadmeDocuments = mutableStateListOf<String>()
    /** 当前选中的 README 全文；题目文档选中时为空。 */
    var sourceReadmeContent by mutableStateOf<String?>(null)

    private val selectedSourcePathState = mutableStateOf(SourceQuestions.TARGET_PATH)

    /**
     * 当前同源题目文档。选择持久化到 settings：重启恢复上次选择（2026-09-24 用户反馈：
     * 每次进题库都默认打开 01-语法基础.md）；换库或文档失效时由 reloadKnowledgeFiles 回退。
     */
    var selectedSourcePath: String
        get() = selectedSourcePathState.value
        set(value) {
            if (selectedSourcePathState.value == value) return
            selectedSourcePathState.value = value
            if (settings.selectedSourcePath != value) {
                settings = settings.copy(selectedSourcePath = value)
                runCatching { settingsStore.save(settings, File(settings.libraryPath, "atlas/config/settings.properties")) }
                    .onFailure { Log.e("持久化题库选中文档失败", it) }
            }
        }
    val candidates = mutableStateListOf<Inbox.Candidate>()
    val outbox = mutableStateListOf<OutboxTasks.OutboxTask>()

    // 复习会话状态
    val dueQueue = mutableStateListOf<CardEntry>()
    var reviewIdx by mutableStateOf(-1)
    var showingBack by mutableStateOf(false)
    var reviewDeckFilter by mutableStateOf("全部")

    // 本轮复习会话统计（重建队列时清零）
    private val sessionGrades = mutableStateListOf<String>()
    private var sessionStart = 0L

    // 跨视图跳转请求（复习卡来源锚点 / Ctrl+K 命中 → 预览浮层）
    var pendingPreview by mutableStateOf<String?>(null)

    val toast = mutableStateOf<String?>(null)

    /** 工具页：27HM 日志解密的运行状态（跨页签保留，进程在 scope 托管的 IO 协程里跑） */
    val hcToolRun = mutableStateOf<Tools.ToolRun?>(null)

    fun runHcLogTool(inputPath: String) {
        if (hcToolRun.value?.running == true) return
        val script = Tools.hcLogScriptFile()
        if (!script.isFile) {
            Log.e("工具页：解密脚本不存在 ${script.absolutePath}")
            hcToolRun.value = Tools.ToolRun(false, inputPath, exitCode = -1, tail = listOf("未找到解密脚本：${script.absolutePath}"))
            showToast("未找到解密脚本，请确认 Summary 仓库位置")
            return
        }
        Log.i("工具页：运行日志解密 input=$inputPath")
        hcToolRun.value = Tools.ToolRun(running = true, inputPath = inputPath)
        scope.launch {
            runCatching {
                val proc = ProcessBuilder(Tools.hcLogCommand(script.absolutePath, inputPath))
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().readText()
                Tools.parseToolOutput(text, proc.waitFor())
            }.onSuccess { run ->
                if (run.exitCode == 0) Log.i("工具页：解密完成 ${run.summary}")
                else Log.w("工具页：解密结束 exitCode=${run.exitCode}")
                hcToolRun.value = run
                if (run.exitCode != 0) showToast("解密未成功，请查看输出详情")
            }.onFailure { e ->
                Log.e("工具页：解密运行异常", e)
                hcToolRun.value = Tools.ToolRun(false, inputPath, exitCode = -1, tail = listOf("运行失败：${e.message}"))
                showToast("解密运行失败：${e.message}")
            }
        }
    }

    /** 工具页：任务自动化运行状态（跨页签保留，阻塞流程在 scope 的 IO 协程里跑） */
    val feishuRun = mutableStateOf<FeishuCheckin.FeishuRun?>(null)

    /** 工具页：手机链路连通状态（卡片可见时由 UI 定时刷新） */
    val feishuLink = mutableStateOf<FeishuCheckin.LinkStatus?>(null)

    /** 链路预检（幂等，秒级），结果回写 feishuLink；执行期间跳过 */
    fun refreshFeishuLink() {
        if (feishuRun.value?.running == true) return
        scope.launch { refreshFeishuLinkNow() }
    }

    /** 手动重检的挂起变体：完成后返回结果（feishuLink 已同步更新），UI 据此报告检测结果 */
    suspend fun refreshFeishuLinkNow(): FeishuCheckin.LinkStatus? {
        if (feishuRun.value?.running == true) return feishuLink.value
        val link = runCatching { FeishuCheckin.checkLink(feishuConfig()) }.getOrNull()
        if (link != null) {
            feishuLink.value = link
            Log.d("工具页：链路检测 ${link.describe()}")
        }
        return link
    }

    /** 题库页：搜索栏可见性（默认隐藏，Ctrl+Shift+F 召出/收起；跨页签保留） */
    val questionSearchVisible = mutableStateOf(false)

    /** 工具页：USB 一键转无线进行中（按钮禁用 + 文案切换） */
    val feishuWirelessBusy = mutableStateOf(false)

    /** USB 一键转无线：读手机 Wi-Fi 地址 → tcpip → connect，成功即落配置并刷新链路（§6.4.18） */
    fun feishuUsbToWireless() {
        if (feishuRun.value?.running == true || feishuWirelessBusy.value) return
        feishuWirelessBusy.value = true
        scope.launch {
            val result = runCatching { FeishuCheckin.usbToWireless(feishuConfig()) }
                .getOrElse { FeishuCheckin.UsbWirelessResult(false, "转换失败：${it.message}") }
            if (result.ok && result.ip != null) {
                saveFeishuConfig(settings.feishuMode, result.ip, settings.feishuPort)
                Log.i("工具页：USB 转无线成功，配置已保存 ${result.ip}:${settings.feishuPort}")
            }
            feishuWirelessBusy.value = false
            showToast(result.message)
            if (result.ok) refreshFeishuLink()
        }
    }

    private fun feishuConfig(pin: String = "") = FeishuCheckin.FeishuConfig(
        mode = settings.feishuMode,
        deviceIp = settings.feishuIp,
        devicePort = settings.feishuPort,
        pin = pin,
    )

    fun runFeishuCheckin() {
        if (feishuRun.value?.running == true) return
        val pin = FeishuCheckin.readPin(configDir)
        if (pin.isBlank()) {
            showToast("请先在「设置」里保存手机锁屏 PIN")
            return
        }
        Log.i("工具页：运行任务自动化 mode=${settings.feishuMode} ip=${settings.feishuIp}")
        feishuRun.value = FeishuCheckin.FeishuRun(running = true)
        scope.launch {
            runCatching {
                FeishuCheckin.runCheckin(feishuConfig(pin), configDir) { line ->
                    Log.i("工具页：$line")
                    appendFeishuLine(line)
                }
            }.onSuccess { run ->
                feishuRun.value = run
                if (run.exitCode == 0) showToast("流程执行完毕，请确认截图")
                else showToast(run.summary ?: "未成功，请看输出详情")
                refreshFeishuLink()
            }.onFailure { e ->
                Log.e("工具页：自动化运行异常", e)
                feishuRun.value = FeishuCheckin.FeishuRun(
                    running = false, exitCode = -1, summary = "运行异常：${e.message}",
                )
                showToast("运行失败：${e.message}")
            }
        }
    }

    /** 链路测试：唤醒 → 解锁（有 PIN 时）→ 启动应用 → 截图，只验证通路不进入目标页面 */
    fun runFeishuLinkTest() {
        if (feishuRun.value?.running == true) return
        val pin = FeishuCheckin.readPin(configDir)
        Log.i("工具页：链路测试 mode=${settings.feishuMode} ip=${settings.feishuIp} pin=${pin.isNotBlank()}")
        feishuRun.value = FeishuCheckin.FeishuRun(running = true)
        scope.launch {
            runCatching {
                FeishuCheckin.runLinkTest(feishuConfig(pin), configDir) { line ->
                    Log.i("工具页：$line")
                    appendFeishuLine(line)
                }
            }.onSuccess { run ->
                feishuRun.value = run
                if (run.exitCode == 0) showToast("通路正常，请看手机或截图")
                else showToast(run.summary ?: "链路测试未成功")
                refreshFeishuLink()
            }.onFailure { e ->
                Log.e("工具页：链路测试异常", e)
                feishuRun.value = FeishuCheckin.FeishuRun(
                    running = false, exitCode = -1, summary = "链路测试异常：${e.message}",
                )
                showToast("链路测试失败：${e.message}")
            }
        }
    }

    private fun appendFeishuLine(line: String) {
        val cur = feishuRun.value ?: return
        feishuRun.value = cur.copy(lines = (cur.lines + line).takeLast(30))
    }

    fun saveFeishuConfig(mode: String, ip: String, port: Int) {
        settings = settings.copy(feishuMode = mode, feishuIp = ip.trim(), feishuPort = port)
        saveSettings()
    }

    fun saveFeishuPin(pin: String) {
        FeishuCheckin.writePin(configDir, pin)
        Log.i("工具页：锁屏 PIN 已更新（独立 0600 文件）")
    }

    // ---------- 工具页：设备工具箱（launcher_tool 原生移植，单并发沿用原约束） ----------

    val toolRun = mutableStateOf(DeviceTools.ToolRun())

    private val toolMutex = Mutex()

    /** 在线设备列表（adb devices 过滤 state=device，serial）；目标设备选择跨页签保留 */
    val toolboxDevices = mutableStateListOf<String>()

    /** 设备工具箱目标设备：工具页全部 adb 命令带 -s；默认避开无线手机（多设备在线场景，2026-09-29 用户反馈）。无线判定用 serial 冒号（语义同 FeishuCheckin.usbSerial） */
    var toolboxSerial by mutableStateOf<String?>(null)

    /** 刷新在线设备列表；当前选中已失效（掉线）时按默认规则重选（优先非无线） */
    fun refreshToolboxDevices() {
        val adbPath = FeishuCheckin.findAdb() ?: return
        scope.launch {
            val (_, out) = execCapture(DeviceTools.devicesArgs(adbPath))
            applyToolboxDevices(out)
        }
    }

    /** 同步变体：WMS 载入前先解析目标设备再拼命令（adb devices 秒级，放调用方协程） */
    private fun resolveToolboxSerial(): String? {
        val adbPath = FeishuCheckin.findAdb() ?: return null
        val (_, out) = execCapture(DeviceTools.devicesArgs(adbPath))
        applyToolboxDevices(out)
        return toolboxSerial
    }

    private fun applyToolboxDevices(devicesOutput: String) {
        val online = FeishuCheckin.parseDevices(devicesOutput)
            .filter { it.second == "device" }.map { it.first }
        toolboxDevices.clear()
        toolboxDevices.addAll(online)
        if (toolboxSerial == null || toolboxSerial !in online) {
            toolboxSerial = DeviceTools.pickDefaultSerial(online)
        }
    }

    val aaosRunner = AaosCommandRunner(scope)

    fun runAaosCommand(command: String) {
        aaosRunner.start(command, toolboxSerial, FeishuCheckin.findAdb())
            ?.let(::showToast)
    }

    fun stopAaosCommand() = aaosRunner.stop()

    fun runTool(tool: DeviceTools.Tool) {
        if (toolRun.value.running) {
            showToast("已有工具在运行，请稍候")
            return
        }
        scope.launch {
            toolMutex.withLock {
                val lines = mutableListOf<String>()
                fun progress(msg: String) {
                    lines += msg
                    toolRun.value = DeviceTools.ToolRun(tool.id, running = true, lines = lines.takeLast(50))
                }
                Log.i("工具页：运行工具 ${tool.id}")
                toolRun.value = DeviceTools.ToolRun(tool.id, running = true)
                val exit = runCatching { executeTool(tool, ::progress) }
                    .getOrElse { e ->
                        Log.e("工具页：工具运行异常 ${tool.id}", e)
                        progress("运行异常：${e.message}")
                        -1
                    }
                toolRun.value = DeviceTools.ToolRun(tool.id, running = false, lines = lines.takeLast(50), exitCode = exit)
                showToast(if (exit == 0) "${tool.label} 完成" else "${tool.label} 未成功（exit $exit）")
            }
        }
    }

    /** 单个工具的执行体，返回退出码（0=成功） */
    private fun executeTool(tool: DeviceTools.Tool, progress: (String) -> Unit): Int {
        val adbPath = FeishuCheckin.findAdb()
        return when (tool) {
            DeviceTools.Tool.PUSH_LAUNCHER -> {
                val apk = settings.pushApkPath
                if (apk.isBlank() || !File(apk).isFile) {
                    progress("APK 不存在：${apk.ifBlank { "未配置路径，请在设置里填写" }}")
                    return 1
                }
                val adb = adbPath ?: return failNoAdb(progress)
                val serial = toolboxSerial ?: return failNoDevice(progress)
                runSequence(
                    DeviceTools.pushLauncherCommands(adb, apk, serial),
                    progress,
                    commandTimeoutsMs = mapOf(3 to APK_PUSH_TIMEOUT_MS),
                )
            }
            DeviceTools.Tool.REBOOT_LAUNCHER -> {
                val adb = adbPath ?: return failNoAdb(progress)
                val serial = toolboxSerial ?: return failNoDevice(progress)
                progress("查找 ${DeviceTools.LAUNCHER_PACKAGE} 进程…")
                val (_, psOut) = execCapture(DeviceTools.listProcessesArgs(adb, serial))
                val pid = DeviceTools.parsePid(psOut, DeviceTools.LAUNCHER_PACKAGE)
                if (pid == null) {
                    progress("未找到运行中的 Launcher 进程")
                    return 1
                }
                progress("结束进程 PID=$pid")
                execCapture(DeviceTools.killPidArgs(adb, pid, serial))
                0
            }
            DeviceTools.Tool.SCREENSHOT -> {
                val adb = adbPath ?: return failNoAdb(progress)
                val serial = toolboxSerial ?: return failNoDevice(progress)
                val dir = File(settings.screenshotSaveDir.ifBlank { File(System.getProperty("user.home"), "Desktop").absolutePath })
                val target = DeviceTools.nextScreenshotFile(dir)
                val (code, png) = execCaptureBytes(DeviceTools.screenshotArgs(adb, serial))
                if (code != 0 || png.isEmpty()) {
                    progress("截屏失败（设备未连接？）")
                    return 1
                }
                target.writeBytes(png)
                progress("已保存 ${target.absolutePath}")
                0
            }
            DeviceTools.Tool.CLEAR_LOGCAT -> {
                val adb = adbPath ?: return failNoAdb(progress)
                val serial = toolboxSerial ?: return failNoDevice(progress)
                execCapture(DeviceTools.clearLogcatArgs(adb, serial))
                0
            }
            DeviceTools.Tool.START_EMULATOR, DeviceTools.Tool.COLD_BOOT_EMULATOR -> {
                val emu = DeviceTools.findEmulator() ?: run {
                    progress("未找到模拟器（~/Android/Sdk/emulator/emulator）")
                    return 1
                }
                val cold = tool == DeviceTools.Tool.COLD_BOOT_EMULATOR
                ProcessBuilder(DeviceTools.emulatorArgs(emu, settings.emulatorAvd, cold))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(false)
                    .start()
                progress("模拟器启动中（${settings.emulatorAvd}${if (cold) "，冷启动" else ""}）")
                0
            }
            DeviceTools.Tool.STRIP_SLASHES -> {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val text = runCatching {
                    cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    progress("剪贴板为空，请先复制要处理的内容")
                    return 1
                }
                cb.setContents(java.awt.datatransfer.StringSelection(DeviceTools.stripCommentSlashes(text)), null)
                progress("已处理并写回剪贴板，可直接粘贴")
                0
            }
        }
    }

    private fun failNoAdb(progress: (String) -> Unit): Int {
        progress("未找到 adb，请确认 Android platform-tools 已安装")
        return 1
    }

    private fun failNoDevice(progress: (String) -> Unit): Int {
        progress("未检测到在线设备：请连接设备，或在「目标」下拉刷新后重选")
        return 1
    }

    /** 顺序执行命令序列并流式收集输出；可为耗时步骤单独延长超时；首条失败即停 */
    private fun runSequence(
        cmds: List<List<String>>,
        progress: (String) -> Unit,
        commandTimeoutsMs: Map<Int, Long> = emptyMap(),
    ): Int {
        for ((index, cmd) in cmds.withIndex()) {
            progress("==> [${index + 1}/${cmds.size}] ${cmd.drop(1).joinToString(" ")}")
            val (code, out) = execCapture(cmd, commandTimeoutsMs[index] ?: DEFAULT_TOOL_COMMAND_TIMEOUT_MS)
            out.trim().takeIf { it.isNotEmpty() }?.let { progress(it.trim()) }
            if (code != 0) {
                progress("命令失败 exit=$code")
                return code
            }
        }
        return 0
    }

    /** 执行一条命令收集合并输出；超时强杀（先异步读满再等退出，防挂死） */
    private fun execCapture(cmd: List<String>, timeoutMs: Long = DEFAULT_TOOL_COMMAND_TIMEOUT_MS): Pair<Int, String> {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val future = CompletableFuture.supplyAsync { proc.inputStream.bufferedReader().readText() }
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            future.cancel(true)
            return -1 to "命令超时（${timeoutMs / 1_000}s）"
        }
        return proc.exitValue() to runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("")
    }

    private fun execCaptureBytes(cmd: List<String>, timeoutMs: Long = 30_000L): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val future = CompletableFuture.supplyAsync { proc.inputStream.readBytes() }
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            future.cancel(true)
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault(ByteArray(0))
    }

    // ---------- 工具页：logcat 流式捕获 ----------

    val logcatLines = mutableStateListOf<String>()
    val logcatRunning = mutableStateOf(false)
    private var logcatProcess: Process? = null

    /** 开始捕获（Kotlin 侧过滤，替代原 grep --line-buffered 管道）；目标是当前选中设备 */
    fun startLogcat(filter: String) {
        if (logcatRunning.value) return
        val adbPath = FeishuCheckin.findAdb()
        if (adbPath == null) {
            showToast("未找到 adb，请确认 Android platform-tools 已安装")
            return
        }
        val serial = toolboxSerial ?: run {
            showToast("未检测到在线设备：请连接设备，或在「目标」下拉刷新后重试")
            return
        }
        val f = filter.trim()
        val proc = ProcessBuilder(DeviceTools.logcatArgs(adbPath, serial)).redirectErrorStream(true).start()
        logcatProcess = proc
        logcatLines.clear()
        logcatRunning.value = true
        scope.launch {
            proc.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (f.isEmpty() || f in line) appendLogcatLine(line)
                }
            }
            // 流自然结束 = 设备断开
            logcatRunning.value = false
            if (logcatProcess === proc) logcatProcess = null
            showToast("日志流已结束")
        }
    }

    fun stopLogcat() {
        logcatProcess?.destroyForcibly()
        logcatProcess = null
        logcatRunning.value = false
    }

    private fun appendLogcatLine(line: String) {
        if (logcatLines.size >= 500) logcatLines.removeAt(0)
        logcatLines.add(line)
    }

    // ---------- 工具页：萌宠调试 ----------

    val petDebugRun = mutableStateOf(PetDebugTools.RunState())
    private var petDebugJob: Job? = null
    @Volatile private var petDebugGeneration = 0L
    @Volatile private var activePetProcess: Process? = null
    private var activePetGeneration: Long? = null
    private val petProcessLock = Any()

    fun runPetQuickAction(action: PetDebugTools.QuickAction) {
        runPetDebug(action.label) { adb, serial -> PetDebugTools.quickActionSteps(action, adb, serial) }
    }

    fun runPetScenario(scenario: PetDebugTools.Scenario) {
        runPetDebug("#${scenario.number} ${scenario.titleZh}") { adb, serial ->
            PetDebugTools.scenarioSteps(scenario, adb, serial)
        }
    }

    fun petScenarioTechnicalCommands(scenario: PetDebugTools.Scenario): List<String> {
        val adbPath = FeishuCheckin.findAdb() ?: return listOf("未找到 adb")
        val serial = toolboxSerial ?: return listOf("尚未选择目标设备")
        return PetDebugTools.scenarioTechnicalCommands(scenario, adbPath, serial)
    }

    fun petQuickActionTechnicalCommands(action: PetDebugTools.QuickAction): List<String> {
        val adbPath = FeishuCheckin.findAdb() ?: return listOf("未找到 adb")
        val serial = toolboxSerial ?: return listOf("尚未选择目标设备")
        return PetDebugTools.quickActionTechnicalCommands(action, adbPath, serial)
    }

    private fun runPetDebug(
        target: String,
        steps: (adbPath: String, serial: String) -> List<PetDebugTools.Step>,
    ) {
        if (petDebugRun.value.running) {
            showToast("已有萌宠命令正在执行")
            return
        }
        val adbPath = FeishuCheckin.findAdb() ?: run {
            showToast("未找到 adb，请确认 Android platform-tools 已安装")
            return
        }
        val serial = toolboxSerial ?: run {
            showToast("未检测到在线设备：请先刷新并选择目标")
            return
        }
        val allSteps = steps(adbPath, serial)
        val generation = ++petDebugGeneration
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val lines = mutableListOf<String>()
            fun update(step: Int, message: String? = null, exitCode: Int? = null, running: Boolean = true) {
                if (generation != petDebugGeneration) return
                message?.let { lines += it }
                petDebugRun.value = PetDebugTools.RunState(
                    target = target,
                    running = running,
                    step = step,
                    totalSteps = allSteps.size,
                    lines = lines.takeLast(80),
                    exitCode = exitCode,
                )
            }
            update(0, "目标设备：$serial")
            var exit = 0
            try {
                for ((index, step) in allSteps.withIndex()) {
                    when (step) {
                        is PetDebugTools.Step.Wait -> {
                            update(index + 1, "${step.explanation}（${step.millis} ms）")
                            delay(step.millis)
                        }
                        is PetDebugTools.Step.Note -> update(index + 1, step.text)
                        is PetDebugTools.Step.Run -> {
                            val command = step.command
                            update(index + 1, "${command.label}：${command.explanation}")
                            val (code, out) = execPetCommand(command, generation)
                            out.trim().takeIf { it.isNotEmpty() }?.let { update(index + 1, it) }
                            if (code != 0) {
                                exit = code
                                update(index + 1, "命令失败 exit=$code", exitCode = code, running = false)
                                break
                            }
                        }
                    }
                }
                if (exit == 0) {
                    update(allSteps.size, "执行完成", exitCode = 0, running = false)
                    showToast("$target 执行完成")
                } else {
                    showToast("$target 未成功（exit $exit）")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                synchronized(petProcessLock) {
                    if (activePetGeneration == generation) {
                        activePetProcess?.destroyForcibly()
                        activePetProcess = null
                        activePetGeneration = null
                    }
                }
                update(petDebugRun.value.step, "已停止后续步骤", exitCode = -2, running = false)
                if (generation == petDebugGeneration) showToast("萌宠命令已停止")
            } catch (error: Exception) {
                update(petDebugRun.value.step, "执行未能启动：${error.message ?: error.javaClass.simpleName}", exitCode = -1, running = false)
                if (generation == petDebugGeneration) showToast("萌宠调试执行失败，请检查 adb、设备连接和本地构建环境")
            } finally {
                synchronized(petProcessLock) {
                    if (generation == petDebugGeneration) petDebugJob = null
                }
            }
        }
        synchronized(petProcessLock) {
            petDebugJob = job
            job.start()
        }
    }

    fun stopPetDebug() {
        synchronized(petProcessLock) {
            activePetProcess?.destroyForcibly()
            activePetProcess = null
            activePetGeneration = null
            petDebugJob?.cancel()
        }
    }

    private fun execPetCommand(command: PetDebugTools.Command, generation: Long): Pair<Int, String> {
        val builder = ProcessBuilder(command.args).redirectErrorStream(true)
        command.workingDirectory?.let { builder.directory(File(it)) }
        val proc = synchronized(petProcessLock) {
            if (generation != petDebugGeneration || petDebugJob?.isActive == false) {
                throw kotlinx.coroutines.CancellationException()
            }
            builder.start().also {
                activePetProcess = it
                activePetGeneration = generation
            }
        }
        val future = CompletableFuture.supplyAsync {
            val tail = ArrayDeque<String>()
            var size = 0
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    tail.addLast(line)
                    size += line.length
                    while (tail.size > 80 || size > 16_384) size -= tail.removeFirst().length
                }
            }
            tail.joinToString("\n")
        }
        try {
            if (!proc.waitFor(command.timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                future.cancel(true)
                return -1 to "执行超时（${command.timeoutMs / 1_000}s）。检查设备连接或稍后重试。"
            }
            if (petDebugJob?.isActive == false) throw kotlinx.coroutines.CancellationException()
            val output = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            return proc.exitValue() to output
        } finally {
            synchronized(petProcessLock) {
                if (activePetProcess === proc) {
                    activePetProcess = null
                    activePetGeneration = null
                }
            }
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    /** 使用 logcat 的 tag 过滤能力，避免萌宠页面被整机日志淹没。 */
    fun startPetLogcat() {
        if (logcatRunning.value) return
        val adbPath = FeishuCheckin.findAdb() ?: run {
            showToast("未找到 adb，请确认 Android platform-tools 已安装")
            return
        }
        val serial = toolboxSerial ?: run {
            showToast("未检测到在线设备：请先刷新并选择目标")
            return
        }
        val proc = ProcessBuilder(PetDebugTools.petLogcatCommand(adbPath, serial).args)
            .redirectErrorStream(true).start()
        logcatProcess = proc
        logcatLines.clear()
        logcatRunning.value = true
        scope.launch {
            proc.inputStream.bufferedReader().useLines { seq -> seq.forEach(::appendLogcatLine) }
            logcatRunning.value = false
            if (logcatProcess === proc) logcatProcess = null
            showToast("萌宠日志流已结束")
        }
    }

    // ---------- 工具页：WMS 查看器 ----------
    // 树/diff/详情状态放 AppStore：侧栏切换分区后不丢已拉取的窗口树（dumpsys 重取成本高）

    val wmsLoading = mutableStateOf(false)
    val wmsError = mutableStateOf<String?>(null)
    var wmsLeftRoot by mutableStateOf<WmsParser.Node?>(null)
    var wmsRightRoot by mutableStateOf<WmsParser.Node?>(null)
    var wmsLeftDiff by mutableStateOf<Map<Int, WmsParser.Diff>>(emptyMap())
    var wmsRightDiff by mutableStateOf<Map<Int, WmsParser.Diff>>(emptyMap())
    var wmsDetail by mutableStateOf<String?>(null)

    /** 载入 dumpsys 输出到指定栏位（0=左 1=右）；目标是设备工具箱选中的设备（同页共享），载入前刷新一次设备列表；管道命令走 sh -c 保留 shell 语义（与 python 版一致） */
    fun loadWmsDump(command: String, slot: Int) {
        if (wmsLoading.value) return
        val adbPath = FeishuCheckin.findAdb()
        if (adbPath == null) {
            wmsError.value = "未找到 adb，请确认 Android platform-tools 已安装"
            return
        }
        wmsLoading.value = true
        wmsError.value = null
        scope.launch {
            val serial = resolveToolboxSerial()
            if (serial == null) {
                wmsError.value = "未检测到在线设备：请连接设备，或在设备工具箱「目标」下拉刷新"
                wmsLoading.value = false
                return@launch
            }
            val cmd = if ('|' in command || '\'' in command) {
                listOf("sh", "-c", "$adbPath -s $serial shell '${command.replace("'", "'\\''")}'")
            } else {
                listOf(adbPath, "-s", serial, "shell") + command.split(" ")
            }
            val (code, text) = execCapture(cmd)
            if (code != 0) {
                wmsError.value = "命令失败 exit=$code${if (text.isBlank()) "" else "：${text.take(200)}"}"
            } else {
                val root = WmsParser.parse(text.lines())
                if (slot == 0) {
                    wmsLeftRoot = root
                    wmsLeftDiff = emptyMap()
                } else {
                    wmsRightRoot = root
                    wmsRightDiff = emptyMap()
                }
                wmsDetail = null
            }
            wmsLoading.value = false
        }
    }

    /** 双栏对比：按展平序列对齐写回 diff 标记；返回 是否有差异（未载齐两栏返回 null） */
    fun compareWmsTrees(): Boolean? {
        val left = wmsLeftRoot ?: return null
        val right = wmsRightRoot ?: return null
        val (ld, rd) = WmsParser.diff(WmsParser.flatten(left), WmsParser.flatten(right))
        wmsLeftDiff = WmsParser.flatten(left)
            .mapIndexed { i, node -> System.identityHashCode(node) to ld[i] }
            .filter { it.second != WmsParser.Diff.NONE }.toMap()
        wmsRightDiff = WmsParser.flatten(right)
            .mapIndexed { i, node -> System.identityHashCode(node) to rd[i] }
            .filter { it.second != WmsParser.Diff.NONE }.toMap()
        return !(ld.all { it == WmsParser.Diff.NONE } && rd.all { it == WmsParser.Diff.NONE })
    }

    // ---------- 工具页：提示词库 ----------

    val prompts = mutableStateListOf<Prompts.Entry>()
    private var promptsLoaded = false

    fun loadPrompts() {
        if (promptsLoaded) return
        promptsLoaded = true
        val entries = Prompts.load(promptsFile())
        prompts.clear()
        prompts.addAll(entries)
    }

    private fun promptsFile() = File(configDir, "prompts.md")

    fun savePrompts() = Prompts.save(promptsFile(), prompts.toList())

    fun addPrompt(title: String, body: String) {
        prompts.add(Prompts.Entry(title.trim(), body.trim()))
        savePrompts()
    }

    fun updatePrompt(index: Int, title: String, body: String) {
        if (index !in prompts.indices) return
        prompts[index] = Prompts.Entry(title.trim(), body.trim())
        savePrompts()
    }

    fun deletePrompt(index: Int) {
        if (index !in prompts.indices) return
        prompts.removeAt(index)
        savePrompts()
    }

    fun copyPromptToClipboard(index: Int) {
        val entry = prompts.getOrNull(index) ?: return
        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        cb.setContents(java.awt.datatransfer.StringSelection(entry.title + "\n\n" + entry.body), null)
        showToast("已复制「${entry.title}」")
    }

    private var watchJob: Job? = null
    private val watching = AtomicBoolean(false)
    // reloadKnowledgeFiles 会被 UI 线程（新建/编辑写回）与文件监听协程并发触发，
    // clear→addAll 交错会让列表出现重复条目（LazyColumn key 冲突，2026-09-23），用锁串行化
    private val reloadLock = Any()

    private fun libraryRoot() = File(settings.libraryPath)
    private fun atlasDir() = File(libraryRoot(), "atlas")
    fun cardsFile() = File(atlasDir(), "cards.md")
    fun questionsFile() = File(atlasDir(), "questions.md")
    fun sourceQuestionFile(): File {
        return sourceDocumentFile(selectedSourcePath)
    }
    fun inboxDir() = File(atlasDir(), "inbox")
    fun outboxDir() = File(atlasDir(), "outbox")
    private fun dbFile(): File {
        val key = Integer.toHexString(libraryRoot().absolutePath.hashCode())
        return File(configDir, "lib-$key/atlas.db")
    }

    fun showToast(msg: String) { Log.i("toast: $msg"); toast.value = msg; scope.launch { delay(2600); toast.value = null } }

    fun openSkillEditor(skillName: String) {
        val file = Inbox.skillFile(skillName) ?: run {
            showToast("找不到 skill 文件：$skillName")
            return
        }
        try {
            Desktop.getDesktop().open(file)
            Log.i("打开 skill 编辑器 ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("打开 skill 编辑器失败 ${file.absolutePath}", e)
            showToast("打开失败：${e.message}")
        }
    }

    /** 启动：载入设置；若已配置库则打开 */
    fun boot() {
        bootError = null
        Log.i("boot() 开始")
        try {
            // 先读本机层（含 libraryPath 引导键），再以它定位仓库同步层叠加（§6.4.23）
            settings = settingsStore.load { lib -> File(lib, "atlas/config/settings.properties") }
            Log.i("设置已加载 libraryPath=${settings.libraryPath} 仅本地目录=${settings.localOnlyExtra.size}个")
            if (settings.libraryPath.isNotBlank() && File(settings.libraryPath).isDirectory) {
                openLibrary(settings.libraryPath, rescanIfNeeded = true)
            } else {
                Log.i("未配置有效知识库目录，进入 SetupView")
            }
        } catch (e: Exception) {
            Log.e("boot 失败", e)
            bootError = "启动失败：${e.message}"
        }
    }

    fun saveSettings() {
        Log.i("设置保存 libraryPath=${settings.libraryPath} 仅本地=${settings.localOnlyExtra.size}个 忽略额外=${settings.ignoredExtra.size}个")
        settingsStore.save(settings, File(settings.libraryPath, "atlas/config/settings.properties"))
        writeRecordingConfig()
    }

    /**
     * 把录屏参数导出为 tools/screen_recorder 脚本读取的 JSON（契约见 tools/screen_recorder/README.md）。
     * 保存目录为空时导出默认路径，让脚本侧始终拿到具体目录。
     */
    private fun writeRecordingConfig() {
        try {
            val defaultDir = File(System.getProperty("user.home"), "Videos/Screencasts").absolutePath
            val dir = settings.recordingSaveDir.ifBlank { defaultDir }
            val json = "{\"saveDir\": \"${jsonEscape(dir)}\", \"fps\": ${settings.recordingFps}, \"bitrate\": ${settings.recordingBitrate}}"
            File(configDir, "screen-recorder.json").writeText(json)
        } catch (e: Exception) {
            Log.e("导出录屏配置失败", e)
        }
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 打开/切换库：连接 DB → 载入知识文件 → 增量扫描 → 启动文件监听 */
    fun openLibrary(path: String, rescanIfNeeded: Boolean) {
        Log.i("openLibrary path=$path rescan=$rescanIfNeeded 线程=${Thread.currentThread().name}")
        settings = settings.copy(libraryPath = path)
        saveSettings()
        try {
            conn?.close()
        } catch (e: Exception) {
            Log.w("旧连接关闭异常（忽略）：${e.message}")
            scanMessage.value = "旧连接关闭异常（忽略）：${e.message}"
        }
        Log.timed("打开 SQLite db=${dbFile().absolutePath}", warnMs = 500) { conn = Indexer.connect(dbFile()) }
        indexer = Indexer(conn!!)
        libraryReady = true
        // 恢复上次选中的题库文档（换库后可能失效，reloadKnowledgeFiles 会回退到首篇映射文档）
        selectedSourcePathState.value = settings.selectedSourcePath.ifBlank { SourceQuestions.TARGET_PATH }
        Log.timed("载入知识文件与收件箱", warnMs = 500) {
            reloadKnowledgeFiles()
            scanInbox()
            refreshOutbox()
        }
        // 持久化恢复的路径可能已失效（换库/文档被删）：回退到扫描到的首篇文档，避免题库空白。
        // 只在启动时做——会话内允许选中未映射文档（界面有"暂未接入解析"提示），重载不能弹回。
        if (selectedSourcePath !in knowledgeDocuments && knowledgeDocuments.isNotEmpty()) {
            Log.i("持久化的题库文档已失效（${selectedSourcePath}），回退到 ${knowledgeDocuments.first()}")
            selectedSourcePath = knowledgeDocuments.first()
        }
        if (rescanIfNeeded) rescan(full = false)
        startWatching()
        Log.i("openLibrary 完成 library=$path")
    }

    fun rules() = settings.rules()

    fun rescan(full: Boolean) {
        val ix = indexer ?: return
        if (scanning.value) { Log.d("rescan 跳过：已有扫描进行中 full=$full"); return }
        scanning.value = true
        scanMessage.value = "扫描中…"
        Log.i("rescan 开始 full=$full（后台线程）")
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val st = ix.rescan(libraryRoot(), rules(), full)
                scanMessage.value = "条目 ${ix.itemCount()} · 区块 ${ix.chunkCount()}（本次新增区块 ${st.chunks}）"
                Log.i("rescan 完成 耗时=${System.currentTimeMillis() - t0}ms 新增区块=${st.chunks} 条目=${ix.itemCount()} 总区块=${ix.chunkCount()}")
                notes.clear(); notes.addAll(ix.allItems())
                Log.d("条目列表已刷新 共 ${notes.size} 条")
            } catch (e: Exception) {
                Log.e("rescan 失败（耗时 ${System.currentTimeMillis() - t0}ms）", e)
                scanMessage.value = "扫描失败：${e.message}"
            } finally {
                scanning.value = false
            }
        }
    }

    fun reloadKnowledgeFiles() {
        Log.timed("重载知识文件", warnMs = 300) {
            synchronized(reloadLock) {
                // 重载可能来自 UI 线程（编辑写回）也可能来自 IO 线程（文件监听），UI 随时在取帧。
                // 先把新数据全部解析进局部量，再用一个可变快照原子换入：读者只会看到换入前或
                // 换入后的完整状态。若让 UI 看到 clear→addAll 之间的空列表，题库 LazyColumn 的
                // 滚动位置会被钳回顶部（2026-09-24 用户录屏：保存题目后页面跳回顶部）。
                val newCards = MdStores.loadCards(cardsFile())
                val newQuestions = MdStores.loadQuestions(questionsFile())
                val documents = scanKnowledgeDocuments()
                val mappedDocuments = SourceQuestions.supportedDocuments(documents, settings.sourceQuestionPaths)
                val mappedReadmes = SourceQuestions.supportedReadmeDocuments(documents, settings.sourceQuestionPaths)
                val docContents = mappedDocuments.mapNotNull { path ->
                    val file = sourceDocumentFile(path)
                    if (file.isFile) path to file.readText(Charsets.UTF_8) else null
                }
                val newAllSourceQuestions = SourceQuestions.parseAll(docContents)
                val source = sourceQuestionFile()
                val sourceDocument = if (
                    source.isFile &&
                    selectedSourcePath in mappedDocuments &&
                    SourceQuestions.isSupportedPath(selectedSourcePath, settings.sourceQuestionPaths)
                ) source.readText(Charsets.UTF_8) else null
                val newSourceQuestions = sourceDocument
                    ?.let { SourceQuestions.parse(selectedSourcePath, it, settings.sourceQuestionPaths) }
                    .orEmpty()
                val newSourceSections = sourceDocument
                    ?.let { SourceQuestions.parseSections(selectedSourcePath, it, settings.sourceQuestionPaths) }
                    .orEmpty()
                val newReadmeContent = if (selectedSourcePath in mappedReadmes) {
                    sourceDocumentFile(selectedSourcePath).takeIf { it.isFile }?.readText(Charsets.UTF_8)
                } else null
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    cards.clear(); cards.addAll(newCards)
                    questions.clear(); questions.addAll(newQuestions)
                    knowledgeDocuments.clear(); knowledgeDocuments.addAll(documents)
                    sourceReadmeDocuments.clear(); sourceReadmeDocuments.addAll(mappedReadmes)
                    sourceReadmeContent = newReadmeContent
                    sourceQuestions.clear(); sourceQuestions.addAll(newSourceQuestions)
                    allSourceQuestions.clear(); allSourceQuestions.addAll(newAllSourceQuestions)
                    sourceSections.clear(); sourceSections.addAll(newSourceSections)
                    rebuildDueQueue()
                }
            }
            // 内容可能变了，异步重算"相对 git HEAD 的题目改动"标记（子进程慢，不能占 UI 线程）
            refreshSourceQuestionGitDiff()
        }
    }

    /** 比对任务代号：刷新期间又发生重载时，旧任务结果直接丢弃。 */
    private val gitDiffGeneration = AtomicInteger(0)

    /** 探测过「不是 git 仓库」的库根：签名轮询每 3s 一次，不能对这种根反复 spawn 子进程 */
    private val gitUnavailableRoots: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 逐题比对当前内容与 git HEAD 里的版本（按题面身份配对，不按题号配对）。除是否变化外，
     * 还携带题面变化字符区间与答案变化行号，供 UI 行内着色。
     * 返回 null 表示 git 不可用或当前库不在 git 仓库里，UI 不标色。
     */
    fun computeSourceQuestionGitDiffs(): Map<String, SourceQuestionGitDiff>? {
        val entriesByDoc = allSourceQuestions.toList().groupBy { it.sourcePath }
        if (entriesByDoc.isEmpty()) return emptyMap()
        val rootPath = libraryRoot().absolutePath
        if (rootPath in gitUnavailableRoots) return null
        val probe = runCatching {
            val proc = ProcessBuilder("git", "-C", rootPath, "rev-parse", "--git-dir")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            proc.waitFor()
        }.getOrNull() ?: return null
        if (probe != 0) {
            gitUnavailableRoots.add(rootPath)
            return null
        }
        val result = HashMap<String, SourceQuestionGitDiff>()
        for ((path, entries) in entriesByDoc) {
            val oldDoc = gitShowHeadContent(sourceDocumentFile(path))
            if (oldDoc == null) {
                // 文件不在 HEAD（新文件尚未提交过）：全部内容都算有未提交改动
                entries.forEach { entry ->
                    result[sourceQuestionGitKey(entry)] = SourceQuestionGitDiff(
                        changed = true,
                        questionRanges = listOf(0 until entry.question.length),
                        answerDirtyLines = entry.answer.lines().indices.toSet(),
                    )
                }
                continue
            }
            val oldEntries = SourceQuestions.parse(path, oldDoc, settings.sourceQuestionPaths)
            val oldByCurrentNumber = matchGitHeadQuestions(oldEntries, entries)
            entries.forEach { entry ->
                val old = oldByCurrentNumber[entry.number]
                result[sourceQuestionGitKey(entry)] = when {
                    old == null -> SourceQuestionGitDiff(
                        changed = true,
                        questionRanges = listOf(0 until entry.question.length),
                        answerDirtyLines = entry.answer.lines().indices.toSet(),
                    )
                    old.question == entry.question && old.answer == entry.answer -> SourceQuestionGitDiff(changed = false)
                    else -> SourceQuestionGitDiff(
                        changed = true,
                        questionRanges = TextDiff.changedRangesInNew(old.question, entry.question),
                        answerDirtyLines = TextDiff.changedLinesInNew(old.answer, entry.answer),
                    )
                }
            }
        }
        return result
    }

    /**
     * 按题面文本匹配当前题目与 HEAD 中的题目，避免插入或重排题目后题号整体偏移，
     * 把未修改的后续题目误标为改动。重复题面优先按答案精确匹配；题面改写时再用答案或剩余顺序辅助配对。
     */
    private fun matchGitHeadQuestions(
        oldEntries: List<SourceQuestions.Entry>,
        currentEntries: List<SourceQuestions.Entry>,
    ): Map<Int, SourceQuestions.Entry> {
        fun identity(question: String): String = question.trim().replace(Regex("\\s+"), " ")

        val matchesByCurrentNumber = HashMap<Int, SourceQuestions.Entry>()
        val usedOld = BooleanArray(oldEntries.size)
        val usedCurrent = BooleanArray(currentEntries.size)
        val oldIndicesByQuestion = oldEntries.indices.groupBy { identity(oldEntries[it].question) }
        val currentIndicesByQuestion = currentEntries.indices.groupBy { identity(currentEntries[it].question) }

        fun pair(currentIndex: Int, oldIndex: Int) {
            usedCurrent[currentIndex] = true
            usedOld[oldIndex] = true
            matchesByCurrentNumber[currentEntries[currentIndex].number] = oldEntries[oldIndex]
        }

        // 题面文本是主要身份；重复题面先用答案精确匹配，避免新副本抢走旧题身份。
        for ((questionIdentity, currentIndices) in currentIndicesByQuestion) {
            val oldIndices = oldIndicesByQuestion[questionIdentity].orEmpty()
            if (oldIndices.isEmpty()) continue

            val unmatchedCurrent = ArrayList<Int>()
            for (currentIndex in currentIndices) {
                val current = currentEntries[currentIndex]
                val exactAnswerIndex = oldIndices.firstOrNull { oldIndex ->
                    !usedOld[oldIndex] && oldEntries[oldIndex].answer == current.answer
                }
                if (exactAnswerIndex == null) {
                    unmatchedCurrent += currentIndex
                } else {
                    pair(currentIndex, exactAnswerIndex)
                }
            }

            // 题面相同但答案有改动时，只有组内数量相等才按顺序配对；数量不等时保留歧义项，
            // 避免把新增的重复题面误认成旧题编辑。
            val remainingOld = oldIndices.filterNot { usedOld[it] }
            if (unmatchedCurrent.size == remainingOld.size) {
                unmatchedCurrent.zip(remainingOld).forEach { (currentIndex, oldIndex) ->
                    pair(currentIndex, oldIndex)
                }
            }
        }

        // 题面改写但答案未变时，用答案作为辅助身份，避免把未改答案整段标黄。
        for (currentIndex in currentEntries.indices.filterNot { usedCurrent[it] }) {
            val current = currentEntries[currentIndex]
            val exactAnswerIndex = oldEntries.indices
                .filterNot { usedOld[it] }
                .filter { oldEntries[it].answer == current.answer }
                .minByOrNull { kotlin.math.abs(oldEntries[it].number - current.number) }
            if (exactAnswerIndex != null) pair(currentIndex, exactAnswerIndex)
        }

        // 若题面和答案都改写，但增删数相等，剩余区间按文档顺序配对。
        val remainingCurrent = currentEntries.indices.filterNot { usedCurrent[it] }
        val remainingOld = oldEntries.indices.filterNot { usedOld[it] }
        if (remainingCurrent.size == remainingOld.size) {
            remainingCurrent.zip(remainingOld).forEach { (currentIndex, oldIndex) ->
                pair(currentIndex, oldIndex)
            }
        }
        return matchesByCurrentNumber
    }

    /** 后台重算 git 改动标记并原子发布；重载后调用，保存与外部修改都会跟着刷新。 */
    fun refreshSourceQuestionGitDiff() {
        val generation = gitDiffGeneration.incrementAndGet()
        scope.launch {
            val result = computeSourceQuestionGitDiffs() ?: return@launch
            if (gitDiffGeneration.get() != generation) return@launch
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                sourceQuestionGitDiffs.clear()
                sourceQuestionGitDiffs.putAll(result)
            }
        }
    }

    private fun gitShowHeadContent(file: File): String? = runCatching {
        val proc = ProcessBuilder("git", "-C", file.parentFile.absolutePath, "show", "HEAD:./${file.name}")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val text = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        if (proc.waitFor() != 0) null else text
    }.getOrNull()

    fun selectSourceDocument(path: String) {
        if (path == selectedSourcePath) return
        selectedSourcePath = path
        Log.i("切换题库源文档 → $path")
        reloadKnowledgeFiles()
    }

    /** 将 README 草稿写回原文件；expectedContent 用于阻止覆盖编辑器外部的并发修改。 */
    fun saveReadme(path: String, expectedContent: String, updatedContent: String): ReadmeSaveResult {
        val normalized = path.replace('\\', '/').trim('/')
        val supportedReadmes = SourceQuestions.supportedReadmeDocuments(
            knowledgeDocuments.toList(),
            settings.sourceQuestionPaths,
        )
        if (path !in supportedReadmes && normalized !in supportedReadmes.map { it.replace('\\', '/').trim('/') }) {
            return ReadmeSaveResult.UNAVAILABLE
        }
        val result = runCatching {
            synchronized(reloadLock) {
                val file = sourceDocumentFile(path)
                if (!file.isFile) return@synchronized ReadmeSaveResult.UNAVAILABLE
                val actualContent = file.readText(Charsets.UTF_8)
                if (actualContent != expectedContent) {
                    ReadmeSaveResult.CONFLICT
                } else {
                    MdStores.atomicWrite(file, updatedContent)
                    ReadmeSaveResult.SAVED
                }
            }
        }.getOrElse { error ->
            Log.e("README 自动保存失败 path=$path", error)
            ReadmeSaveResult.FAILED
        }
        if (result != ReadmeSaveResult.UNAVAILABLE) reloadKnowledgeFiles()
        return result
    }

    fun renameKnowledgeNode(path: String, newName: String): Boolean {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank() || normalized == "knowledge-base") {
            showToast("知识库根目录不能重命名")
            return false
        }
        val oldFile = sourceDocumentFile(normalized)
        if (!oldFile.exists()) {
            showToast("找不到要重命名的文件或目录")
            return false
        }
        val safeName = atlas.core.KnowledgeTree.safeRenameName(newName, oldFile.isDirectory)
            ?: run {
                showToast("名称不能为空，且不能包含路径分隔符")
                return false
            }
        if (safeName == oldFile.name) return true
        val target = File(oldFile.parentFile, safeName)
        if (target.exists()) {
            showToast("目标名称已存在：$safeName")
            return false
        }
        if (!oldFile.renameTo(target)) {
            showToast("重命名失败，请检查文件权限")
            return false
        }
        val renamed = atlas.core.KnowledgeTree.renamedPath(normalized, safeName, oldFile.isDirectory)
        if (selectedSourcePath == normalized || selectedSourcePath.startsWith("$normalized/")) {
            selectedSourcePath = renamed + selectedSourcePath.removePrefix(normalized)
        }
        settings = settings.copy(
            sourceQuestionPaths = settings.sourceQuestionPaths.map { configured ->
                if (configured == normalized || configured.startsWith("$normalized/")) {
                    renamed + configured.removePrefix(normalized)
                } else configured
            },
        )
        saveSettings()
        reloadKnowledgeFiles()
        showToast("已重命名为：$safeName")
        return true
    }

    fun nextSourceQuestionNumber(path: String): Int {
        val file = sourceDocumentFile(path)
        if (!file.isFile) return 1
        val entries = SourceQuestions.parse(path, file.readText(Charsets.UTF_8), listOf(path))
        return (entries.maxOfOrNull { it.number } ?: 0) + 1
    }

    fun sourceQuestionNumbers(path: String): Set<Int> {
        val file = sourceDocumentFile(path)
        if (!file.isFile) return emptySet()
        return SourceQuestions.parse(path, file.readText(Charsets.UTF_8), listOf(path)).map { it.number }.toSet()
    }

    fun sourceQuestionTexts(path: String): Set<String> {
        val file = sourceDocumentFile(path)
        if (!file.isFile) return emptySet()
        return SourceQuestions.parse(path, file.readText(Charsets.UTF_8), listOf(path))
            .map { it.question.trim() }
            .toSet()
    }

    fun createSourceQuestions(path: String, drafts: List<SourceQuestions.Draft>): Boolean {
        if (drafts.isEmpty() || drafts.any { it.question.isBlank() }) {
            showToast("至少需要一道有效题目")
            return false
        }
        val file = sourceDocumentFile(path)
        if (!file.isFile) {
            showToast("找不到目标 Markdown 文档")
            return false
        }
        return runCatching {
            val document = file.readText(Charsets.UTF_8)
            MdStores.atomicWrite(file, SourceQuestions.append(document, drafts))
            selectedSourcePath = path
            if (!SourceQuestions.isSupportedPath(path, settings.sourceQuestionPaths)) {
                settings = settings.copy(sourceQuestionPaths = settings.sourceQuestionPaths + path)
                saveSettings()
            }
            reloadKnowledgeFiles()
            showToast("已新建 ${drafts.size} 道题目")
            true
        }.getOrElse { error ->
            Log.e("新建题目写回失败 path=$path", error)
            showToast("写入失败：${error.message}")
            false
        }
    }

    fun createSourceQuestionAt(path: String, draft: SourceQuestions.Draft, number: Int): Boolean {
        if (draft.question.isBlank() || number <= 0) {
            showToast("题目和插入序号不能为空")
            return false
        }
        val file = sourceDocumentFile(path)
        if (!file.isFile) {
            showToast("找不到目标 Markdown 文档")
            return false
        }
        return runCatching {
            val document = file.readText(Charsets.UTF_8)
            MdStores.atomicWrite(file, SourceQuestions.insertAtNumber(document, draft, number))
            selectedSourcePath = path
            if (!SourceQuestions.isSupportedPath(path, settings.sourceQuestionPaths)) {
                settings = settings.copy(sourceQuestionPaths = settings.sourceQuestionPaths + path)
                saveSettings()
            }
            reloadKnowledgeFiles()
            showToast("已在第 ${number.coerceAtMost(sourceQuestions.size)} 题位置插入")
            true
        }.getOrElse { error ->
            Log.e("插入题目写回失败 path=$path", error)
            showToast("插入失败：${error.message}")
            false
        }
    }

    private fun sourceDocumentFile(path: String): File {
        val root = libraryRoot()
        val normalized = path.replace('\\', '/').trimStart('/')
        val relative = if (root.name == "knowledge-base" && normalized.startsWith("knowledge-base/")) {
            normalized.removePrefix("knowledge-base/")
        } else normalized
        return File(root, relative)
    }

    private fun scanKnowledgeDocuments(): List<String> {
        val root = libraryRoot()
        val knowledgeRoot = if (root.name == "knowledge-base") root else File(root, "knowledge-base")
        if (!knowledgeRoot.isDirectory) return emptyList()
        return knowledgeRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .filterNot { file -> file.toPath().any { part -> part.toString() == ".git" || part.toString() == "atlas" } }
            .map { file ->
                val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                if (root.name == "knowledge-base") "knowledge-base/$relative" else relative
            }
            .sorted()
            .toList()
    }

    /** 只改写当前 Q 块；如果源文件已被外部修改，则拒绝覆盖并要求重新加载。 */
    fun saveSourceQuestion(
        entry: SourceQuestions.Entry,
        question: String,
        answer: String,
        status: QuestionStatus = QuestionStatus.DEFAULT,
        tags: List<String> = entry.tags,
    ): Boolean {
        val file = sourceQuestionFile()
        val current = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (current != entry.document) {
            Log.w("同源题目写回冲突：源文件已变化 file=${file.absolutePath}")
            reloadKnowledgeFiles()
            showToast("源文档已被外部修改，已重新加载")
            return false
        }
        MdStores.atomicWrite(file, SourceQuestions.replace(entry, question, answer, status, tags))
        reloadKnowledgeFiles()
        Log.i("同源题目写回成功 path=${entry.sourcePath} Q${entry.number}")
        return true
    }

    /** 在当前源文档中调整题目顺序；文档被外部修改时拒绝覆盖并重新加载。 */
    fun reorderSourceQuestions(entries: List<SourceQuestions.Entry>, fromIndex: Int, toIndex: Int): Boolean {
        if (entries.isEmpty() || selectedSourcePath != entries.first().sourcePath) return false
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return false
        val file = sourceQuestionFile()
        val current = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (current != entries.first().document || entries.any { it.document != current }) {
            reloadKnowledgeFiles()
            showToast("源文档已被外部修改，已重新加载")
            return false
        }
        val reordered = SourceQuestions.reorderEntries(current, entries, fromIndex, toIndex)
        if (reordered == null) {
            showToast("只能在连续题目之间调整顺序")
            return false
        }
        return runCatching {
            MdStores.atomicWrite(file, reordered)
            reloadKnowledgeFiles()
            showToast("题目顺序已保存")
            true
        }.onFailure { error ->
            Log.e("题目排序写回失败 path=$selectedSourcePath", error)
            reloadKnowledgeFiles()
            showToast("题目顺序保存失败：${error.message}")
        }.getOrElse { false }
    }

    /** 从源文档删除一道题目并让剩余题目连续重新编号；文档被外部修改时拒绝覆盖并重新加载。 */
    fun deleteSourceQuestion(entry: SourceQuestions.Entry): Boolean {
        val file = sourceDocumentFile(entry.sourcePath)
        val current = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (current != entry.document) {
            Log.w("同源题目删除冲突：源文件已变化 file=${file.absolutePath}")
            reloadKnowledgeFiles()
            showToast("源文档已被外部修改，已重新加载")
            return false
        }
        val updated = SourceQuestions.remove(current, entry)
        if (updated == null) {
            reloadKnowledgeFiles()
            showToast("题目已被外部修改，已重新加载")
            return false
        }
        return runCatching {
            MdStores.atomicWrite(file, updated)
            reloadKnowledgeFiles()
            Log.i("同源题目删除成功 path=${entry.sourcePath} Q${entry.number}")
            showToast("已删除 Q${entry.number}")
            true
        }.getOrElse { error ->
            Log.e("删除题目写回失败 path=${entry.sourcePath}", error)
            reloadKnowledgeFiles()
            showToast("删除失败：${error.message}")
            false
        }
    }

    /** 把一道题目移动到另一份映射文档：源文档整块移除，目标文档末尾追加并从最大题号之后编号。 */
    fun moveSourceQuestion(entry: SourceQuestions.Entry, targetPath: String): Boolean {
        val normalizedTarget = targetPath.replace('\\', '/').trim('/')
        if (normalizedTarget == entry.sourcePath.replace('\\', '/').trim('/')) {
            showToast("目标文档与题目所在文档相同")
            return false
        }
        if (!SourceQuestions.isSupportedPath(normalizedTarget, settings.sourceQuestionPaths)) {
            showToast("目标文档未纳入题库映射")
            return false
        }
        val sourceFile = sourceDocumentFile(entry.sourcePath)
        val targetFile = sourceDocumentFile(normalizedTarget)
        val current = if (sourceFile.isFile) sourceFile.readText(Charsets.UTF_8) else ""
        if (current != entry.document) {
            Log.w("同源题目移动冲突：源文件已变化 file=${sourceFile.absolutePath}")
            reloadKnowledgeFiles()
            showToast("源文档已被外部修改，已重新加载")
            return false
        }
        val updatedSource = SourceQuestions.remove(current, entry)
        if (updatedSource == null) {
            reloadKnowledgeFiles()
            showToast("题目已被外部修改，已重新加载")
            return false
        }
        val targetDocument = if (targetFile.isFile) targetFile.readText(Charsets.UTF_8) else ""
        val updatedTarget = SourceQuestions.append(
            targetDocument,
            listOf(SourceQuestions.Draft(entry.question, entry.answer, status = entry.status, tags = entry.tags)),
        )
        return runCatching {
            // 先写目标再写源：中途失败只会造成题目重复，不会丢题
            MdStores.atomicWrite(targetFile, updatedTarget)
            MdStores.atomicWrite(sourceFile, updatedSource)
            reloadKnowledgeFiles()
            Log.i("同源题目移动成功 ${entry.sourcePath} Q${entry.number} → $normalizedTarget")
            showToast("已移动到 $normalizedTarget")
            true
        }.getOrElse { error ->
            Log.e("移动题目写回失败 ${entry.sourcePath} → $normalizedTarget", error)
            reloadKnowledgeFiles()
            showToast("移动失败：${error.message}")
            false
        }
    }

    fun saveCards() { Log.d("保存 cards.md ${cards.size} 条"); MdStores.saveCards(cardsFile(), cards.toList()) }
    fun saveQuestions() { Log.d("保存 questions.md ${questions.size} 条"); MdStores.saveQuestions(questionsFile(), questions.toList()) }

    fun scanInbox() {
        Log.timed("收件箱扫描", warnMs = 300, logAlways = false) {
            candidates.clear(); candidates.addAll(Inbox.scan(inboxDir()))
        }
        Log.d("收件箱候选 ${candidates.size} 条")
    }

    /** outbox 任务状态（agent 标 done 后这里变化）；watch 循环每轮刷新，保证任务完成可见 */
    fun refreshOutbox() {
        outbox.clear(); outbox.addAll(OutboxTasks.scanOutbox(outboxDir()))
    }

    // ---------- 文件监听：第三方进程（agent/编辑器）改库后 ≤3s 自动重载 ----------
    fun startWatching() {
        if (!watching.getAndSet(true)) {
            // 基线必须在启动函数返回前建立。若把首次签名放进协程，调用方可能在协程
            // 第一次运行前写入文件，这次外部修改会被错误地当成初始状态而永久漏掉。
            val initialSignature = knowledgeSignature()
            watchJob = scope.launch {
                var lastSig = initialSignature
                while (isActive) {
                    delay(3000)
                    try {
                        val sig = knowledgeSignature()
                        refreshOutbox()
                        if (sig != lastSig) {
                            Log.i("文件监听：检测到知识文件变化，重载（≤3s 机制）")
                            lastSig = sig
                            Log.timed("文件监听触发的重载", warnMs = 500) {
                                reloadKnowledgeFiles()
                                scanInbox()
                            }
                        }
                    } catch (e: Exception) { Log.e("文件监听轮询异常（监听继续）", e) }
                }
            }
            Log.i("文件监听已启动（每 3s 比对签名）")
        }
    }

    private fun knowledgeSignature(): String {

        val files = listOf(cardsFile(), questionsFile(), sourceQuestionFile())
            .joinToString(";") { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
        val docs = scanKnowledgeDocuments().joinToString(";") { path ->
            val file = sourceDocumentFile(path)
            "$path:${file.lastModified()}:${file.length()}"
        }
        val inbox = inboxDir().listFiles()?.joinToString(";") { "${it.name}:${it.lastModified()}" } ?: ""
        // 把 HEAD 提交代号纳入签名：git commit 不改文件内容，但不纳入的话「未提交改动」
        // 标记要等到下次内容变化才会重算，用户提交后橙标迟迟不消失。
        // 非仓库的库根缓存探测结果，避免监听线程每 3s 空转一个子进程
        val rootPath = libraryRoot().absolutePath
        val gitHead = if (rootPath in gitUnavailableRoots) {
            "no-git"
        } else runCatching {
            val proc = ProcessBuilder("git", "-C", rootPath, "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            when {
                proc.waitFor() == 0 -> output.trim()
                output.contains("not a git repository") -> { gitUnavailableRoots.add(rootPath); "no-git" }
                else -> "no-git"
            }
        }.getOrDefault("no-git")
        return "$files|$docs|$inbox|$gitHead"
    }

    // ---------- 检索与上下文包 ----------
    fun search(q: String): List<Indexer.Hit> = Log.timed("search q=$q", warnMs = 300, logAlways = false) {
        indexer?.search(q) ?: emptyList()
    }

    fun contextPack(q: String): String {
        val pack = Log.timed("contextPack q=$q", warnMs = 300) {
            val ix = indexer
            if (ix == null) "" else {
                val chunks = ix.contextChunks(q)
                ix.logEvent("citation", q)
                val sb = StringBuilder()
                sb.append("## 问题\n").append(q).append("\n\n## 库内相关内容\n")
                chunks.forEachIndexed { i, c ->
                    sb.append("\n### [${i + 1}] ${c.path}##${c.section}\n").append(c.body).append("\n")
                }
                sb.toString()
            }
        }
        Log.i("上下文包已生成 q=$q 长度=${pack.length} 字符")
        return pack
    }

    fun requestPreview(relPath: String) { pendingPreview = relPath }

    // ---------- 出题 ----------
    fun addCardManual(front: String, back: String, deck: String, source: String) {
        val c = CardEntry(
            id = atlas.core.Md.md5(front + deck + System.currentTimeMillis()),
            front = front.trim(), back = back.trim(), deck = deck.ifBlank { "默认" },
            source = source.ifBlank { "手动" }, fsrsJson = FsrsEngine.newCardJson(), logs = emptyList(),
        )
        cards.add(c); saveCards(); rebuildDueQueue()
        Log.i("建卡 deck=${c.deck} front=${c.front.take(20)} source=${c.source}")
        showToast("已建卡：${c.front.take(20)}")
    }

    fun updateCard(c: CardEntry) {
        val i = cards.indexOfFirst { it.id == c.id }
        if (i >= 0) { cards[i] = c; saveCards(); rebuildDueQueue(); Log.i("更新卡 id=${c.id} front=${c.front.take(20)}") }
    }

    fun deleteCard(id: String) {
        cards.removeAll { it.id == id }; saveCards(); rebuildDueQueue()
        Log.i("删除卡 id=$id 剩余=${cards.size}")
        showToast("已删除卡片")
    }

    fun suspendCard(id: String, on: Boolean) {
        val i = cards.indexOfFirst { it.id == id }
        if (i >= 0) { cards[i] = cards[i].copy(suspended = on); saveCards(); rebuildDueQueue(); Log.i("${if (on) "暂停" else "恢复"}卡 id=$id") }
    }

    fun confirmCardCandidate(c: Inbox.Candidate) {
        addCardManual(front = c.s("front"), back = c.s("back"), deck = c.s("deck"), source = c.s("source").ifBlank { "收件箱" })
        Inbox.removeBlock(c); scanInbox()
    }

    fun confirmQuestionCandidate(c: Inbox.Candidate) {
        addQuestionsFromText(c.s("q"), c.s("ref"), c.s("source").ifBlank { "收件箱" }, c.s("answer"), c.s("tags"))
        Inbox.removeBlock(c); scanInbox()
    }

    /** 待确认内容统一进入题库；兼容旧协议中的 card(front/back) 与 question(ref)。 */
    fun confirmCandidateAsQuestion(c: Inbox.Candidate) {
        val question = c.s("q").ifBlank { c.s("front") }.ifBlank { c.s("title") }
        val answer = c.s("answer").ifBlank { c.s("back") }.ifBlank { c.s("ref") }
        addQuestionsFromText(
            question,
            ref = c.s("ref"),
            source = c.s("source").ifBlank { "收件箱" },
            answer = answer,
            tags = c.s("tags"),
        )
        Inbox.removeBlock(c); scanInbox()
    }

    // ---------- 复习 ----------
    fun rebuildDueQueue() {
        Log.timed("重建复习队列", warnMs = 200) {
            val now = Instant.now()
            val pool = cards.filter { c ->
                !c.suspended && (reviewDeckFilter == "全部" || c.deck == reviewDeckFilter) && FsrsEngine.isDue(c.fsrsJson, now)
            }.sortedBy { FsrsEngine.dueEpochMs(it.fsrsJson) }
            dueQueue.clear(); dueQueue.addAll(pool)
            reviewIdx = if (pool.isEmpty()) -1 else 0
            showingBack = false
            sessionGrades.clear(); sessionStart = 0L
            // 到期事件入日志（按卡去重计数，作为完成率分母；PRD 北极星 1）
            pool.forEach { c -> indexer?.logEvent("review_due", c.id) }
            Log.i("复习队列重建 卡组=${reviewDeckFilter} 到期=${pool.size}/${cards.size} 张（逐卡写到期事件 ${pool.size} 条 INSERT，都在 UI 线程）")
        }
    }

    fun currentCard(): CardEntry? = dueQueue.getOrNull(reviewIdx)

    /** 仅浏览复习队列，不评分、不写回 FSRS、不移除卡片。 */
    fun previousReviewCard(): Boolean = moveReview(-1)

    fun nextReviewCard(): Boolean = moveReview(1)

    private fun moveReview(delta: Int): Boolean {
        if (dueQueue.isEmpty()) return false
        val next = (reviewIdx + delta).coerceIn(0, dueQueue.lastIndex)
        if (next == reviewIdx) return false
        reviewIdx = next
        showingBack = false
        return true
    }

    // 上次评分快照（撤销用）
    private var lastUndo: Triple<String, String, Int>? = null // cardId, prevJson, prevIdx

    fun grade(grade: FsrsEngine.Grade) {
        val c = currentCard() ?: return
        lastUndo = Triple(c.id, c.fsrsJson, reviewIdx)
        val r = FsrsEngine.review(c.fsrsJson, grade)
        val idx = cards.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            cards[idx] = c.copy(fsrsJson = r.newJson, logs = c.logs + "评分 ${grade.name} ${MdStores.now()}")
            saveCards()
            indexer?.logEvent("review", "${grade.name}|${c.id}")
        }
        sessionGrades.add(grade.name)
        if (sessionStart == 0L) sessionStart = System.currentTimeMillis()
        showingBack = false
        // 评分后移队列：AGAIN 当日重现（先写回新 FSRS 状态再移尾，保证同会话复评基于新状态）
        if (grade == FsrsEngine.Grade.AGAIN) {
            if (reviewIdx < dueQueue.size) dueQueue[reviewIdx] = cards.getOrElse(idx) { c }
            dueQueue.add(dueQueue.removeAt(reviewIdx))
        } else {
            dueQueue.removeAt(reviewIdx)
            if (reviewIdx >= dueQueue.size) reviewIdx = dueQueue.size - 1
        }
        if (reviewIdx < 0 && dueQueue.isNotEmpty()) reviewIdx = 0
        Log.i("评分 card=${c.front.take(16)} grade=$grade 下次到期=${java.time.Instant.ofEpochMilli(r.dueEpochMs)} 队列剩余=${dueQueue.size}")
    }

    data class SessionSummary(val count: Int, val dist: Map<String, Int>, val minutes: Long)

    /** 撤销上次评分：恢复 FSRS 状态并重建队列（卡片因再次到期回到队首） */
    fun undoLastGrade() {
        val (id, json, _) = lastUndo ?: run { showToast("没有可撤销的评分"); return }
        val i = cards.indexOfFirst { it.id == id }
        if (i >= 0) {
            cards[i] = cards[i].copy(fsrsJson = json, logs = cards[i].logs.dropLast(1))
            saveCards()
        }
        if (sessionGrades.isNotEmpty()) sessionGrades.removeAt(sessionGrades.size - 1)
        lastUndo = null
        rebuildDueQueue()
        Log.i("撤销评分 card=$id")
        showToast("已撤销上次评分")
    }

    fun sessionSummary(): SessionSummary {
        val start = if (sessionStart == 0L) System.currentTimeMillis() else sessionStart
        return SessionSummary(sessionGrades.size, sessionGrades.groupingBy { it }.eachCount(), (System.currentTimeMillis() - start) / 60000)
    }

    fun dueCount(deck: String): Int = Log.timed("dueCount deck=$deck", warnMs = 100, logAlways = false) {
        val now = Instant.now()
        cards.count { !it.suspended && (deck == "全部" || it.deck == deck) && FsrsEngine.isDue(it.fsrsJson, now) }
    }
    fun deckNames(): List<String> = cards.map { it.deck }.distinct().sorted()

    data class DeckStat(val deck: String, val total: Int, val due: Int, val learning: Int, val suspended: Int)

    fun deckStats(): List<DeckStat> = Log.timed("deckStats", warnMs = 100, logAlways = false) {
        val now = Instant.now()
        cards.groupBy { it.deck }.map { (deck, cs) ->
            val live = cs.filter { !it.suspended }
            DeckStat(
                deck = deck, total = cs.size,
                due = live.count { FsrsEngine.isDue(it.fsrsJson, now) },
                learning = live.count { FsrsEngine.isLearning(it.fsrsJson) },
                suspended = cs.count { it.suspended },
            )
        }.sortedBy { it.deck }
    }

    // ---------- 题库与模拟面试 ----------
    fun addQuestionsFromText(text: String, ref: String, source: String = "粘贴导入", answer: String = "", tags: String = "") {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var added = 0
        lines.forEach { l ->
            val q = l.replace(Regex("^[0-9]+[.、)\\s]+"), "").trim()
            if (q.length < 4) return@forEach
            questions.add(QuestionEntry(
                id = atlas.core.Md.md5(q + System.currentTimeMillis() + added), q = q,
                ref = ref, status = "未测", logs = emptyList(), source = source, answer = answer.trim(),
                tags = atlas.core.QuestionTags.normalize(tags.split(',', '，')),
            ))
            added++
        }
        saveQuestions()
        Log.i("导入题目 $added/${lines.size} 道 source=$source ref=${ref.take(30)} answer=${answer.length}字")
        showToast("题目已入库 $added 道")
    }

    fun updateQuestion(q: QuestionEntry) {
        val i = questions.indexOfFirst { it.id == q.id }
        if (i >= 0) { questions[i] = q; saveQuestions(); Log.i("更新题目 id=${q.id} status=${q.status} q=${q.q.take(20)}") }
    }

    /** 题目 → 闪卡（题库是信息源：答案派生卡背，进 FSRS 复习队列防遗忘） */
    fun questionToCard(q: QuestionEntry) {
        val back = q.answer.ifBlank { q.ref }.ifBlank { "（无答案，先在编辑里补全）" }
        addCardManual(front = q.q, back = back, deck = q.source.ifBlank { "题库" }, source = "题库:${q.status}")
        showToast("已转闪卡，进「${q.source.ifBlank { "题库" }}」卡组复习")
    }

    fun deleteQuestion(id: String) {
        questions.removeAll { it.id == id }; saveQuestions(); Log.i("删除题目 id=$id 剩余=${questions.size}"); showToast("已删除题目")
    }

    fun startInterview(list: List<QuestionEntry>) {
        if (list.isEmpty()) return
        val task = OutboxTasks.writeInterview(outboxDir(), list)
        Log.i("发起模拟面试 ${list.size} 题 outbox=${task.name}")
        refreshOutbox()
        showToast("已发起 AI 模拟面试（${list.size} 题）：复制任务文件给 agent，完成后回题目页确认")
    }

    // ---------- agent 任务动作（outbox） ----------
    fun actionCardgen(source: String, instruction: String) {
        val f = OutboxTasks.writeCardgen(outboxDir(), source, instruction)
        Log.i("outbox 出题任务 source=$source file=${f.name}")
        refreshOutbox()
        showToast("已发起出题任务：复制任务文件给 agent")
    }


    // ---------- 经验流（FR-A8） ----------
    fun retroCandidates(): List<NoteFile> = notes.filter {
        it.marker == DocMarker.RETROSPECTIVE || it.marker == DocMarker.PROJECT_EXPERIENCE
    }

    /** 从复盘/项目经验条目提取候选主题：未竟/暴露/不懂/待办/改进/遗留 小节的列表行 */
    private fun extractRetroTopics(text: String): List<String> {
        val keys = listOf("未竟", "暴露", "不懂", "待办", "改进", "遗留", "问题")
        val out = ArrayList<String>()
        var inSection = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                inSection = keys.any { line.removePrefix("## ").contains(it) }
                continue
            }
            if (inSection && (line.startsWith("- ") || line.startsWith("* "))) {
                val t = line.removePrefix("- ").removePrefix("* ").trim()
                if (t.length >= 4) out.add(t)
            }
        }
        return out.distinct().take(10)
    }



    fun proposeFromRetro(note: NoteFile, asCards: Boolean) {
        if (!asCards) return
        val text = indexer?.readFile(note.relPath, libraryRoot()) ?: run {
            Log.w("复盘提议失败：读取条目失败 ${note.relPath}")
            showToast("读取条目失败：${note.relPath}"); return
        }
        val topics = extractRetroTopics(text)
        if (asCards) OutboxTasks.writeCardgen(outboxDir(), note.relPath, "请通读该复盘，把关键结论与教训整理成 3–8 道题目候选，每题只写题目和答案")
        Log.i("复盘提议 note=${note.relPath} 提取主题=${topics.size}个 出题=$asCards")
        refreshOutbox()
        showToast("已发起：让 AI 通读复盘提议题目，确认候选会在待确认区出现")
    }
    // ---------- 指标（FR-F2 北极星） ----------

    data class Metrics(val review30: Double, val citations: Int, val dueNow: Int)

    fun metrics(): Metrics = Log.timed("metrics()", warnMs = 300, logAlways = false) {
        val ix = indexer ?: return@timed Metrics(0.0, 0, 0)
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        var reviewed = 0; var citations = 0
        val c = conn ?: return@timed Metrics(0.0, 0, dueCount("全部"))
        c.prepareStatement("SELECT COUNT(*) FROM events WHERE ts>? AND kind='review'").use { ps ->
            ps.setLong(1, monthAgo)
            ps.executeQuery().use { r -> if (r.next()) reviewed = r.getInt(1) }
        }
        c.prepareStatement("SELECT COUNT(*) FROM events WHERE kind='citation'").use { ps ->
            ps.executeQuery().use { r -> if (r.next()) citations = r.getInt(1) }
        }
        Metrics(reviewed.toDouble(), citations, dueCount("全部"))
    }

    data class Metrics2(
        val reviewed30: Int, val dueEvents30: Int, val completionRate: Double,
        val citations: Int, val dueNow: Int,
        val daily: List<Pair<String, Int>>,
    )

    fun metrics2(): Metrics2 = Log.timed("metrics2()（5 条统计 SQL）", warnMs = 300, logAlways = false) {
        val c = conn ?: return@timed Metrics2(0, 0, 0.0, 0, dueCount("全部"), emptyList())
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        var reviewed = 0; var dueEvents = 0; var citations = 0
        val perDay = HashMap<String, Int>()
        c.prepareStatement("SELECT COUNT(*) FROM events WHERE ts>? AND kind='review'").use { ps ->
            ps.setLong(1, monthAgo); ps.executeQuery().use { r -> if (r.next()) reviewed = r.getInt(1) }
        }
        c.prepareStatement("SELECT COUNT(DISTINCT data) FROM events WHERE ts>? AND kind='review_due'").use { ps ->
            ps.setLong(1, monthAgo); ps.executeQuery().use { r -> if (r.next()) dueEvents = r.getInt(1) }
        }
        c.prepareStatement("SELECT COUNT(*) FROM events WHERE kind='citation'").use { ps ->
            ps.executeQuery().use { r -> if (r.next()) citations = r.getInt(1) }
        }
        c.prepareStatement(
            "SELECT date(ts/1000,'unixepoch','localtime') d, COUNT(*) n FROM events WHERE ts>? AND kind='review' GROUP BY d"
        ).use { ps ->
            ps.setLong(1, monthAgo)
            ps.executeQuery().use { r -> while (r.next()) perDay[r.getString(1)] = r.getInt(2) }
        }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val daily = (29 downTo 0).map { off ->
            val k = LocalDate.now().minusDays(off.toLong()).format(fmt)
            k to (perDay[k] ?: 0)
        }
        Metrics2(
            reviewed30 = reviewed, dueEvents30 = dueEvents,
            completionRate = if (dueEvents == 0) 0.0 else reviewed.toDouble() / dueEvents,
            citations = citations, dueNow = dueCount("全部"), daily = daily,
        )
    }
}
