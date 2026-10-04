package atlas.core

import atlas.platform.Platform
import java.io.File
import kotlin.math.roundToInt

/** 首次启动且尚未保存过路径时使用的默认知识库。 */
const val DEFAULT_LIBRARY_PATH = "/home/liang/Project/MyProject/Summary/knowledge-base"

/**
 * 三档隐私边界（PRD FR-A1）：
 *  - IGNORED：完全忽略（.git/node_modules/构建产物/app 协作目录等）
 *  - LOCAL_ONLY：仅本地编目——内容不进 FTS、不进上下文包（工作敏感仓库）
 *  - FULL：全索引
 */
class IgnoreRules(
    val ignoredDirs: List<String>,
    val localOnlyDirs: List<String>,
    val retroDirs: List<String> = emptyList(),
) {
    companion object {
        /** 默认完全忽略的目录名（任一层级命中即忽略） */
        val DEFAULT_IGNORED = listOf(
            ".git", ".idea", ".gradle", ".qoder", ".sisyphus", ".trae", ".vscode",
            "node_modules", "__pycache__", "build", "dist", "out", ".venv", "venv",
            "atlas", // 应用协作目录（<库根>/atlas）
        )

        /** 默认复盘/项目经验目录名（任一层级命中，PRD FR-A8 目录名识别） */
        val DEFAULT_RETRO_DIRS = listOf("issue", "retros", "retrospective", "project-experience")
    }

    fun effectiveRetroDirs(): List<String> = retroDirs.ifEmpty { DEFAULT_RETRO_DIRS }

    fun tierOf(relPath: String): Tier {
        val norm = relPath.replace('\\', '/').trim('/')
        val parts = norm.split('/')
        val dirs = parts.dropLast(1)
        if (dirs.any { it in ignoredDirs }) return Tier.IGNORED
        val local = localOnlyDirs.any { p ->
            val ps = p.trim('/').replace('\\', '/')
            ps.isNotEmpty() && (norm.startsWith("$ps/") || dirs.any { it == ps })
        }
        return if (local) Tier.LOCAL_ONLY else Tier.FULL
    }
}

data class AppSettings(
    val libraryPath: String = DEFAULT_LIBRARY_PATH,
    /** 支持解析为题目的源文档；路径以知识库根目录为基准，目录规则以 / 结尾。 */
    val sourceQuestionPaths: List<String> = SourceQuestions.DEFAULT_SUPPORTED_PATHS,
    val ignoredExtra: List<String> = emptyList(),
    val localOnlyExtra: List<String> = emptyList(),
    val retroDirs: List<String> = emptyList(),
    val theme: String = "light", // light | dark
    /** 主题名：内置名（见 ui.AtlasThemes）或 customThemes 里的自定义主题名。 */
    val themeName: String = "",
    /** 用户自建主题，逐条 `v2|名称|浅色20项|深色20项`，多条之间用 `;` 分隔。 */
    val customThemes: List<String> = emptyList(),
    /** 全局文字大小基准（sp，11–20）：界面按 14sp=100% 换算成 Density.fontScale 应用，替代旧的百分比 fontScale。 */
    val globalFontSize: Int = 14,
    /** 题库题面字号（sp，10–18）：工作台题面为基准值，同源题库标题按 15/13 比例跟随。 */
    val questionFontSize: Int = 13,
    /** 题库内容字号（sp，10–24）：答案/闪卡/预览 markdown 正文的基准，标题与代码按比例跟随。 */
    val contentFontSize: Int = 14,
    /** 内容行间距（%，100–220）：140% 为内置默认行距，作用于答案正文、列表与代码块。 */
    val contentLineHeight: Int = 140,
    /** 同源题库卡片上 #标签名 与添加入口的字号（sp）。 */
    val questionTagFontSize: Int = 11,
    /** 题库答案区域单击时是否打开编辑弹窗；关闭后仍可划词，编辑按钮不受影响。 */
    val clickAnswerToEdit: Boolean = true,
    /** 屏幕录制（tools/screen_recorder）参数：保存目录（空=默认 ~/Videos/Screencasts）、帧率、码率 kbps。 */
    val recordingSaveDir: String = "",
    val recordingFps: Int = 15,
    val recordingBitrate: Int = 4000,
    /** 题库上次选中的源文档路径（重启恢复）；空 = 未记录，用默认文档。 */
    val selectedSourcePath: String = "",
    /** 任务自动化（工具页）：连接模式 auto（无线优先、USB 兜底）|wireless|usb，与无线模式的 IP、端口。PIN 存独立 0600 文件，不在此。 */
    val feishuMode: String = "auto",
    val feishuIp: String = "",
    val feishuPort: Int = 5555,
    /** 设备工具箱（工具页）：推送 APK 的本机路径、设备截图保存目录、模拟器 AVD 名。 */
    val pushApkPath: String = "",
    val screenshotSaveDir: String = "",
    val emulatorAvd: String = "3DAA",
) {
    val darkTheme: Boolean get() = theme == "dark"

    fun rules(): IgnoreRules = IgnoreRules(
        ignoredDirs = IgnoreRules.DEFAULT_IGNORED + ignoredExtra,
        localOnlyDirs = localOnlyExtra,
        retroDirs = retroDirs,
    )
}

