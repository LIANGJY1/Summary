package atlas.core

import java.io.File

/**
 * 收件箱协议（PRD FR-E1/§1.4）：agent 把候选写入 `<库根>/atlas/inbox/` 下的 md 文件，
 * 每个候选是一个块（用行首 `---` 分隔），块内为 `key: value` 行：
 *
 *     kind: card
 *     deck: Android
 *     source: AI 生成（未对照库）
 *     front: 卡面单行
 *     back: 背面第一行
 *       背面续行（缩进两空格）
 *
 * kind: question 同理（q/ref/source）。
 * Atlas 确认后转入正式文件并把候选从 inbox 删除；整批忽略 = 删除文件。
 */
object Inbox {

    enum class Kind { CARD, QUESTION, UNKNOWN }

    data class Candidate(val file: File, val kind: Kind, val fields: Map<String, String>, val blockHash: String) {
        fun s(k: String) = fields[k]?.trim() ?: ""
    }

    /** 生成候选的 skill：优先显式 skill，其次从兼容的 source 前缀提取。 */
    fun skillName(candidate: Candidate): String {
        val explicit = candidate.s("skill")
        if (explicit.isNotBlank()) return explicit.substringBefore(':').trim()
        val source = candidate.s("source")
        if (source.isBlank()) return "未标注"
        return source.substringBefore(':').trim().ifBlank { source }
    }

    fun skillNames(candidates: List<Candidate>): List<String> =
        candidates.map(::skillName).filter { it != "未标注" }.distinct().sorted()

    /** 只解析安全的单段 skill 名，优先打开 ~/.agents/skills 的真实源文件。 */
    fun skillFile(skillName: String): File? {
        val name = skillName.trim()
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") return null
        val candidates = listOf(
            File(System.getProperty("user.home"), ".agents/skills/$name/SKILL.md"),
            File("skills/$name/SKILL.md"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun scan(inboxDir: File): List<Candidate> {
        val out = ArrayList<Candidate>()
        if (!inboxDir.isDirectory) return out
        inboxDir.listFiles { f -> f.isFile && Md.isMd(f) }?.sortedBy { it.name }?.forEach { f ->
            parseFile(f).forEach { out.add(it) }
        }
        Log.d("收件箱扫描 dir=${inboxDir.absolutePath} 候选=${out.size} 条")
        return out
    }

    fun parseFile(f: File): List<Candidate> {
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("收件箱文件读取失败 ${f.name}：${e.message}")
            return emptyList()
        }
        val blocks = text.split(Regex("(?m)^---\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Candidate>()
        for (b in blocks) out += parseBlock(f, b, Md.md5(b))
        return out
    }

    private fun parseBlock(f: File, block: String, blockHash: String): Candidate {
        val fields = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var key: String? = null
        for (line in block.lines()) {
            val indented = line.startsWith("  ") || line.startsWith("\t")
            val m = Regex("^(\\w+)\\s*:\\s*(.*)$").find(line)
            if (m != null && !indented) {
                key?.let { fields[key!!] = sb.toString().trim() }
                key = m.groupValues[1].lowercase()
                sb.setLength(0)
                sb.append(m.groupValues[2])
            } else if (key != null && (indented || (line.isBlank() && sb.isNotEmpty()))) {
                sb.append("\n").append(line.trimStart())
            } else {
                // 无 key 上下文的裸行：当作 front 的续行兜底
                if (key == null) {
                    key = "front"; sb.append(line)
                }
            }
        }
        key?.let { fields[key] = sb.toString().trim() }
        val kind = when (fields["kind"]?.lowercase()) {
            "card" -> Kind.CARD; "question" -> Kind.QUESTION
            else -> Kind.UNKNOWN
        }
        return Candidate(f, kind, fields, blockHash)
    }

    /** 确认/丢弃后按块哈希精确移除；文件空了则删除；找不到（外部已改）则不动文件 */
    fun removeBlock(candidate: Candidate) {
        val f = candidate.file
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("收件箱移除失败（读文件）：${f.name} ${e.message}")
            return
        }
        val parts = text.split(Regex("(?m)^---\\s*$")).toMutableList()
        val idx = parts.indexOfFirst { Md.md5(it.trim()) == candidate.blockHash }
        if (idx < 0) {
            Log.w("收件箱移除失败：块哈希未命中（文件可能已被外部修改）file=${f.name} hash=${candidate.blockHash.take(8)}")
            return
        }
        parts.removeAt(idx)
        val rest = parts.joinToString("\n---\n").trim()
        if (rest.isEmpty()) f.delete() else f.writeText(rest, Charsets.UTF_8)
        Log.i("收件箱移除候选 file=${f.name} kind=${candidate.kind} hash=${candidate.blockHash.take(8)} 剩块=${parts.size}")
    }
}
