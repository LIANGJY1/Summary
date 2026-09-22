package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceQuestionReorderTest {
    @Test
    fun `reorders whole question blocks while keeping answers attached`() {
        val document = """标题

**Q1: 第一题**

第一题答案

**Q2: 第二题**

第二题答案
"""
        val entries = SourceQuestions.parse("knowledge-base/test.md", document, listOf("knowledge-base/test.md"))

        val reordered = SourceQuestions.reorderEntries(document, entries, fromIndex = 0, toIndex = 1)

        assertTrue(reordered != null)
        assertTrue(reordered!!.indexOf("**Q1: 第二题**") < reordered.indexOf("**Q2: 第一题**"))
        assertTrue(reordered.indexOf("第二题答案") < reordered.indexOf("**Q2: 第一题**"))
        assertTrue(reordered.indexOf("第一题答案") > reordered.indexOf("**Q2: 第一题**"))
    }

    @Test
    fun `does not reorder across non whitespace content`() {
        val document = """**Q1: 第一题**

答案一

第1章 第二部分

**Q2: 第二题**

答案二
"""
        val entries = SourceQuestions.parse("knowledge-base/test.md", document, listOf("knowledge-base/test.md"))

        assertEquals(null, SourceQuestions.reorderEntries(document, entries, fromIndex = 0, toIndex = 1))
    }
}