/**
 * 配置持久化（Properties，双层，PRD §6.4.23）。
 *  - 本机层 [file]：只存「必然跨设备不同」的键（[localKeys]）——知识库路径（引导键，各机绝对路径不同）、
 *    本机文件路径（题库选中文档/推送 APK/截图/录制目录）、局域网手机 IP、本机 AVD 名。
 *  - 仓库同步层 syncedFile（`<库根>/atlas/config/settings.properties`）：其余全部用户配置，随知识库所在
 *    git 仓库同步（`<库根>/atlas` 本就是索引忽略名单里的应用协作目录），多设备经 git pull 保持一致。
 *
 * 兼容与迁移：syncedFile 传 null 时保持旧行为（本机文件全量读写）——既有调用与「仓库层尚未落盘」的
 * 老配置原地可用，首次非空保存即自动分流成两层。写入用固定键序、无时间戳注释（java.util.Properties
 * 的 store 会带当天日期，每次保存都会弄脏 git diff）。PIN 是凭据，照旧独立 0600 文件
 * （FeishuCheckin.pinFile），绝不入仓库（脱敏底线）。
 */
class SettingsStore(private val file: File) {

    /** 只留在本机层的键；其余键全部进仓库同步层 */
    private val localKeys = setOf(
        "libraryPath", "selectedSourcePath", "recordingSaveDir",
        "pushApkPath", "screenshotSaveDir", "feishuIp", "emulatorAvd",
    )

    fun load(): AppSettings = load(null)

