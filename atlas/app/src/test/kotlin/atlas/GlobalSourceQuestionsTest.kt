package atlas

import atlas.core.SourceQuestions
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSourceQuestionsTest {
    @Test
    fun `parses questions from every markdown document`() {
        val entries = SourceQuestions.parseAll(
            listOf(
                "knowledge-base/android/a.md" to "**Q1: Android 问题？**\n\n答案 A",
                "knowledge-base/language/b.md" to "# b\n\nQ2: Kotlin 问题？\n\n答案 B",
            ),
        )

        assertEquals(listOf("Android 问题？", "Kotlin 问题？"), entries.map { it.question })
        assertEquals(listOf("knowledge-base/android/a.md", "knowledge-base/language/b.md"), entries.map { it.sourcePath })
    }
}
