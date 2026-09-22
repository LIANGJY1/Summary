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
