package atlas.core

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 知识文件的读写：<库根>/atlas/cards.md、questions.md（PRD FR-C2/D5）。
 * 均为「## 小节 = 一条」的 md 文件；元数据以 `- key: value` 行存于小节尾部；
 * 作答与批改记录以 `- log:` 行追加；人可手改，文件为准（读-改-写全文件原子替换）。
 */
object MdStores {

    /** 原子写：临时文件 + rename */
    fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        val ok = tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
        if (ok) Log.d("原子写 ${file.name} ${content.length} 字")
        else Log.e("原子写失败（临时文件残留）：${file.absolutePath}")
    }

    /** 把一个小节文本切成 (正文行, 元数据 map, 日志行)。元数据区只认小节**尾部**的连续 `- key:` 行，
     *  防止正文中恰好出现同样格式的列表行被误吞（深度验证修复 #2）。 */
    data class Section(val title: String, val bodyLines: List<String>, val meta: Map<String, String>, val logs: List<String>) {
        fun meta(key: String) = meta[key] ?: ""
    }

    fun splitSections(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val text = file.readText(Charsets.UTF_8)
        val out = ArrayList<String>()
        var buf = ArrayList<String>()
        var inFence = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (!inFence && line.startsWith("## ")) {
                if (buf.isNotEmpty()) out.add(buf.joinToString("\n"))
                buf = ArrayList(listOf(line))
            } else buf.add(line)
        }
        if (buf.isNotEmpty()) out.add(buf.joinToString("\n"))
        return out.filter { it.startsWith("## ") }
    }

    private val META_KEYS = setOf("deck", "source", "fsrs", "id", "status", "ref", "tags", "suspended")

    private fun isMetaLine(l: String): Boolean {
        val t = l.trim()
        if (!t.startsWith("- ")) return false
        val body = t.removePrefix("- ")
        if (body.startsWith("log:") || body.startsWith("log：")) return true
        val k = body.substringBefore(':').trim().lowercase()
        return k in META_KEYS && body.contains(':')
    }

    fun parseSection(section: String): Section {
        val lines = section.lines()
        val title = lines.firstOrNull()?.removePrefix("## ")?.trim() ?: ""
        val content = lines.drop(1)
        // 从尾部向前收敛连续元数据区（可跨越空行），正文其余部分保持原样
        var end = content.size
        while (end > 0) {
            val t = content[end - 1].trim()
            if (t.isEmpty()) { end--; continue }
            if (isMetaLine(content[end - 1])) { end--; continue }
            break
        }
        val body = ArrayList(content.subList(0, end))
        val meta = LinkedHashMap<String, String>()
        val logs = ArrayList<String>()
        for (i in end until content.size) {
            val t = content[i].trim()
            if (t.isEmpty()) continue
            if (t.startsWith("- log:") || t.startsWith("- log：")) { logs.add(t.substringAfter(':').trim()); continue }
            if (t.startsWith("- ") && t.contains(':')) {
                meta[t.removePrefix("- ").substringBefore(':').trim().lowercase()] = t.substringAfter(':').trim()
            }
        }
        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
        return Section(title, body, meta, logs)
    }

    fun renderSection(title: String, bodyLines: List<String>, meta: LinkedHashMap<String, String>, logs: List<String>): String {
        val sb = StringBuilder()
        sb.append("## ").append(title).append("\n\n")
        bodyLines.forEach { sb.append(it).append("\n") }
        if (bodyLines.isNotEmpty()) sb.append("\n")
        meta.forEach { (k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n") }
        logs.forEach { sb.append("- log: ").append(it).append("\n") }
        return sb.toString().trimEnd('\n')
    }

    fun renderFile(header: String, sections: List<String>): String {
        val sb = StringBuilder("# ").append(header).append("\n\n")
        sb.append("> 本文件由 Atlas 维护，人可直接编辑；以文件内容为准。请保留 `- key: value` 元数据行格式。\n\n")
        sections.forEach { sb.append(it).append("\n\n") }
        return sb.toString()
    }

    // ---------- Card ----------
    data class CardEntry(
        val id: String, val front: String, val back: String, val deck: String,
        val source: String, val fsrsJson: String, val logs: List<String>,
        val suspended: Boolean = false,
    )

    fun loadCards(f: File): List<CardEntry> = splitSections(f).mapNotNull { s ->
        val sec = parseSection(s)
        val id = sec.meta("id").ifBlank { Md.md5(sec.title) }
        if (sec.title.isBlank()) return@mapNotNull null
        CardEntry(
            id = id,
            front = sec.title,
            back = sec.bodyLines.joinToString("\n").trim(),
            deck = sec.meta("deck").ifBlank { "默认" },
            source = sec.meta("source").ifBlank { "手动" },
            fsrsJson = sec.meta("fsrs"),
            logs = sec.logs,
            suspended = sec.meta("suspended").equals("true", ignoreCase = true),
        )
    }

    fun saveCards(f: File, cards: List<CardEntry>) {
        val secs = cards.map { c ->
            val meta = linkedMapOf(
                "deck" to c.deck, "source" to c.source, "id" to c.id, "fsrs" to c.fsrsJson,
                "suspended" to if (c.suspended) "true" else "",
            ).filterValues { it.isNotEmpty() }
            renderSection(c.front, c.back.lines(), meta as LinkedHashMap<String, String>, c.logs)
        }
        atomicWrite(f, renderFile("Atlas 卡库（cards）", secs))
    }

    // ---------- Question ----------
    data class QuestionEntry(
        val id: String, val q: String, val ref: String, val status: String, val logs: List<String>,
        val source: String = "",
        val answer: String = "",
        val tags: List<String> = emptyList(),
    )

    fun loadQuestions(f: File): List<QuestionEntry> = splitSections(f).mapNotNull { s ->
        val sec = parseSection(s)
        if (sec.title.isBlank()) return@mapNotNull null
        QuestionEntry(
            id = sec.meta("id").ifBlank { Md.md5(sec.title) },
            q = sec.title,
            ref = sec.meta("ref"),
            status = sec.meta("status").ifBlank { "未测" },
            logs = sec.logs,
            source = sec.meta("source"),
            // 答案存小节正文（题目=标题、答案=正文，md 直觉）；旧条目正文为空 → 兜底显示要点
            answer = sec.bodyLines.joinToString("\n").trim(),
            tags = QuestionTags.normalize(sec.meta("tags").split(',', '，')),
        )
    }

    fun saveQuestions(f: File, qs: List<QuestionEntry>) {
        val secs = qs.map { q ->
            val meta = linkedMapOf(
                "status" to q.status, "ref" to q.ref, "source" to q.source, "id" to q.id,
                "tags" to QuestionTags.normalize(q.tags).joinToString(", "),
            ).filterValues { it.isNotEmpty() }
            val body = if (q.answer.isBlank()) emptyList() else q.answer.lines()
            renderSection(q.q, body, meta as LinkedHashMap<String, String>, q.logs)
        }
        atomicWrite(f, renderFile("Atlas 面试题库（questions）", secs))
    }

    fun now(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).substring(0, 16).replace("T", " ")
}
