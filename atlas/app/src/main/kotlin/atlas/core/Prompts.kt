package atlas.core

import java.io.File

/**
 * 提示词库的纯逻辑（UI 无关，可单测）：launcher_tool PromptsPanel 的存储移植。
 * 文件协议：`## 标题` 起一条，其后到下一个 `## ` 之前的行是正文；空行裁边。
 */
object Prompts {

    data class Entry(val title: String, val body: String)

    /** Returns a normalized autosave value only for an existing entry with a valid, changed title/body. */
    fun autoSaveCandidate(current: Entry?, title: String, body: String): Entry? {
        if (current == null || title.isBlank()) return null
        return Entry(title.trim(), body.trim()).takeIf { it != current }
    }

    fun load(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        val entries = mutableListOf<Entry>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            val t = title?.trim()
            if (!t.isNullOrBlank()) entries.add(Entry(t, body.toString().trim()))
            title = null
            body.clear()
        }
        file.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("## ")) {
                    flush()
                    title = line.removePrefix("## ").trim()
                } else if (title != null) {
                    body.appendLine(line)
                }
            }
        }
        flush()
        return entries
    }

    fun save(file: File, entries: List<Entry>) {
        file.parentFile?.mkdirs()
        file.writeText(
            entries.joinToString(separator = "\n\n") { e ->
                buildString {
                    appendLine("## ${e.title.trim()}")
                    append(e.body.trim())
                }
            } + if (entries.isEmpty()) "" else "\n",
        )
    }
}
