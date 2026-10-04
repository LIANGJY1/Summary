package atlas.core

/** knowledge-base 的目录树模型；全部子目录可见，题目源文档与关联 README 作为文件叶节点。 */
data class KnowledgeTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<KnowledgeTreeNode> = emptyList(),
)

object KnowledgeTree {
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

        fun freeze(node: MutableNode): KnowledgeTreeNode = KnowledgeTreeNode(
            name = node.name,
            path = node.path,
            isDirectory = node.isDirectory,
            children = node.children.values
                .sortedWith(compareBy<MutableNode> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
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
