package atlas.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReviewCopyTest {

    @Test
    fun `review progress uses relative session language`() {
        assertEquals("本轮第 2 张 / 共 5 张", reviewProgressLabel(2, 5))
        assertFalse(reviewProgressLabel(2, 5).contains("今日"))
    }

    @Test
    fun `answer prompt explains both click and keyboard action`() {
        assertEquals("还没看到答案？点击按钮或按 Space 查看", answerPromptLabel())
    }

    @Test
    fun `deck label stays compact for the sidebar`() {
        assertEquals("article-quiz:性能优化…", compactDeckLabel("article-quiz:性能优化方法论", 18))
        assertEquals("Android", compactDeckLabel("Android", 18))
    }
}
