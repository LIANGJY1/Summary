package atlas.core

import java.io.File

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
    val fontScale: Float = 1f,
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

/** 配置持久化（Properties，应用数据目录下 settings.properties） */
class SettingsStore(private val file: File) {
    fun load(): AppSettings {
        if (!file.isFile) { Log.d("设置文件不存在，用默认值：${file.absolutePath}"); return AppSettings() }
        val p = java.util.Properties()
        file.inputStream().use { p.load(it.reader(Charsets.UTF_8)) }
        fun list(k: String) = (p.getProperty(k) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val s = AppSettings(
            // 旧版本可能已经写入空路径；空路径不应让用户每次重新选择知识库。
            libraryPath = p.getProperty("libraryPath")?.trim().orEmpty().ifBlank { DEFAULT_LIBRARY_PATH },
            sourceQuestionPaths = list("sourceQuestionPaths").ifEmpty { SourceQuestions.DEFAULT_SUPPORTED_PATHS },
            ignoredExtra = list("ignoredExtra"),
            localOnlyExtra = list("localOnlyExtra"),
            retroDirs = list("retroDirs"),
            theme = p.getProperty("theme") ?: "light",
            themeName = p.getProperty("themeName") ?: "",
            customThemes = (p.getProperty("customThemes") ?: "").split(";")
                .map { it.trim() }.filter { it.isNotEmpty() },
            fontScale = p.getProperty("fontScale")?.toFloatOrNull()?.coerceIn(0.8f, 1.4f) ?: 1f,
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

    fun save(s: AppSettings) {
        file.parentFile?.mkdirs()
        val p = java.util.Properties()
        p.setProperty("libraryPath", s.libraryPath)
        p.setProperty("sourceQuestionPaths", s.sourceQuestionPaths.joinToString(","))
        p.setProperty("ignoredExtra", s.ignoredExtra.joinToString(","))
        p.setProperty("localOnlyExtra", s.localOnlyExtra.joinToString(","))
        p.setProperty("retroDirs", s.retroDirs.joinToString(","))
        p.setProperty("theme", s.theme)
        p.setProperty("themeName", s.themeName)
        p.setProperty("customThemes", s.customThemes.joinToString(";"))
        p.setProperty("fontScale", s.fontScale.coerceIn(0.8f, 1.4f).toString())
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
        file.outputStream().use { p.store(it, "Atlas settings") }
        Log.i("设置已写入 ${file.absolutePath}")
    }
}
