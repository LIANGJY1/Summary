package atlas.core

/** knowledge-base 文档的目录树模型；只从可产生题目的 Markdown 文件路径构建。 */
data class KnowledgeTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<KnowledgeTreeNode> = emptyList(),
)

object KnowledgeTree {
    fun build(paths: List<String>): KnowledgeTreeNode {
        val normalized = paths.map { it.replace('\\', '/').trim('/') }
            .filter { it.isNotBlank() }.distinct()
        val rootName = normalized.firstOrNull()?.substringBefore('/') ?: "knowledge-base"

        class MutableNode(val name: String, val path: String, val isDirectory: Boolean) {
            val children = linkedMapOf<String, MutableNode>()
        }

        val root = MutableNode(rootName, rootName, true)
        normalized.forEach { path ->
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return@forEach
            val relativeParts = if (parts.first() == rootName) parts.drop(1) else parts
            var current = root
            relativeParts.forEachIndexed { index, part ->
                val childPath = "${current.path}/$part"
                val directory = index < relativeParts.lastIndex
                current = current.children.getOrPut(part) { MutableNode(part, childPath, directory) }
            }
        }

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
