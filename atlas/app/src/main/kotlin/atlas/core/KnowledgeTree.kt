package atlas.core

/** knowledge-base 的目录树模型；全部子目录可见，题目源文档与关联 README 作为文件叶节点。 */
data class KnowledgeTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<KnowledgeTreeNode> = emptyList(),
)

object KnowledgeTree {
    /** 搜索目录与文件名；保留命中节点的祖先，命中目录时保留其完整子树。 */
    fun filter(tree: KnowledgeTreeNode, query: String): KnowledgeTreeNode {
        val needle = query.trim()
        if (needle.isEmpty()) return tree

        fun prune(node: KnowledgeTreeNode): KnowledgeTreeNode? {
            // knowledge-base 是结构根节点，不参与搜索；否则输入常见字母会命中根并返回全树。
            val matches = node.path != tree.path && node.name.contains(needle, ignoreCase = true)
            if (matches && node.isDirectory) return node
            if (matches) return node
            val children = node.children.mapNotNull(::prune)
            return node.takeIf { children.isNotEmpty() }?.copy(children = children)
        }

        return prune(tree) ?: tree.copy(children = emptyList())
    }

    /** 只展开搜索命中项的祖先目录；命中目录本身及其后代保持可折叠，避免一次组合整棵子树。 */
    fun searchAncestorDirectoryPaths(tree: KnowledgeTreeNode, query: String): Set<String> = buildSet {
        val needle = query.trim()
        if (needle.isEmpty()) return@buildSet

        fun containsMatch(node: KnowledgeTreeNode): Boolean {
            if (node.path != tree.path && node.name.contains(needle, ignoreCase = true)) return true
            var childMatches = false
            node.children.forEach { child ->
                if (containsMatch(child)) childMatches = true
            }
            if (childMatches && node.isDirectory) add(node.path)
            return childMatches
        }

        containsMatch(tree)
    }

    fun safeRenameName(rawName: String, isDirectory: Boolean): String? {
        val name = rawName.trim()
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) return null
        return if (isDirectory || name.endsWith(".md", ignoreCase = true)) name else "$name.md"
    }

    fun renamedPath(path: String, newName: String, isDirectory: Boolean): String {
        val normalized = path.replace('\\', '/').trim('/')
        val parent = normalized.substringBeforeLast('/', "")
        val safeName = requireNotNull(safeRenameName(newName, isDirectory))
        return if (parent.isBlank()) safeName else "$parent/$safeName"
    }

    fun build(paths: List<String>, directories: List<String> = emptyList()): KnowledgeTreeNode {
        val normalized = paths.map { it.replace('\\', '/').trim('/') }
            .filter { it.isNotBlank() }.distinct()
        val normalizedDirectories = directories.map { it.replace('\\', '/').trim('/') }
            .filter { it.isNotBlank() }.distinct()
        val rootName = (normalized + normalizedDirectories).firstOrNull()?.substringBefore('/') ?: "knowledge-base"

        class MutableNode(val name: String, val path: String, var isDirectory: Boolean) {
            val children = linkedMapOf<String, MutableNode>()
        }

        val root = MutableNode(rootName, rootName, true)
        fun insert(path: String, directoryLeaf: Boolean) {
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return
            val relativeParts = if (parts.first() == rootName) parts.drop(1) else parts
            var current = root
            relativeParts.forEachIndexed { index, part ->
                val childPath = "${current.path}/$part"
                val isDirectory = index < relativeParts.lastIndex || directoryLeaf
                current = current.children.getOrPut(part) { MutableNode(part, childPath, isDirectory) }
                if (isDirectory) current.isDirectory = true
            }
        }
        normalizedDirectories.forEach { insert(it, directoryLeaf = true) }
        normalized.forEach { insert(it, directoryLeaf = false) }

        val numberedName = Regex("^\\d{2}-")
        fun freeze(node: MutableNode): KnowledgeTreeNode = KnowledgeTreeNode(
            name = node.name,
            path = node.path,
            isDirectory = node.isDirectory,
            children = node.children.values
                .sortedWith(compareBy<MutableNode> {
                    when {
                        numberedName.containsMatchIn(it.name) -> 0
                        it.isDirectory -> 1
                        else -> 2
                    }
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map(::freeze),
        )
        return freeze(root)
    }

    fun ancestorPaths(path: String): Set<String> {
        val parts = path.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return emptySet()
        return parts.dropLast(1).indices.mapTo(linkedSetOf()) { index ->
            parts.take(index + 1).joinToString("/")
        }
    }
}
