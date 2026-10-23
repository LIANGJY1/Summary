package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeTreeTest {

    @Test
    fun `builds nested knowledge base hierarchy`() {
        val tree = KnowledgeTree.build(
            listOf(
                "knowledge-base/language/kotlin/01-语法基础.md",
                "knowledge-base/language/java/泛型.md",
                "knowledge-base/README.md",
            ),
        )

        assertEquals("knowledge-base", tree.path)
        assertEquals(listOf("language", "README.md"), tree.children.map { it.name })
        val language = tree.children.first()
        assertTrue(language.isDirectory)
        assertEquals(listOf("java", "kotlin"), language.children.map { it.name })
        assertEquals(
            "knowledge-base/language/kotlin/01-语法基础.md",
            language.children.last().children.single().path,
        )
    }

    @Test
    fun `sorts directories before files at each level`() {
        val tree = KnowledgeTree.build(
            listOf(
                "knowledge-base/z.md",
                "knowledge-base/a-dir/item.md",
                "knowledge-base/a.md",
            ),
        )

        assertEquals(listOf("a-dir", "a.md", "z.md"), tree.children.map { it.name })
    }

    @Test
    fun `sorts numbered directories and files together by sequence`() {
        val tree = KnowledgeTree.build(
            listOf(
                "knowledge-base/07-能效.md",
                "knowledge-base/06-图片加载/01-格式与体积.md",
                "knowledge-base/02-渲染.md",
                "knowledge-base/01-启动.md",
                "knowledge-base/README.md",
            ),
        )

        assertEquals(
            listOf("01-启动.md", "02-渲染.md", "06-图片加载", "07-能效.md", "README.md"),
            tree.children.map { it.name },
        )
    }

    @Test
    fun `returns directory ancestors for a selected file`() {
        assertEquals(
            setOf("knowledge-base", "knowledge-base/language", "knowledge-base/language/kotlin"),
            KnowledgeTree.ancestorPaths("knowledge-base/language/kotlin/01-语法基础.md"),
        )
    }

    @Test
    fun `filters by node name while retaining matching file ancestors`() {
        val tree = KnowledgeTree.build(
            listOf(
                "knowledge-base/language/kotlin/01-语法基础.md",
                "knowledge-base/language/java/泛型.md",
                "knowledge-base/android/性能.md",
            ),
        )

        val filtered = KnowledgeTree.filter(tree, "语法")

        assertEquals("knowledge-base", filtered.path)
        assertEquals(listOf("language"), filtered.children.map { it.name })
        assertEquals(listOf("kotlin"), filtered.children.single().children.map { it.name })
        assertEquals(listOf("01-语法基础.md"), filtered.children.single().children.single().children.map { it.name })
        assertEquals(
            setOf("knowledge-base", "knowledge-base/language", "knowledge-base/language/kotlin"),
            KnowledgeTree.searchAncestorDirectoryPaths(tree, "语法"),
        )
        assertTrue(KnowledgeTree.filter(tree, "不存在").children.isEmpty())
    }

    @Test
    fun `matching directory keeps its descendants and blank query restores the tree`() {
        val tree = KnowledgeTree.build(
            listOf(
                "knowledge-base/language/kotlin/01-语法基础.md",
                "knowledge-base/language/java/泛型.md",
                "knowledge-base/android/性能.md",
            ),
        )

        val filtered = KnowledgeTree.filter(tree, "language")

        assertEquals(listOf("language"), filtered.children.map { it.name })
        assertEquals(listOf("java", "kotlin"), filtered.children.single().children.map { it.name })
        assertEquals(setOf("knowledge-base"), KnowledgeTree.searchAncestorDirectoryPaths(tree, "language"))
        assertEquals(tree, KnowledgeTree.filter(tree, "  "))
    }

    @Test
    fun `search does not treat the knowledge base root as a match`() {
        val tree = KnowledgeTree.build(
            listOf(
                "knowledge-base/android/item.md",
                "knowledge-base/data/item.md",
                "knowledge-base/index.md",
            ),
        )

        val filtered = KnowledgeTree.filter(tree, "a")

        assertEquals(listOf("android", "data"), filtered.children.map { it.name })
        assertEquals(setOf("knowledge-base"), KnowledgeTree.searchAncestorDirectoryPaths(tree, "a"))
        assertEquals(
            setOf("knowledge-base", "knowledge-base/android", "knowledge-base/data"),
            KnowledgeTree.searchAncestorDirectoryPaths(tree, "item.md"),
        )
    }
}
