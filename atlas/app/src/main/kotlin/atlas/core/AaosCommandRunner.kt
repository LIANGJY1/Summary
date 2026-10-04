package atlas.core

import androidx.compose.runtime.mutableStateOf
import atlas.platform.Platform
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 一次一条非交互式命令；持续排空输出、限制展示行数，停止时一并结束子进程。 */
class AaosCommandRunner(private val scope: CoroutineScope) {
    data class RunState(
        val command: String = "",
        val running: Boolean = false,
        val lines: List<String> = emptyList(),
        val exitCode: Int? = null,
    )

    val state = mutableStateOf(RunState())
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    private var process: Process? = null

    /** 返回 null 表示已开始；否则返回可以直接展示的输入/环境错误。 */
    fun start(
        command: String,
        serial: String?,
        adbPath: String?,
    ): String? {
        if (command.isBlank()) return "请先输入命令或选择预设"
        if (serial.isNullOrBlank() && !command.trim().startsWith("adb devices")) return "请先选择在线设备"
        if (adbPath.isNullOrBlank()) return "未找到 adb，请检查 Android SDK platform-tools 配置"
        val workDir = File(System.getProperty("user.home"))

        synchronized(lock) {
            if (state.value.running) return "已有 AAOS 命令正在运行"
            val runId = ++generation
            state.value = RunState(command = command, running = true)
            job = scope.launch(start = CoroutineStart.LAZY) {
                execute(runId, workDir, command, serial, adbPath)
            }.also { it.start() }
        }
        return null
    }

    fun stop() {
        val active = synchronized(lock) {
            if (!state.value.running) return
            generation++
            job?.cancel()
            job = null
            val current = process
            process = null
            state.value = state.value.copy(
                running = false,
                exitCode = -2,
                lines = (state.value.lines + "已停止").takeLast(MAX_LINES),
            )
            current
        }
        active?.let(::killProcessTree)
    }

    private fun execute(
        runId: Long,
        workDir: File,
        command: String,
        serial: String?,
        adbPath: String,
    ) {
        var current: Process? = null
        try {
            val builder = ProcessBuilder("bash", "--noprofile", "--norc", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
            val env = builder.environment()
            env.remove("ANDROID_SERIAL")
            env.putAll(AaosDebug.commandEnvironment(serial, adbPath, env["PATH"].orEmpty()))
            current = builder.start()
            synchronized(lock) {
                if (runId != generation) {
                    killProcessTree(current)
                    return
                }
                process = current
            }

            val tail = ArrayDeque<String>()
            var lastPublish = 0L
            current.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    tail.addLast(line.take(MAX_LINE_CHARS))
                    if (tail.size > MAX_LINES) tail.removeFirst()
                    val now = System.nanoTime()
                    if (now - lastPublish >= PUBLISH_INTERVAL_NANOS) {
                        publish(runId, tail.toList())
                        lastPublish = now
                    }
                }
            }
            val exit = current.waitFor()
            publish(runId, tail.toList(), exit)
        } catch (error: Exception) {
            publish(runId, listOf("命令执行失败：${error.message ?: error.javaClass.simpleName}"), -1)
        } finally {
            synchronized(lock) {
                if (process === current) process = null
                if (runId == generation) job = null
            }
            if (current?.isAlive == true) killProcessTree(current)
        }
    }

    private fun publish(runId: Long, lines: List<String>, exitCode: Int? = null) {
        synchronized(lock) {
            if (runId != generation) return
            state.value = state.value.copy(
                lines = lines,
                running = exitCode == null,
                exitCode = exitCode,
            )
        }
    }

    private fun killProcessTree(process: Process) {
        // 进程树终结是平台能力（桌面 ProcessHandle / Android 只能尽力杀根进程）
        Platform.killProcessTree(process)
    }

    private companion object {
        const val MAX_LINES = 400
        const val MAX_LINE_CHARS = 2_000
        const val PUBLISH_INTERVAL_NANOS = 100_000_000L
    }
}
