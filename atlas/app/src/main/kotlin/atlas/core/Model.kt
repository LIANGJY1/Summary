package atlas.core

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** 库内条目类型（PRD §6.2 内容条目） */
enum class ItemKind { NOTE, SKILL, FILE }

/** 三档隐私边界（PRD FR-A1） */
enum class Tier { IGNORED, LOCAL_ONLY, FULL }

/** 识别约定：frontmatter `type:` 或 目录名（PRD FR-A8） */
enum class DocMarker { NONE, RETROSPECTIVE, PROJECT_EXPERIENCE }

/** 题库标签：预置常用主题，同时允许用户在编辑题目时补充自定义标签。 */
object QuestionTags {
    val BUILT_IN = listOf("Android系统启动", "性能优化", "Handler", "操作系统")

    fun normalize(tags: List<String>): List<String> = tags
        .flatMap { it.split(',', '，') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun groupByTag(questions: List<MdStores.QuestionEntry>): Map<String, List<MdStores.QuestionEntry>> {
        val groups = linkedMapOf<String, MutableList<MdStores.QuestionEntry>>()
        questions.forEach { question ->
            val tags = normalize(question.tags)
            if (tags.isEmpty()) groups.getOrPut("未分类") { mutableListOf() }.add(question)
            else tags.forEach { tag -> groups.getOrPut(tag) { mutableListOf() }.add(question) }
        }
        return groups
    }
}

/** 题库视图规则：题库页面只按标签筛选/分组，来源仅作为题目的存档元数据。 */
object QuestionListModel {
    fun visible(
        questions: List<MdStores.QuestionEntry>,
        tag: String = "全部",
        query: String = "",
    ): List<MdStores.QuestionEntry> {
        val keyword = query.trim()
        return questions.filter {
            (tag == "全部" || tag in QuestionTags.normalize(it.tags)) &&
                (keyword.isEmpty() || it.q.contains(keyword, ignoreCase = true))
        }
    }

    fun groups(questions: List<MdStores.QuestionEntry>): Map<String, List<MdStores.QuestionEntry>> =
        QuestionTags.groupByTag(questions)

    /** 从现有题库生成一套练习题；只做本地筛选/抽取，不调用模型。 */
    fun generateSet(
        questions: List<MdStores.QuestionEntry>,
        tag: String = "全部",
        count: Int = 5,
        random: Boolean = true,
        randomSource: Random = Random.Default,
    ): List<MdStores.QuestionEntry> {
        val candidates = visible(questions, tag)
        val ordered = if (random) candidates.shuffled(randomSource) else candidates
        return ordered.take(count.coerceAtLeast(0))
    }
}

data class NoteFile(
    val relPath: String,
    val title: String,
    val kind: ItemKind,
    val sizeBytes: Long,
    val mtime: Long,
    val tier: Tier,
    val marker: DocMarker,
)

data class Chunk(
    val path: String,
    val section: String,
    val body: String,
)

object Md {

    /** 取 md 首个一级标题作为标题，退化为文件名 */
    fun titleOf(file: File): String {
        if (!file.isFile) return file.name
        return file.useLines(charset = Charsets.UTF_8) { lines ->
            lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        } ?: file.nameWithoutExtension
    }

    fun isMd(f: File) = f.extension.lowercase() == "md"

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.substring(0, 10)

    /** 按 `##` 小节切块（`#` 一级为文件题，`##` 为一等条目锚点，PRD FR-A2） */
    fun chunksOf(relPath: String, text: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        val lines = text.lines()
        var curSection = ""
        var buf = ArrayList<String>()
        fun flush() {
            val body = buf.joinToString("\n").trim()
            if (body.isNotEmpty()) out.add(Chunk(relPath, curSection.ifBlank { "(卷首)" }, body))
            buf = ArrayList()
        }
        var inFence = false
        for (line in lines) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            val isH2 = !inFence && line.startsWith("## ") && !line.startsWith("###")
            if (isH2) {
                flush()
                curSection = line.removePrefix("## ").trim()
                // 小节标题本身也入正文，保证可检索
                buf.add(line)
            } else {
                buf.add(line)
            }
        }
        flush()
        if (out.isEmpty()) {
            val t = text.trim()
            if (t.isNotEmpty()) out.add(Chunk(relPath, "", t))
        }
        return out
    }
}
