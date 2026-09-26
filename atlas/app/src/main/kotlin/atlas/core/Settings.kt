package atlas.core

import java.io.File

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
    val libraryPath: String = "",
    /** 支持解析为题目的源文档；路径以知识库根目录为基准，目录规则以 / 结尾。 */
    val sourceQuestionPaths: List<String> = SourceQuestions.DEFAULT_SUPPORTED_PATHS,
    val ignoredExtra: List<String> = emptyList(),
    val localOnlyExtra: List<String> = emptyList(),
    val retroDirs: List<String> = emptyList(),
    val theme: String = "light", // light | dark
    val fontScale: Float = 1f,
    /** 题库答案区域单击时是否打开编辑弹窗；关闭后仍可划词，编辑按钮不受影响。 */
    val clickAnswerToEdit: Boolean = true,
    /** Markdown 内容展示样式：reader 为阅读优化样式，classic 为旧版样式。 */
    val markdownStyle: String = "reader",
    /** 屏幕录制（tools/screen_recorder）参数：保存目录（空=默认 ~/Videos/Screencasts）、帧率、码率 kbps。 */
    val recordingSaveDir: String = "",
    val recordingFps: Int = 15,
    val recordingBitrate: Int = 4000,
    /** 题库上次选中的源文档路径（重启恢复）；空 = 未记录，用默认文档。 */
    val selectedSourcePath: String = "",
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
            libraryPath = p.getProperty("libraryPath") ?: "",
            sourceQuestionPaths = list("sourceQuestionPaths").ifEmpty { SourceQuestions.DEFAULT_SUPPORTED_PATHS },
            ignoredExtra = list("ignoredExtra"),
            localOnlyExtra = list("localOnlyExtra"),
            retroDirs = list("retroDirs"),
            theme = p.getProperty("theme") ?: "light",
            fontScale = p.getProperty("fontScale")?.toFloatOrNull()?.coerceIn(0.8f, 1.4f) ?: 1f,
            clickAnswerToEdit = p.getProperty("clickAnswerToEdit")?.toBooleanStrictOrNull() ?: true,
            markdownStyle = p.getProperty("markdownStyle")?.takeIf { it == "reader" || it == "classic" } ?: "reader",
            recordingSaveDir = p.getProperty("recordingSaveDir") ?: "",
            recordingFps = p.getProperty("recordingFps")?.toIntOrNull()?.coerceIn(5, 60) ?: 15,
            recordingBitrate = p.getProperty("recordingBitrate")?.toIntOrNull()?.coerceIn(500, 20000) ?: 4000,
            selectedSourcePath = p.getProperty("selectedSourcePath") ?: "",
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
        p.setProperty("fontScale", s.fontScale.coerceIn(0.8f, 1.4f).toString())
        p.setProperty("clickAnswerToEdit", s.clickAnswerToEdit.toString())
        p.setProperty("markdownStyle", if (s.markdownStyle == "classic") "classic" else "reader")
        p.setProperty("recordingSaveDir", s.recordingSaveDir)
        p.setProperty("recordingFps", s.recordingFps.toString())
        p.setProperty("recordingBitrate", s.recordingBitrate.toString())
        if (s.selectedSourcePath.isNotEmpty()) p.setProperty("selectedSourcePath", s.selectedSourcePath)
        file.outputStream().use { p.store(it, "Atlas settings") }
        Log.i("设置已写入 ${file.absolutePath}")
    }
}
