package atlas.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * 轻量文件日志（零依赖）：<数据目录>/logs/atlas-YYYY-MM-DD.log，按天轮换，默认保留 14 天。
 *
 * 排障三板斧：
 *  1. [timed]  —— 包住任意耗时操作：超阈值记 WARN（SLOW 行），异常带耗时记 ERROR；
 *                 热路径（重组期间反复调用的查询）传 logAlways=false，只在变慢时留痕。
 *  2. [uiOp]   —— 包住 UI 线程点击操作：记录「UI 最后动作」，配合看门狗还原卡死现场。
 *  3. 看门狗   —— 后台线程每 0.5s 往 AWT EDT 投喂心跳；EDT 无响应 ≥2s 开始告警并每 5s 追加，
 *                 恢复后汇总本次卡顿总时长。**应用卡死时先搜 log 里的「UI 线程已无响应」，
 *                 再看它上面最近的一行 UI 动作，即为卡死时正在执行的操作。**
 *
 * 未调用 [init] 前所有日志静默丢弃（单测环境零副作用）；写入走单写线程，永不阻塞调用方。
 */
object Log {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private const val KEEP_DAYS = 14

    @Volatile private var dir: File? = null
    @Volatile private var minLevel: Level = Level.DEBUG

    private val exec by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-log").apply { isDaemon = true } }
    }

    fun init(configDir: File, level: Level = Level.DEBUG) {
        minLevel = level
        val d = File(configDir, "logs").apply { mkdirs() }
        dir = d
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        var removed = 0
        d.listFiles { f -> f.isFile && f.name.startsWith("atlas-") && f.name.endsWith(".log") }?.forEach {
            if (it.lastModified() < cutoff && it.delete()) removed++
        }
        i("=== Atlas 启动 === 日志目录=${d.absolutePath}（保留 ${KEEP_DAYS} 天，本次清理旧日志 $removed 个）")
        startWatchdog()
    }

    fun d(msg: String) = log(Level.DEBUG, msg)
    fun i(msg: String) = log(Level.INFO, msg)

    fun w(msg: String, t: Throwable? = null) {
        log(Level.WARN, msg + (t?.let { " | ${it.javaClass.name}: ${it.message}" } ?: ""))
        t?.stackTrace?.take(8)?.forEach { f -> log(Level.WARN, "    at $f") }
    }

    fun e(msg: String, t: Throwable? = null) {
        log(Level.ERROR, msg + (t?.let { " | ${it.javaClass.name}: ${it.message}" } ?: ""))
        t?.stackTrace?.take(12)?.forEach { f -> log(Level.ERROR, "    at $f") }
        t?.cause?.let { c -> log(Level.ERROR, "  caused by ${c.javaClass.name}: ${c.message}") }
    }

    fun log(level: Level, msg: String) {
        val d = dir ?: return
        if (level.ordinal < minLevel.ordinal) return
        val line = "${LocalDateTime.now().format(TS)} ${level.name.take(1)} [${Thread.currentThread().name}] $msg"
        try {
            exec.execute { append(d, line) }
        } catch (_: Exception) { /* 日志自身绝不影响业务 */ }
    }

    private fun append(dir: File, line: String) {
        try {
            File(dir, "atlas-${LocalDateTime.now().format(DAY)}.log").appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("atlas-log 写入失败: ${e.message}")
        }
    }

    /** 计时包装：≥warnMs 记 WARN，否则 logAlways=true 时记 DEBUG；异常带耗时记 ERROR 后原样抛出。 */
    fun <T> timed(what: String, warnMs: Long = 300, logAlways: Boolean = true, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            val r = block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms >= warnMs) w("SLOW $what 耗时 ${ms}ms（阈值 ${warnMs}ms）")
            else if (logAlways) d("$what 耗时 ${ms}ms")
            return r
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            e("$what 异常（耗时 ${ms}ms）", t)
            throw t
        }
    }

    // ---------- UI 线程卡顿定位 ----------

    @Volatile private var lastUiOp: String = "(尚无 UI 动作)"

    /** UI 线程操作包装：更新「最后动作」面包屑 + 超阈值告警。点击回调里包住重活即可。 */
    fun <T> uiOp(what: String, warnMs: Long = 200, block: () -> T): T {
        lastUiOp = what
        val t0 = System.nanoTime()
        try {
            val r = block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms >= warnMs) w("UI 慢操作 $what 耗时 ${ms}ms（阈值 ${warnMs}ms）——点击卡顿时重点查这类行")
            else d("UI $what 耗时 ${ms}ms")
            return r
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            e("UI $what 异常（耗时 ${ms}ms）", t)
            throw t
        }
    }

    /** 只记录「UI 最后动作」不计时（用于无法用 lambda 包住的场景）。 */
    fun uiMark(what: String) { lastUiOp = what }

    private val edtTick = AtomicLong(0)

    private fun startWatchdog() {
        Thread {
            var lastSeen = -1L
            var stallStart = 0L
            var lastReport = 0L
            while (true) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                try { SwingUtilities.invokeLater { edtTick.incrementAndGet() } } catch (_: Exception) { return@Thread }
                val now = edtTick.get()
                if (now != lastSeen) {
                    if (stallStart > 0) {
                        val total = System.currentTimeMillis() - stallStart
                        if (total >= 1000) w("UI 线程恢复响应：本次卡顿约 ${total}ms ｜ 卡顿时最后动作: $lastUiOp")
                        stallStart = 0; lastReport = 0
                    }
                    lastSeen = now
                } else {
                    if (stallStart == 0L) stallStart = System.currentTimeMillis()
                    val stalledFor = System.currentTimeMillis() - stallStart
                    if (stalledFor >= 2000 && stalledFor - lastReport >= 5000) {
                        w("UI 线程已无响应 ${(stalledFor + 500) / 1000.0}s 且持续中 ｜ 最后动作: $lastUiOp ｜ 界面卡死时：查本行之前最近的 UI/耗时日志行即为元凶")
                        lastReport = stalledFor
                    }
                }
            }
        }.apply { isDaemon = true; name = "atlas-edt-watchdog" }.start()
        i("EDT 看门狗已启动（0.5s 心跳，无响应 ≥2s 告警）")
    }
}
