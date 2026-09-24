package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextDiffTest {

    @Test
    fun `相同或纯删除不产生 new 侧区间`() {
        assertEquals(emptyList(), TextDiff.changedRangesInNew("一样", "一样"))
        assertEquals(emptyList(), TextDiff.changedRangesInNew("旧答案很长的开头和结尾", "旧答案结尾"), "纯删除在 new 侧无可标内容")
        assertEquals(emptyList(), TextDiff.changedRangesInNew("旧", ""))
    }

    @Test
    fun `中间替换只标变化字符且保留公共前后缀`() {
        val ranges = TextDiff.changedRangesInNew("旧题面？", "新题面？")
        assertEquals(listOf(0..0), ranges, "只有第一个字变化")
        val inserted = TextDiff.changedRangesInNew("答案。", "答案，补充说明。")
        assertEquals(listOf(2..6), inserted, "标出插入的「，补充说明」；首部「答案」与尾部「。」是公共前后缀不标")
    }

    @Test
    fun `纯插入走整段标色分支`() {
        val ranges = TextDiff.changedRangesInNew("", "全新答案")
        assertEquals(listOf(0 until 4), ranges)
    }

    @Test
    fun `超过回退阈值时中段整体标色`() {
        val old = "旧" + "a".repeat(900) + "尾"
        val new = "新" + "b".repeat(900) + "尾"
        val ranges = TextDiff.changedRangesInNew(old, new, lcsLimit = 800)
        assertEquals(listOf(0 until 901), ranges, "中段 901 字符超过阈值，不再逐字精算；尾部公共「尾」不标")
    }

    @Test
    fun `行级 diff 只标记变化的行`() {
        val old = "第一段\n第二段\n第三段"
        val new = "第一段\n第二段改了\n第三段"
        assertEquals(setOf(1), TextDiff.changedLinesInNew(old, new))
        assertEquals(emptySet(), TextDiff.changedLinesInNew(new, new))
        assertEquals(setOf(0, 1, 2), TextDiff.changedLinesInNew("一\n二\n三", "1\n2\n3"), "全部行都变了")
        assertEquals(setOf(3), TextDiff.changedLinesInNew("一\n二\n三", "一\n二\n三\n四"), "尾部新增行单独标")
    }

    @Test
    fun `行级大文本回退整段标记`() {
        val old = (1..500).joinToString("\n") { "旧$it" }
        val new = (1..500).joinToString("\n") { "新$it" }
        assertEquals((0 until 500).toSet(), TextDiff.changedLinesInNew(old, new, lcsLimit = 400))
    }
}
