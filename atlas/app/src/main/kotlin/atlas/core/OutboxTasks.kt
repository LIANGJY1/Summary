package atlas.core

import java.io.File

/**
 * 发件箱任务文件统一生成（PRD FR-E3）：Atlas 写出、agent 消费、完成后 status 改 done。
 * 收敛原先散落在 AppStore/KnowledgeView 等处的生成逻辑，格式保持不变。
 */
object OutboxTasks {

    data class OutboxTask(val type: String, val file: File, val status: String, val title: String)

    fun writeInterview(outboxDir: File, questions: List<MdStores.QuestionEntry>): File = write(
        outboxDir, "interview-${System.currentTimeMillis() / 1000}.md", "模拟面试任务",
        buildString {
            append("- type: interview\n- status: todo\n\n")
            append("逐题向用户提问（可追问 1–2 层），用户作答后按参考要点批改（通过/勉强/不通过 + 点评）。完成后：\n")
            append("1. 把每题的批改以 `- log: <日期> | <结论> | <点评摘要>` 追加到对应题目的 questions.md 小节；\n")
            append("2. 把「不通过/勉强」的题的 `status` 保持/改为 `未测`（补漏后可重测）；\n")
            append("3. 把本任务 status 改为 done。\n\n## 题目清单\n")
            questions.forEach { q ->
                append("\n### ${q.q}\n- id: ${q.id}\n- 参考要点: ${q.ref.ifBlank { "（无，请基于通用知识批改）" }}\n")
            }
        },
    )

    /** instruction 例：「请基于以上来源出 3–5 道题目候选…」「请通读该笔记，出 3–8 道…」 */
    fun writeCardgen(outboxDir: File, source: String, instruction: String, deck: String = "建议"): File = write(
        outboxDir, "cardgen-${Md.md5(source)}.md", "出题任务",
        """
        - type: cardgen
        - source: $source
        - deck: $deck
        - status: todo

        $instruction，写入 `<库根>/atlas/inbox/`（每个候选只写：kind: question、q、answer）。
        """.trimIndent(),
    )

    /** 扫描 outbox：type/status/title 供 UI 展示 agent 侧消费进度 */
    fun scanOutbox(outboxDir: File): List<OutboxTask> {
        if (!outboxDir.isDirectory) return emptyList()
        val list = outboxDir.listFiles { f -> f.isFile && Md.isMd(f) }?.sortedBy { it.name }?.mapNotNull { f ->
            val text = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { Log.w("outbox 文件读取失败 ${f.name}：${e.message}"); return@mapNotNull null }
            val type = Regex("""(?m)^-\s*type:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "unknown"
            val status = Regex("""(?m)^-\s*status:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "todo"
            val title = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: f.name
            OutboxTask(type, f, status, title)
        } ?: emptyList()
        Log.d("outbox 扫描 ${list.size} 个任务（todo=${list.count { it.status != "done" }}）")
        return list
    }

    private fun write(outboxDir: File, name: String, title: String, metaAndBody: String): File {
        outboxDir.mkdirs()
        val f = File(outboxDir, name)
        f.writeText("# $title\n\n$metaAndBody\n", Charsets.UTF_8)
        Log.i("outbox 任务写入 ${f.absolutePath}（${metaAndBody.length} 字）")
        return f
    }
}
