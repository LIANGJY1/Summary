package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceQuestionCreationTest {
    @Test
    fun `parses one question per line and appends empty answers with continuous numbers`() {
        val drafts = SourceQuestions.parseBatch(
            """
            第一题？
            第二题？

            第三题？
            """.trimIndent(),
        )
        assertEquals(listOf("第一题？", "第二题？", "第三题？"), drafts.map { it.question })
        assertEquals(listOf("", "", ""), drafts.map { it.answer })

        val existing = "**Q3: 已有题目？**\n\n已有答案。\n"
        val updated = SourceQuestions.append(existing, drafts)
        assertTrue(updated.contains("**Q4: 第一题？**"))
        assertTrue(updated.contains("**Q4: 第一题？**\n\n"))
        assertTrue(updated.contains("**Q5: 第二题？**"))
        assertTrue(updated.contains("**Q6: 第三题？**"))
        assertTrue(!updated.contains("第一题？**\n\n答案"))
        assertTrue(updated.contains("已有题目？"))
    }

    @Test
    fun `appends a single question with an explicitly configured number`() {
        val existing = "**Q3: 已有题目？**\n\n已有答案。\n"

        val updated = SourceQuestions.append(
            existing,
            listOf(SourceQuestions.Draft("指定序号题目？", "", number = 12)),
        )

        assertTrue(updated.contains("**Q12: 指定序号题目？**"))
        assertTrue(!updated.contains("**Q4: 指定序号题目？**"))
    }

    @Test
    fun `inserts a question at an existing position and shifts later questions`() {
        val document = """**Q1: 第一题**

答案一

**Q2: 第二题**

答案二
"""

        val updated = SourceQuestions.insertAtNumber(
            document,
            SourceQuestions.Draft("插入题目？", "插入答案"),
            number = 2,
        )

        assertTrue(updated.indexOf("**Q1: 第一题**") < updated.indexOf("**Q2: 插入题目？**"))
        assertTrue(updated.indexOf("**Q2: 插入题目？**") < updated.indexOf("**Q3: 第二题**"))
        assertTrue(updated.indexOf("答案二") > updated.indexOf("**Q3: 第二题**"))
    }
}
