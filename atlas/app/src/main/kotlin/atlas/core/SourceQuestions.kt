package atlas.core

/**
 * 同源题目文档协议的首期解析器。
 *
 * 一份 Markdown 文档可以包含多道题：合法的 Q 标记是题目起点，直到下一个 Q
 * 标记或文件结尾的全部 Markdown 都是答案。当前只开放一个真实文档，避免在协议
 * 尚未稳定时误读整个知识库。
 */
object SourceQuestions {
    const val TARGET_PATH = "knowledge-base/language/kotlin/01-语法基础.md"
    val DEFAULT_SUPPORTED_PATHS = listOf(TARGET_PATH)

    data class Entry(
        val sourcePath: String,
        val number: Int,
        val question: String,
        val answer: String,
        val startOffset: Int,
        val endOffset: Int,
        val answerStartOffset: Int,
        val document: String,
    ) {
        val id: String get() = "$sourcePath#Q$number:${Md.md5(question)}"
    }

    data class SectionHeading(
        val sourcePath: String,
        val title: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    data class Draft(val question: String, val answer: String, val number: Int? = null)

    private data class Marker(val number: Int, val question: String, val start: Int, val lineEnd: Int)
    private data class SectionMarker(val title: String, val start: Int, val lineEnd: Int)

    private val boldPattern = Regex("^\\s*\\*\\*Q(\\d+)\\s*[:：]\\s*(.*?)\\*\\*\\s*$")
    private val headingPattern = Regex("^\\s*#{1,6}\\s*Q(\\d+)\\s*[:：]\\s*(.*?)\\s*$")
    private val plainPattern = Regex("^\\s*Q(\\d+)\\s*[:：]\\s*(.*?)\\s*$")
    private val sectionPattern = Regex("^\\s*第\\s*[0-9一二三四五六七八九十百]+\\s*[章节]\\s+(.+?)\\s*$")

    fun isSupportedPath(path: String, supportedPaths: List<String> = DEFAULT_SUPPORTED_PATHS): Boolean {
        val normalized = path.replace('\\', '/').trimStart('/')
        return supportedPaths.any { configuredPath ->
            val configured = configuredPath.replace('\\', '/').trim('/')
            if (configured.isBlank()) return@any false
            val shortPath = configured.removePrefix("knowledge-base/")
            val directoryRule = configuredPath.replace('\\', '/').trim().endsWith('/')
            if (directoryRule) {
                normalized == configured || normalized.startsWith("$configured/") && normalized.endsWith(".md")
            } else {
                normalized == configured || normalized == shortPath ||
                    normalized.endsWith("/$configured") || normalized.endsWith("/$shortPath")
            }
        }
    }

    /** 只保留配置的单个 Markdown 文档或目录规则命中的文档。 */
    fun supportedDocuments(documents: Iterable<String>, supportedPaths: List<String> = DEFAULT_SUPPORTED_PATHS): List<String> =
        documents.filter { isSupportedPath(it, supportedPaths) }.distinct().sorted()

    fun parse(sourcePath: String, document: String, supportedPaths: List<String> = DEFAULT_SUPPORTED_PATHS): List<Entry> {
        if (!isSupportedPath(sourcePath, supportedPaths)) return emptyList()
        val markers = ArrayList<Marker>()
        val sections = sectionMarkers(document)
        var offset = 0
        var fenced = false
        document.split("\n").forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced
            } else if (!fenced) {
                marker(line, offset)?.let { markers += it }
            }
            offset += line.length + 1
        }

