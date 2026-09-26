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

    @Test
    fun `零散小改动保留字符级区间`() {
        val ranges = TextDiff.changedRangesInNew("旧题面？", "新题面？")
        assertEquals(listOf(0..0), TextDiff.coalesceForHighlight(ranges, "新题面？".length), "只改一个字时行内区间仍有信息量")
    }

    @Test
    fun `区间过多退化为整段着色`() {
        // 真实场景：20-SELinux.md 整份重写后 Q4「作为framework开发者…」碎成 6 段单双字交替
        val ranges = listOf(0..3, 5..5, 7..7, 9..17, 20..20, 23..29)
        assertEquals(listOf(0 until 30), TextDiff.coalesceForHighlight(ranges, 30))
    }

    @Test
    fun `覆盖过高退化为整段着色`() {
        // 真实场景：Q7「allow 规则到底该写在哪个文件？…」只有 2 段，但覆盖 37/46
        val ranges = listOf(6..18, 21..44)
        assertEquals(listOf(0 until 46), TextDiff.coalesceForHighlight(ranges, 46))
    }

    @Test
    fun `整份重写后的题面全部退化为整段`() {
        val question = "作为framework开发者，SELinux的实际应用场景都是什么？"
        val ranges = TextDiff.changedRangesInNew("作为应用层开发者，SELinux都有哪些实际使用场景？", question)
        assertEquals(listOf(0 until question.length), TextDiff.coalesceForHighlight(ranges, question.length))
    }

    @Test
    fun `无差异与零长度文本不产生着色区间`() {
        assertEquals(emptyList(), TextDiff.coalesceForHighlight(emptyList(), 10))
        assertEquals(emptyList(), TextDiff.coalesceForHighlight(listOf(0..3), 0))
    }

    @Test
    fun `越界区间按文本长度裁剪后再统计覆盖`() {
        assertEquals(listOf(0..4), TextDiff.coalesceForHighlight(listOf(0..99), 5), "越界区间裁到文本末尾，覆盖达满则整段")
    }
}
