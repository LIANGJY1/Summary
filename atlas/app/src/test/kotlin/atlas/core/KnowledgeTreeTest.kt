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
    fun `returns directory ancestors for a selected file`() {
        assertEquals(
            setOf("knowledge-base", "knowledge-base/language", "knowledge-base/language/kotlin"),
            KnowledgeTree.ancestorPaths("knowledge-base/language/kotlin/01-语法基础.md"),
        )
    }
}
