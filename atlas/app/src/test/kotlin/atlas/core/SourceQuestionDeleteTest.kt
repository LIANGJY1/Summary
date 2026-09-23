package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceQuestionDeleteTest {
    private val path = "knowledge-base/test.md"

    private fun parse(document: String) = SourceQuestions.parse(path, document, listOf(path))

    @Test
    fun `removes middle question and renumbers the rest`() {
        val document = "标题\n\n**Q1: 第一题**\n\n第一题答案\n\n**Q2: 第二题**\n\n第二题答案\n\n**Q3: 第三题**\n\n第三题答案\n"
        val entries = parse(document)

        val updated = SourceQuestions.remove(document, entries[1])

        assertEquals(
            "标题\n\n**Q1: 第一题**\n\n第一题答案\n\n**Q2: 第三题**\n\n第三题答案\n",
            updated,
        )
    }

    @Test
    fun `removes last question without leaving trailing blank lines`() {
        val document = "**Q1: 第一题**\n\n答案一\n\n**Q2: 第二题**\n\n答案二\n"
        val entries = parse(document)

        val updated = SourceQuestions.remove(document, entries[1])

        assertEquals("**Q1: 第一题**\n\n答案一\n", updated)
    }

    @Test
    fun `removes first question and keeps the rest attached`() {
        val document = "**Q1: 甲**\n\n答案甲\n\n**Q2: 乙**\n\n答案乙\n"
        val entries = parse(document)

        val updated = SourceQuestions.remove(document, entries[0])

        assertEquals("**Q1: 乙**\n\n答案乙\n", updated)
    }

    @Test
    fun `keeps section headings and renumbers across sections`() {
        val document = "## 第一章\n\n**Q1: 甲**\n\n答案甲\n\n**Q2: 乙**\n\n答案乙\n\n## 第二章\n\n**Q3: 丙**\n\n答案丙\n"
        val entries = parse(document)

        val updated = SourceQuestions.remove(document, entries[0])

        assertEquals(
            "## 第一章\n\n**Q1: 乙**\n\n答案乙\n\n## 第二章\n\n**Q2: 丙**\n\n答案丙\n",
            updated,
        )
    }

    @Test
    fun `removing the only question keeps the preamble`() {
        val document = "前言\n\n**Q1: 唯一**\n\n答案\n"
        val entries = parse(document)

        val updated = SourceQuestions.remove(document, entries[0])

        assertEquals("前言\n", updated)
    }

    @Test
    fun `returns null when the question no longer matches the document`() {
        val document = "**Q1: 甲**\n\n答案甲\n\n**Q2: 乙**\n\n答案乙\n"
        val stale = parse(document)[0]
        val edited = document.replace("甲", "已改题面")

        assertNull(SourceQuestions.remove(edited, stale))
    }

    @Test
    fun `deleting one question keeps code fences inside another answer`() {
        val document = "**Q1: 甲**\n\n答案甲\n```\n**Q9: 代码块里的假题**\n```\n\n**Q2: 乙**\n\n答案乙\n"
        val entries = parse(document)
        assertEquals(2, entries.size)

        val updated = requireNotNull(SourceQuestions.remove(document, entries[1]))

        assertEquals("**Q1: 甲**\n\n答案甲\n```\n**Q9: 代码块里的假题**\n```\n", updated)
        assertFalse(updated.contains("**Q2: 乙**"))
    }
}
