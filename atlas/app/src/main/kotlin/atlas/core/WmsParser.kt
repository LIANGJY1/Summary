package atlas.core

/**
 * WMS 查看器的纯逻辑（UI 无关，可单测）：wms_viewer_module.py 的解析与 diff 移植。
 * 解析 `adb shell dumpsys window containers` 等输出的缩进树，按节点类型分类着色；
 * 双栏对比用 shortName 序列的 LCS 对齐（difflib.SequenceMatcher 的简化实现）。
 */
object WmsParser {

    /** 预置命令（照抄 python 版 DEFAULT_COMMANDS） */
    val DEFAULT_COMMANDS = listOf(
        "dumpsys window containers",
        "dumpsys activity containers",
        "dumpsys window windows",
        "dumpsys activity activities",
        "dumpsys window tokens",
        "dumpsys window displays",
        "dumpsys window policy",
        "dumpsys window sessions",
        "dumpsys window windows | awk '/Window #/{win=$0} /mHasSurface=true/{print win}'",
    )

    enum class Category { ROOT, DISPLAY, WINDOW, ACTIVITY, TASK, DEFAULT }

    /** 树节点：rawText 为原始行（右去空白），shortName/category 为展示用摘要 */
    data class Node(
        val rawText: String,
        val indentLevel: Int,
        val depth: Int,
        val shortName: String,
        val category: Category,
    ) {
        val children = mutableListOf<Node>()
    }

    enum class Diff { NONE, ADD, REMOVE, MODIFY }

    private val nodeRegex = Regex("([A-Za-z0-9]+)\\{([0-9a-f]+)\\s+(.+?)\\}")
    private val nameAttrRegex = Regex("name=\"([^\"]+)\"")
    private val taskIdRegex = Regex("taskId=(\\d+)")

    /** 节点摘要与分类（逐分支照抄 python _parse_node_info） */
    fun classify(rawText: String): Pair<String, Category> {
        val m = nodeRegex.find(rawText)
        if (m != null) {
            val type = m.groupValues[1]
            val details = m.groupValues[3]
            return when {
                type == "WindowState" || type == "Window" ->
                    "Window{$details}" to Category.WINDOW
                type == "ActivityRecord" -> {
                    val last = details.split(" ").lastOrNull().orEmpty()
                    "Activity: $last" to Category.ACTIVITY
                }
                type == "Task" -> {
                    val id = taskIdRegex.find(details)?.groupValues?.get(1)
                    val name = id ?: details.split(" ").firstOrNull().orEmpty()
                    "Task: $name" to Category.TASK
                }
                else -> {
                    var short = "${type}: ${details.split(" ").firstOrNull().orEmpty()}"
                    var cat = if ("Display" in type || type == "RootWindowContainer") Category.DISPLAY else Category.DEFAULT
                    if ("Window:" in short) {
                        short = "Window{$details}"
                        cat = Category.WINDOW
                    }
                    short to cat
                }
            }
        }
        val stripped = rawText.trim()
        val parts = stripped.split(" ")
        if (parts.isNotEmpty() && parts[0].startsWith("#")) {
            val short: String = if (parts.size > 1) {
                val nameMatch = nameAttrRegex.find(rawText)
                if (nameMatch != null) "${parts[0]} ${parts[1]} (${nameMatch.groupValues[1]})"
                else parts.take(3).joinToString(" ")
            } else {
                parts[0]
            }
            val cat = if ("Display" in short || "Root" in short) Category.DISPLAY else Category.DEFAULT
            return short to cat
        }
        if (parts.isNotEmpty()) {
            val short = parts[0]
            return short to if (short == "ROOT") Category.ROOT else Category.DEFAULT
        }
        return stripped to Category.DEFAULT
    }

    /** 缩进栈解析（照抄 parse_dumpsys）：根节点 indent=-1，空行跳过 */
    fun parse(lines: List<String>): Node {
        val root = Node("ROOT", -1, -1, "ROOT", Category.ROOT)
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        for (line in lines) {
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart(' ').length
            while (stack.isNotEmpty() && stack.last().indentLevel >= indent) stack.removeLast()
            val parent = stack.last()
            val text = line.trimEnd()
            val (short, cat) = classify(text)
            val node = Node(text, indent, parent.depth + 1, short, cat)
            parent.children.add(node)
            stack.addLast(node)
        }
        return root
    }

    /** 先序展平（不含根），供列表渲染与 diff 对齐 */
    fun flatten(root: Node): List<Node> = buildList {
        fun walk(n: Node) {
            if (n.indentLevel != -1) add(n)
            n.children.forEach(::walk)
        }
        walk(root)
    }

    enum class DiffOp { EQUAL, REPLACE, DELETE, INSERT }

    data class DiffOpcode(val tag: DiffOp, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    /** LCS 对齐产生 opcodes（difflib.SequenceMatcher.get_opcodes 的简化实现，无 autojunk） */
    fun diffOpcodes(a: List<String>, b: List<String>): List<DiffOpcode> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val ops = mutableListOf<DiffOpcode>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                val i1 = i
                val j1 = j
                while (i < n && j < m && a[i] == b[j]) { i++; j++ }
                ops.add(DiffOpcode(DiffOp.EQUAL, i1, i, j1, j))
                continue
            }
            val i1 = i
            val j1 = j
            while (i < n && j < m && a[i] != b[j]) {
                if (lcs[i + 1][j] >= lcs[i][j + 1]) i++ else j++
            }
            val tag = when {
                j == j1 -> DiffOp.DELETE
                i == i1 -> DiffOp.INSERT
                else -> DiffOp.REPLACE
            }
            ops.add(DiffOpcode(tag, i1, i, j1, j))
        }
        if (i < n) ops.add(DiffOpcode(DiffOp.DELETE, i, n, j, j))
        if (j < m) ops.add(DiffOpcode(DiffOp.INSERT, i, i, j, m))
        return ops
    }

    /** 双栏树 diff：按展平序列对齐，返回每个节点的 diff 标记 */
    fun diff(leftFlat: List<Node>, rightFlat: List<Node>): Pair<List<Diff>, List<Diff>> {
        val leftDiff = MutableList(leftFlat.size) { Diff.NONE }
        val rightDiff = MutableList(rightFlat.size) { Diff.NONE }
        for (op in diffOpcodes(leftFlat.map { it.shortName }, rightFlat.map { it.shortName })) {
            when (op.tag) {
                DiffOp.REPLACE -> {
                    for (i in op.i1 until op.i2) leftDiff[i] = Diff.MODIFY
                    for (j in op.j1 until op.j2) rightDiff[j] = Diff.MODIFY
                }
                DiffOp.DELETE -> for (i in op.i1 until op.i2) leftDiff[i] = Diff.REMOVE
                DiffOp.INSERT -> for (j in op.j1 until op.j2) rightDiff[j] = Diff.ADD
                DiffOp.EQUAL -> Unit
            }
        }
        return leftDiff to rightDiff
    }
}