    /** [syncedFileFor] 以本机层解析出的 libraryPath 定位仓库同步层；文件不存在时仅读本机层 */
    fun load(syncedFileFor: ((libraryPath: String) -> File?)?): AppSettings {
        val p = readProperties(file)
        if (syncedFileFor != null) {
            val libraryPath = p.getProperty("libraryPath")?.trim().orEmpty().ifBlank { Platform.defaultLibraryPath }
            val synced = syncedFileFor(libraryPath)
            if (synced != null && synced.isFile) {
                val sp = readProperties(synced)
                sp.stringPropertyNames().filter { it !in localKeys }.forEach { k -> p.setProperty(k, sp.getProperty(k)) }
                Log.d("设置叠加仓库同步层 ${synced.absolutePath}")
            }
        }
        fun list(k: String) = (p.getProperty(k) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val s = AppSettings(
            // 旧版本可能已经写入空路径；空路径不应让用户每次重新选择知识库。
            libraryPath = p.getProperty("libraryPath")?.trim().orEmpty().ifBlank { Platform.defaultLibraryPath },
            sourceQuestionPaths = list("sourceQuestionPaths").ifEmpty { SourceQuestions.DEFAULT_SUPPORTED_PATHS },
            ignoredExtra = list("ignoredExtra"),
            localOnlyExtra = list("localOnlyExtra"),
            retroDirs = list("retroDirs"),
            theme = p.getProperty("theme") ?: "light",
            themeName = p.getProperty("themeName") ?: "",
            customThemes = (p.getProperty("customThemes") ?: "").split(";")
                .map { it.trim() }.filter { it.isNotEmpty() },
            // 旧键 fontScale（0.8–1.4 百分比）按 14sp 基准换算成 sp；新键 globalFontSize 优先
            globalFontSize = p.getProperty("globalFontSize")?.toIntOrNull()?.coerceIn(11, 20)
                ?: p.getProperty("fontScale")?.toFloatOrNull()?.let { (it * 14).roundToInt().coerceIn(11, 20) }
                ?: 14,
            questionFontSize = p.getProperty("questionFontSize")?.toIntOrNull()?.coerceIn(10, 18) ?: 13,
            contentFontSize = p.getProperty("contentFontSize")?.toIntOrNull()?.coerceIn(10, 24) ?: 14,
            contentLineHeight = p.getProperty("contentLineHeight")?.toIntOrNull()?.coerceIn(100, 220) ?: 140,
            questionTagFontSize = p.getProperty("questionTagFontSize")?.toIntOrNull()?.coerceIn(10, 18) ?: 11,
            clickAnswerToEdit = p.getProperty("clickAnswerToEdit")?.toBooleanStrictOrNull() ?: true,
            recordingSaveDir = p.getProperty("recordingSaveDir") ?: "",
            recordingFps = p.getProperty("recordingFps")?.toIntOrNull()?.coerceIn(5, 60) ?: 15,
            recordingBitrate = p.getProperty("recordingBitrate")?.toIntOrNull()?.coerceIn(500, 20000) ?: 4000,
            selectedSourcePath = p.getProperty("selectedSourcePath") ?: "",
            feishuMode = p.getProperty("feishuMode") ?: "auto",
            feishuIp = p.getProperty("feishuIp") ?: "",
            feishuPort = p.getProperty("feishuPort")?.toIntOrNull()?.coerceIn(1024, 65535) ?: 5555,
            pushApkPath = p.getProperty("pushApkPath") ?: "",
            screenshotSaveDir = p.getProperty("screenshotSaveDir") ?: "",
            emulatorAvd = p.getProperty("emulatorAvd") ?: "3DAA",
        )
        Log.d("设置已读取 ${file.absolutePath} library=${s.libraryPath} theme=${s.theme}")
        return s
    }

    fun save(s: AppSettings) = save(s, null)

    /** syncedFile 为 null 时本机文件全量写入（旧行为）；否则本机层与仓库层按键分流 */
    fun save(s: AppSettings, syncedFile: File?) {
        val all = toProperties(s)
        if (syncedFile == null) {
            writeProperties(file, all, all.stringPropertyNames())
            Log.i("设置已写入 ${file.absolutePath}")
        } else {
            writeProperties(file, all, all.stringPropertyNames().filter { it in localKeys }.toSet())
            writeProperties(
                syncedFile,
                all,
                all.stringPropertyNames().filter { it !in localKeys }.toSet(),
                header = "# Atlas 仓库同步层配置：随本知识库仓库 git 同步，多设备保持一致；可手改，重启生效。",
            )
            Log.i("设置已分层写入 本机=${file.absolutePath} 仓库同步=${syncedFile.absolutePath}")
        }
    }

    private fun readProperties(f: File): java.util.Properties {
        val p = java.util.Properties()
        if (f.isFile) f.inputStream().use { p.load(it.reader(Charsets.UTF_8)) }
        return p
    }

    private fun toProperties(s: AppSettings): java.util.Properties {
        val p = java.util.Properties()
        p.setProperty("libraryPath", s.libraryPath)
        p.setProperty("sourceQuestionPaths", s.sourceQuestionPaths.joinToString(","))
        p.setProperty("ignoredExtra", s.ignoredExtra.joinToString(","))
        p.setProperty("localOnlyExtra", s.localOnlyExtra.joinToString(","))
        p.setProperty("retroDirs", s.retroDirs.joinToString(","))
        p.setProperty("theme", s.theme)
        p.setProperty("themeName", s.themeName)
        p.setProperty("customThemes", s.customThemes.joinToString(";"))
        p.setProperty("globalFontSize", s.globalFontSize.coerceIn(11, 20).toString())
        p.setProperty("questionFontSize", s.questionFontSize.coerceIn(10, 18).toString())
        p.setProperty("contentFontSize", s.contentFontSize.coerceIn(10, 24).toString())
        p.setProperty("contentLineHeight", s.contentLineHeight.coerceIn(100, 220).toString())
        p.setProperty("questionTagFontSize", s.questionTagFontSize.coerceIn(10, 18).toString())
        p.setProperty("clickAnswerToEdit", s.clickAnswerToEdit.toString())
        p.setProperty("recordingSaveDir", s.recordingSaveDir)
        p.setProperty("recordingFps", s.recordingFps.toString())
        p.setProperty("recordingBitrate", s.recordingBitrate.toString())
        if (s.selectedSourcePath.isNotEmpty()) p.setProperty("selectedSourcePath", s.selectedSourcePath)
        p.setProperty("feishuMode", s.feishuMode)
        if (s.feishuIp.isNotEmpty()) p.setProperty("feishuIp", s.feishuIp)
        p.setProperty("feishuPort", s.feishuPort.toString())
        if (s.pushApkPath.isNotEmpty()) p.setProperty("pushApkPath", s.pushApkPath)
        if (s.screenshotSaveDir.isNotEmpty()) p.setProperty("screenshotSaveDir", s.screenshotSaveDir)
        p.setProperty("emulatorAvd", s.emulatorAvd)
        return p
    }

    /** 手写 Properties 文本：固定键序、无时间戳注释（java.util.Properties 的 store 带当天日期，git diff 每次都脏）；
     *  键不用转义（全为固定标识符），值转义反斜杠与首空格 */
    private fun writeProperties(f: File, p: java.util.Properties, keys: Set<String>, header: String? = null) {
        f.parentFile?.mkdirs()
        val body = keys.sorted().joinToString("\n") { k ->
            val v = p.getProperty(k).orEmpty().replace("\\", "\\\\")
            "$k=" + if (v.startsWith(" ")) "\\$v" else v
        }
        val head = header?.let { "$it\n" } ?: ""
        f.writeText(head + body + "\n")
    }
}