        return markers.mapIndexed { index, marker ->
            val nextQuestion = markers.getOrNull(index + 1)?.start ?: document.length
            val nextSection = sections.firstOrNull { it.start > marker.start }?.start ?: document.length
            val end = minOf(nextQuestion, nextSection)
            Entry(
                sourcePath = sourcePath,
                number = marker.number,
                question = marker.question.trim(),
                answer = document.substring(marker.lineEnd, end).trim(),
                startOffset = marker.start,
                endOffset = end,
                answerStartOffset = marker.lineEnd,
                document = document,
            )
        }
    }

    /** 扫描整棵知识库目录树中的 Markdown 文档，收集其中的 Q 题目。 */
    fun parseAll(documents: Iterable<Pair<String, String>>): List<Entry> = buildList {
        documents.forEach { (path, document) ->
            addAll(parse(path, document, listOf(path)))
        }
    }

    /** 解析批量新建输入：每个非空行就是一道题目，答案统一留空。 */
    fun parseBatch(text: String): List<Draft> {
        return text.replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val question = line
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("• ")
                    .replace(Regex("^\\d+[.、)]\\s*"), "")
                    .trim()
                Draft(question, "")
            }
            .filter { it.question.isNotBlank() }
            .toList()
    }

    /** 追加新题目并从现有最大题号之后连续编号。 */
    fun append(document: String, drafts: List<Draft>): String {
        require(drafts.isNotEmpty()) { "至少需要一道题目" }
        require(drafts.all { it.question.isNotBlank() }) { "题目不能为空" }
        val existingNumbers = Regex("(?:^|\\n)\\s*(?:\\*\\*)?Q(\\d+)\\s*[:：]").findAll(document)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
        val explicitNumbers = drafts.mapNotNull { it.number }
        require(explicitNumbers.all { it > 0 }) { "题目序号必须是正整数" }
        require(explicitNumbers.size == explicitNumbers.toSet().size) { "题目序号不能重复" }
        require(explicitNumbers.none { it in existingNumbers }) { "题目序号已存在" }
        val usedNumbers = existingNumbers.toMutableSet()
        usedNumbers.addAll(explicitNumbers)
        var nextNumber = (existingNumbers.maxOrNull() ?: 0) + 1
        val blocks = drafts.map { draft ->
            val number = draft.number ?: run {
                while (nextNumber in usedNumbers) nextNumber++
                nextNumber++
                nextNumber - 1
            }
            usedNumbers += number
            val block = "**Q$number: ${draft.question.trim()}**\n\n${draft.answer.trim()}"
            block
        }
        val prefix = if (document.isBlank()) "" else "\n\n"
        return document.trimEnd() + prefix + blocks.joinToString("\n\n") + "\n"
    }

    /** 在指定题号的位置插入一道题，原题及后续题目向后顺延并重新编号。 */
    fun insertAtNumber(document: String, draft: Draft, number: Int): String {
        require(draft.question.isNotBlank()) { "题目不能为空" }
        require(number > 0) { "题目序号必须是正整数" }
        val markerPath = "__insert-question__"
        val entries = parse(markerPath, document, listOf(markerPath)).sortedBy { it.startOffset }
        if (entries.isEmpty()) {
            return "**Q1: ${draft.question.trim()}**\n\n${draft.answer.trim()}\n"
        }
        val gaps = entries.zipWithNext().map { (left, right) -> document.substring(left.endOffset, right.startOffset) }
        require(gaps.all { it.isBlank() }) { "题目之间存在章节内容，无法在此位置插入" }

        val insertionIndex = (number - 1).coerceIn(0, entries.size)
        val blocks = entries.mapIndexed { index, entry ->
            val shiftedNumber = index + 1 + if (index >= insertionIndex) 1 else 0
            numberedBlock(document, entry, shiftedNumber)
        }.toMutableList()
        blocks.add(insertionIndex, "**Q${insertionIndex + 1}: ${draft.question.trim()}**\n\n${draft.answer.trim()}")
        val start = entries.first().startOffset
        val end = entries.last().endOffset
        val separator = gaps.firstOrNull()?.takeIf { it.isNotBlank() } ?: "\n\n"
        return document.substring(0, start) + blocks.joinToString(separator) + document.substring(end)
    }

    /**
     * 在同一份源文档中移动完整题目块。题目之间若存在非空白内容，则拒绝移动，
     * 避免把章节说明、注释等内容和题目错误地拆开或丢失。
     */
    fun reorderEntries(document: String, entries: List<Entry>, fromIndex: Int, toIndex: Int): String? {
        if (entries.isEmpty() || fromIndex !in entries.indices || toIndex !in entries.indices) return null
        if (fromIndex == toIndex) return document
        if (entries.any { it.document != document }) return null

        val ordered = entries.sortedBy { it.startOffset }
        if (ordered.zipWithNext().any { (left, right) -> left.endOffset > right.startOffset }) return null
        val gaps = ordered.zipWithNext().map { (left, right) -> document.substring(left.endOffset, right.startOffset) }
        if (gaps.any { it.isNotBlank() }) return null

        val moved = entries.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        val blocks = moved.mapIndexed { index, entry ->
            numberedBlock(document, entry, index + 1)
        }
        val separator = gaps.firstOrNull()?.takeIf { it.isNotBlank() } ?: "\n\n"
        val start = ordered.first().startOffset
        val end = ordered.last().endOffset
        return document.substring(0, start) + blocks.joinToString(separator) + document.substring(end)
    }

    private fun numberedBlock(document: String, entry: Entry, number: Int): String {
        val header = document.substring(entry.startOffset, entry.answerStartOffset)
        val renumberedHeader = header.replaceFirst("Q${entry.number}", "Q$number")
        return renumberedHeader + document.substring(entry.answerStartOffset, entry.endOffset)
    }

    fun parseSections(sourcePath: String, document: String, supportedPaths: List<String> = DEFAULT_SUPPORTED_PATHS): List<SectionHeading> {
        if (!isSupportedPath(sourcePath, supportedPaths)) return emptyList()
        return sectionMarkers(document).map {
            SectionHeading(sourcePath, it.title, it.start, it.lineEnd)
        }
    }

    private fun sectionMarkers(document: String): List<SectionMarker> {
        val sections = ArrayList<SectionMarker>()
        var offset = 0
        var fenced = false
        document.split("\n").forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced
            } else if (!fenced) {
                sectionPattern.matchEntire(line)?.let { match ->
                    sections += SectionMarker(
                        title = match.groupValues[1].trim(),
                        start = offset,
                        lineEnd = offset + line.length,
                    )
                }
            }
            offset += line.length + 1
        }
        return sections
    }

    private fun marker(line: String, offset: Int): Marker? {
        val match = listOf(boldPattern, headingPattern, plainPattern)
            .firstNotNullOfOrNull { it.matchEntire(line) }
            ?: return null
        return Marker(
            number = match.groupValues[1].toIntOrNull() ?: return null,
            question = match.groupValues[2],
            start = offset,
            lineEnd = offset + line.length,
        )
    }

    /** 只替换当前 Q 块，文档其余内容保持原样。 */
    fun replace(entry: Entry, question: String, answer: String): String {
        require(question.isNotBlank()) { "题目不能为空" }
        val rendered = "**Q${entry.number}: ${question.trim()}**\n\n${answer.trim()}"
        val suffix = if (entry.endOffset < entry.document.length) "\n\n" else ""
        return entry.document.substring(0, entry.startOffset) + rendered + suffix +
            entry.document.substring(entry.endOffset)
    }
}
