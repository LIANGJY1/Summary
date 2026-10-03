package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionStatusTest {

    private val path = "knowledge-base/language/kotlin/01-syntax-basics.md"

    private fun parseOne(document: String): SourceQuestions.Entry =
        SourceQuestions.parse(path, document).single()

    @Test
    fun `Q 行的方括号前缀解析为完成状态且不进题面`() {
        val entry = parseOne(
            """
            **Q1: [done] 为什么要使用 until？**

            闭区间。
            """.trimIndent(),
        )

        assertEquals(QuestionStatus.DONE, entry.status)
        assertEquals("为什么要使用 until？", entry.question)
    }

    @Test
    fun `没有前缀即 todo`() {
        val entry = parseOne(
            """
            **Q1: 为什么要使用 until？**

            闭区间。
            """.trimIndent(),
        )

        assertEquals(QuestionStatus.TODO, entry.status)
        assertEquals("为什么要使用 until？", entry.question)
    }

    @Test
    fun `状态键大小写不敏感`() {
        val entry = parseOne(
            """
            **Q1: [DONE] 为什么要使用 until？**

            闭区间。
            """.trimIndent(),
        )

        assertEquals(QuestionStatus.DONE, entry.status)
        assertEquals("为什么要使用 until？", entry.question)
    }

    @Test
    fun `题面里的未知方括号不当作状态剥掉`() {
        val entry = parseOne(
            """
            **Q1: [1] 为什么 [注意] 要用 until？**

            闭区间。
            """.trimIndent(),
        )

        assertEquals(QuestionStatus.TODO, entry.status)
        assertEquals("[1] 为什么 [注意] 要用 until？", entry.question)
    }

    @Test
    fun `前缀后可以有空格也可以没有`() {
        val tight = parseOne(
            """
            **Q1: [learning]until 的语义？**

            闭区间。
            """.trimIndent(),
        )
        val loose = parseOne(
            """
            **Q1: [learning]   until 的语义？**

            闭区间。
            """.trimIndent(),
        )

        assertEquals("until 的语义？", tight.question)
        assertEquals("until 的语义？", loose.question)
        assertEquals(QuestionStatus.LEARNING, tight.status)
        assertEquals(QuestionStatus.LEARNING, loose.status)
    }

    @Test
    fun `标题式与裸 Q 行同样支持状态前缀`() {
        val document = """
            ## Q1: [done] 标题式问题？

            答案一。

            Q2: [learning] 裸问题？

            答案二。
        """.trimIndent()

        val entries = SourceQuestions.parse(path, document)

        assertEquals(QuestionStatus.DONE, entries[0].status)
        assertEquals("标题式问题？", entries[0].question)
        assertEquals(QuestionStatus.LEARNING, entries[1].status)
        assertEquals("裸问题？", entries[1].question)
    }

    @Test
    fun `代码块里的 Q 行不解析因而不产生状态`() {
        val entry = parseOne(
            """
            **Q1: 真题？**

            ```kotlin
            **Q2: [done] 其实是代码里的注释**
            ```
            """.trimIndent(),
        )

        assertEquals(1, entry.number)
        assertEquals(QuestionStatus.TODO, entry.status)
        assertTrue(entry.answer.contains("[done]"))
    }

    @Test
    fun `replace 写回 done 后能重新解析出同一状态`() {
        val document = """
            **Q1: 为什么要使用 until？**

            闭区间。

            **Q2: Int 除法为什么会丢失精度？**

            仍然是 Int。
        """.trimIndent()
        val target = SourceQuestions.parse(path, document)[1]

        val updated = SourceQuestions.replace(target, target.question, target.answer, QuestionStatus.DONE)

        assertTrue(updated.contains("**Q2: [done] Int 除法为什么会丢失精度？**"))
        val reparsed = SourceQuestions.parse(path, updated)[1]
        assertEquals(QuestionStatus.DONE, reparsed.status)
        assertEquals("Int 除法为什么会丢失精度？", reparsed.question)
    }

    @Test
    fun `replace 选 todo 时不写标记`() {
        val document = """
            **Q1: [done] 为什么要使用 until？**

            闭区间。
        """.trimIndent()
        val target = SourceQuestions.parse(path, document).single()

        val updated = SourceQuestions.replace(target, target.question, target.answer, QuestionStatus.TODO)

        assertEquals("**Q1: 为什么要使用 until？**", updated.lines().first())
        assertEquals(QuestionStatus.TODO, SourceQuestions.parse(path, updated).single().status)
    }

    @Test
    fun `只改状态不动题面时答案原样保留`() {
        val document = """
            **Q1: 为什么要使用 until？**

            ### 例子
            ```kotlin
            0 until size
            ```
        """.trimIndent()
        val target = SourceQuestions.parse(path, document).single()

        val updated = SourceQuestions.replace(target, target.question, target.answer, QuestionStatus.LEARNING)

        val reparsed = SourceQuestions.parse(path, updated).single()
        assertEquals(QuestionStatus.LEARNING, reparsed.status)
        assertTrue(reparsed.answer.contains("0 until size"))
    }

    @Test
    fun `改状态不改变题目身份`() {
        val document = """
            **Q1: 为什么要使用 until？**

            闭区间。
        """.trimIndent()
        val target = SourceQuestions.parse(path, document).single()

        val updated = SourceQuestions.replace(target, target.question, target.answer, QuestionStatus.DONE)
        val reparsed = SourceQuestions.parse(path, updated).single()

        assertEquals(target.id, reparsed.id)
    }

    @Test
    fun `重排保留状态标记`() {
        val document = """
            **Q1: [done] 第一题？**

            答案一。

            **Q2: [learning] 第二题？**

            答案二。

            **Q3: 第三题？**

            答案三。
        """.trimIndent()
        val entries = SourceQuestions.parse(path, document)

        val updated = SourceQuestions.reorderEntries(document, entries, fromIndex = 0, toIndex = 2)!!

        val reparsed = SourceQuestions.parse(path, updated)
        assertEquals(listOf(QuestionStatus.LEARNING, QuestionStatus.TODO, QuestionStatus.DONE), reparsed.map { it.status })
        assertEquals(listOf("第二题？", "第三题？", "第一题？"), reparsed.map { it.question })
    }

    @Test
    fun `删除题目后其余题目的状态仍正确`() {
        val document = """
            **Q1: [done] 第一题？**

            答案一。

            **Q2: [learning] 第二题？**

            答案二。
        """.trimIndent()
        val entries = SourceQuestions.parse(path, document)

        val updated = SourceQuestions.remove(document, entries[0])!!

        val reparsed = SourceQuestions.parse(path, updated).single()
        assertEquals(1, reparsed.number)
        assertEquals(QuestionStatus.LEARNING, reparsed.status)
        assertEquals("第二题？", reparsed.question)
    }

    @Test
    fun `fromKey 只认已知状态键`() {
        assertEquals(QuestionStatus.DONE, QuestionStatus.fromKey("done"))
        assertEquals(QuestionStatus.LEARNING, QuestionStatus.fromKey(" Learning "))
        assertNull(QuestionStatus.fromKey("已稳定"))
        assertNull(QuestionStatus.fromKey("1"))
    }

    @Test
    fun `markerPrefix 默认态为空`() {
        assertEquals("", QuestionStatus.TODO.markerPrefix())
        assertEquals("[done] ", QuestionStatus.DONE.markerPrefix())
        assertEquals("[learning] ", QuestionStatus.LEARNING.markerPrefix())
    }

    @Test
    fun `状态前缀不影响章节切段`() {
        val document = """
            **Q1: [done] 第一题？**

            答案一。

            第 1 章 其他内容

            Q2: [learning] 第二题？

            答案二。
        """.trimIndent()

        val entries = SourceQuestions.parse(path, document)

        assertEquals(2, entries.size)
        assertEquals(QuestionStatus.DONE, entries[0].status)
        assertFalse(entries[0].answer.contains("其他内容"))
        assertEquals(QuestionStatus.LEARNING, entries[1].status)
    }
}
