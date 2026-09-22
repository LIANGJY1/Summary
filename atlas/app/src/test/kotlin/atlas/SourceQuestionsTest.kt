package atlas

import atlas.core.SourceQuestions
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SourceQuestionsTest {

    @Test
    fun `解析 Q 标记并把下一个 Q 之前的 markdown 作为答案`() {
        val text = """
            # 语法基础

            ## 目录
            - Q1～Q2

            **Q1: 为什么要使用 until？**

            `..` 是闭区间。

            ### 例子
            ```kotlin
            0 until size
            ```

            **Q2: Int 除法为什么会丢失精度？**

            两个 Int 相除仍然是 Int。
        """.trimIndent()

        val questions = SourceQuestions.parse("language/kotlin/01-语法基础.md", text)

        assertEquals(2, questions.size)
        assertEquals(1, questions[0].number)
        assertEquals("为什么要使用 until？", questions[0].question)
        assertTrue(questions[0].answer.contains("### 例子"))
        assertTrue(questions[0].answer.contains("0 until size"))
        assertEquals("Int 除法为什么会丢失精度？", questions[1].question)
        assertEquals("两个 Int 相除仍然是 Int。", questions[1].answer)
    }

    @Test
    fun `只识别目标文件并兼容中文冒号`() {
        val text = "**Q1：中文冒号问题？**\n\n答案。"

        assertTrue(SourceQuestions.isSupportedPath("knowledge-base/language/kotlin/01-语法基础.md"))
        assertTrue(!SourceQuestions.isSupportedPath("language/kotlin/02-对象与类型设计.md"))
        val question = SourceQuestions.parse("language/kotlin/01-语法基础.md", text).single()
        assertEquals("中文冒号问题？", question.question)
    }

    @Test
    fun `局部替换题目答案且保留其他文档内容`() {
        val text = """
            # 语法基础

            **Q1: 旧问题？**

            旧答案。

            **Q2: 第二题？**

            第二题答案。
        """.trimIndent()
        val original = SourceQuestions.parse("language/kotlin/01-语法基础.md", text)

        val updated = SourceQuestions.replace(original[0], "新问题？", "新答案。\n\n- 保留 Markdown")

        assertTrue(updated.contains("# 语法基础"))
        assertTrue(updated.contains("**Q1: 新问题？**"))
        assertTrue(updated.contains("新答案。\n\n- 保留 Markdown"))
        assertTrue(updated.contains("**Q2: 第二题？**\n\n第二题答案。"))
        assertTrue(!updated.contains("旧问题"))
        assertTrue(!updated.contains("旧答案"))
    }

    @Test
    fun `章节标题独立于上一题答案`() {
        val text = """
            **Q1: 第一题？**

            第一题答案。

            第 2 章 函数与集合

            **Q2: 第二题？**

            第二题答案。
        """.trimIndent()

        val questions = SourceQuestions.parse("language/kotlin/01-语法基础.md", text)
        val sections = SourceQuestions.parseSections("language/kotlin/01-语法基础.md", text)

        assertEquals("第一题答案。", questions[0].answer)
        assertEquals("函数与集合", sections.single().title)
        assertTrue(sections.single().startOffset >= questions[0].endOffset)
    }

    @Test
    fun `支持设置多个文档和目录规则`() {
        val configured = listOf("knowledge-base/language/java/", "knowledge-base/docs/面试.md")

        assertTrue(SourceQuestions.isSupportedPath("knowledge-base/language/java/01-基础.md", configured))
        assertTrue(SourceQuestions.isSupportedPath("knowledge-base/docs/面试.md", configured))
        assertTrue(!SourceQuestions.isSupportedPath("knowledge-base/language/kotlin/01-语法基础.md", configured))
    }
}
