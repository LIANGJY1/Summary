package atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import atlas.core.AppSettings
import atlas.core.DocMarker
import atlas.core.Inbox
import atlas.core.Log
import atlas.core.MdStores
import atlas.core.MdStores.CardEntry
import atlas.core.MdStores.QuestionEntry
import atlas.core.NoteFile
import atlas.core.OutboxTasks
import atlas.core.SettingsStore
import atlas.core.SourceQuestions
import atlas.fsrs.FsrsEngine
import atlas.index.Indexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.awt.Desktop
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用中枢：持有全部状态与动作。UI 只读状态 + 调动作。
 */
class AppStore(private val configDir: File = File(System.getProperty("user.home"), ".local/share/atlas")) {

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
    /** 当前同源题目文档中的章节标题，独立于题目答案展示。 */
    val sourceSections = mutableStateListOf<SourceQuestions.SectionHeading>()
    /** 题库左侧的知识库 Markdown 文档列表。 */
    val knowledgeDocuments = mutableStateListOf<String>()
    var selectedSourcePath by mutableStateOf(SourceQuestions.TARGET_PATH)
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
            settings = settingsStore.load()
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

    fun saveSettings() { Log.i("设置保存 libraryPath=${settings.libraryPath} 仅本地=${settings.localOnlyExtra.size}个 忽略额外=${settings.ignoredExtra.size}个"); settingsStore.save(settings) }

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
        Log.timed("载入知识文件与收件箱", warnMs = 500) {
            reloadKnowledgeFiles()
            scanInbox()
            refreshOutbox()
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
                cards.clear(); cards.addAll(MdStores.loadCards(cardsFile()))
                questions.clear(); questions.addAll(MdStores.loadQuestions(questionsFile()))
                knowledgeDocuments.clear()
                knowledgeDocuments.addAll(scanKnowledgeDocuments())
                sourceQuestions.clear()
                allSourceQuestions.clear()
                sourceSections.clear()
                val mappedDocuments = SourceQuestions.supportedDocuments(knowledgeDocuments, settings.sourceQuestionPaths)
                val documents = mappedDocuments.mapNotNull { path ->
                    val file = sourceDocumentFile(path)
                    if (file.isFile) path to file.readText(Charsets.UTF_8) else null
                }
                allSourceQuestions.addAll(SourceQuestions.parseAll(documents))
                val source = sourceQuestionFile()
                if (source.isFile && SourceQuestions.isSupportedPath(selectedSourcePath, settings.sourceQuestionPaths)) {
                    val document = source.readText(Charsets.UTF_8)
                    sourceQuestions.addAll(SourceQuestions.parse(selectedSourcePath, document, settings.sourceQuestionPaths))
                    sourceSections.addAll(SourceQuestions.parseSections(selectedSourcePath, document, settings.sourceQuestionPaths))
                }
                rebuildDueQueue()
            }
        }
    }

    fun selectSourceDocument(path: String) {
        if (path == selectedSourcePath) return
        selectedSourcePath = path
        Log.i("切换题库源文档 → $path")
        reloadKnowledgeFiles()
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
    fun saveSourceQuestion(entry: SourceQuestions.Entry, question: String, answer: String): Boolean {
        val file = sourceQuestionFile()
        val current = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (current != entry.document) {
            Log.w("同源题目写回冲突：源文件已变化 file=${file.absolutePath}")
            reloadKnowledgeFiles()
            showToast("源文档已被外部修改，已重新加载")
            return false
        }
        MdStores.atomicWrite(file, SourceQuestions.replace(entry, question, answer))
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
        val updatedTarget = SourceQuestions.append(targetDocument, listOf(SourceQuestions.Draft(entry.question, entry.answer)))
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
            watchJob = scope.launch {
                var lastSig = knowledgeSignature()
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
        return "$files|$docs|$inbox"
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
