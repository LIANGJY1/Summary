package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.floor

class QuestionReorderTest {

    /** 构造一段「已组合」的槽位窗口：首项位次 firstIndex 起共 count 个，列表已滚动 scroll 像素。 */
    private fun window(
        firstIndex: Int,
        count: Int,
        scroll: Float,
        pitch: Float = 100f,
        height: Float = 94f,
    ): List<ReorderSlot> = (0 until count).map { i ->
        ReorderSlot(index = firstIndex + i, offset = i * pitch - scroll, size = height.toInt())
    }

    @Test
    fun `stays put while the pointer stays inside the current slot`() {
        val slots = window(firstIndex = 17, count = 6, scroll = 0f)
        val current = 19

        val target = QuestionReorder.resolveTargetIndex(
            current = current,
            grabY = slots.first { it.index == current }.offset + 20f,
            slots = slots,
            total = 26,
            hysteresis = 3f,
        )

        assertEquals(current, target)
    }

    @Test
    fun `moves down one slot after crossing the midpoint`() {
        val slots = window(firstIndex = 17, count = 6, scroll = 0f)
        val top = slots.first { it.index == 19 }.offset

        val target = QuestionReorder.resolveTargetIndex(
            current = 19,
            grabY = top + 60f,
            slots = slots,
            total = 26,
            hysteresis = 3f,
        )

        assertEquals(20, target)
    }

    @Test
    fun `moves up one slot after crossing the midpoint`() {
        val slots = window(firstIndex = 17, count = 6, scroll = 0f)
        val top = slots.first { it.index == 20 }.offset

        val target = QuestionReorder.resolveTargetIndex(
            current = 20,
            grabY = (top + slots.first { it.index == 19 }.offset) / 2f - 10f,
            slots = slots,
            total = 26,
            hysteresis = 3f,
        )

        assertEquals(19, target)
    }

    @Test
    fun `crossing a boundary down does not immediately flip back`() {
        val slots = window(firstIndex = 17, count = 6, scroll = 0f)
        val grabY = slots.first { it.index == 19 }.offset + 60f

        val down = QuestionReorder.resolveTargetIndex(19, grabY, slots, 26, 3f)
        // 指针没动，用换位后的位次再算一次：上下换位线对称，必须稳在 down 而不是弹回 19
        val again = QuestionReorder.resolveTargetIndex(down, grabY, slots, 26, 3f)

        assertEquals(20, down)
        assertEquals(20, again)
    }

    @Test
    fun `moves many slots in one call when the pointer jumps far`() {
        val slots = window(firstIndex = 17, count = 8, scroll = 0f)

        val target = QuestionReorder.resolveTargetIndex(
            current = 17,
            grabY = 640f,
            slots = slots,
            total = 26,
            hysteresis = 3f,
        )

        // 只能落到已组合窗口内；窗口外由边缘自动滚动继续推进
        assertEquals(23, target)
    }

    @Test
    fun `clamps at the first position`() {
        val slots = window(firstIndex = 0, count = 6, scroll = 0f)

        assertEquals(0, QuestionReorder.resolveTargetIndex(3, -500f, slots, 26, 3f))
    }

    @Test
    fun `clamps at the last position`() {
        val slots = window(firstIndex = 20, count = 6, scroll = 0f)

        assertEquals(25, QuestionReorder.resolveTargetIndex(23, 5_000f, slots, 26, 3f))
    }

    @Test
    fun `derives the target from visible siblings when the dragged slot is not composed`() {
        val slots = window(firstIndex = 0, count = 6, scroll = 0f)

        // 位次 21 不在组合窗口内：视口内中线在指针之前的有 0/1/2 三张，落点即 3
        assertEquals(3, QuestionReorder.resolveTargetIndex(21, 300f, slots, 26, 3f))
    }

    @Test
    fun `walks an off screen target toward the top instead of deadlocking`() {
        val slots = window(firstIndex = 0, count = 6, scroll = 0f)

        // 防死锁回归：被拖卡片（21）不可见时，指针一路往上走，落点必须单调递减到 0。
        // 早先实现在这里冻结位次，结果是用户越想把它拖出视口，它越被钉死在原位。
        val walk = listOf(500f, 400f, 300f, 200f, 100f, 0f).map {
            QuestionReorder.resolveTargetIndex(21, it, slots, 26, 3f)
        }

        assertEquals(listOf(5, 4, 3, 2, 1, 0), walk)
    }

    @Test
    fun `off screen target is invariant when the pointer travels with the content`() {
        val total = 26
        // 列表滚动 200px 的同时指针也跟着内容上移 200px，落点应保持不变
        val before = QuestionReorder.resolveTargetIndex(21, 300f, window(0, 6, 0f), total, 3f)
        val after = QuestionReorder.resolveTargetIndex(21, 100f, window(0, 6, 200f), total, 3f)

        assertEquals(3, before)
        assertEquals(before, after)
    }

    @Test
    fun `maps the pointer to an absolute list index through content space`() {
        // 独立标尺：把指针换算到内容坐标再按行距取整，不复用被测函数的任何中间量。
        // 滚动只平移槽位，不改变「指针落在内容第几格」，所以各滚动量下这条等式都应成立。
        // 滚动量取值受组合窗口约束：指针必须仍落在窗口内（17+7=24 是最后一个可用槽位）。
        listOf(0f, 200f, 400f).forEach { scroll ->
            val slots = window(firstIndex = 17, count = 8, scroll = scroll)

            val target = QuestionReorder.resolveTargetIndex(20, 260f, slots, 26, 3f)

            val contentY = 260f + scroll
            assertEquals(17 + floor((contentY + 50f) / 100f).toInt(), target, "scroll=$scroll")
        }
    }

    @Test
    fun `uses absolute list indices when the viewport is deep into the list`() {
        val slots = window(firstIndex = 17, count = 8, scroll = 0f)

        val target = QuestionReorder.resolveTargetIndex(17, 80f, slots, 26, 3f)

        // 视口顶端就是 17 号槽，指针越过它的换位线应下移一格——而不是掉回 0/1 号
        assertEquals(18, target)
    }

    @Test
    fun `hysteresis suppresses a move while the pointer rests on the boundary`() {
        val slots = window(firstIndex = 10, count = 6, scroll = 0f)
        val top = slots.first { it.index == 12 }.offset
        val boundary = (top + slots.first { it.index == 13 }.offset) / 2f

        // 指针正好压在中点线上：两个方向都不应换位
        assertEquals(12, QuestionReorder.resolveTargetIndex(12, boundary, slots, 26, 3f))
        // 明确越过滞回带才换位
        assertEquals(13, QuestionReorder.resolveTargetIndex(12, boundary + 10f, slots, 26, 3f))
        assertEquals(12, QuestionReorder.resolveTargetIndex(13, boundary - 10f, slots, 26, 3f))
    }

    @Test
    fun `round trip through uneven heights returns to the starting index`() {
        val heights = listOf(94, 130, 80, 150, 94, 130, 80, 150)
        val gap = 6f
        val offsets = mutableListOf(0f)
        heights.drop(1).forEach { offsets += offsets.last() + heights[offsets.size - 1] + gap }
        val slots = heights.mapIndexed { i, h -> ReorderSlot(index = 14 + i, offset = offsets[i], size = h) }
        val start = 16
        val startTop = slots.first { it.index == start }.offset

        val deep = QuestionReorder.resolveTargetIndex(start, startTop + 700f, slots, 26, 3f)
        val returned = QuestionReorder.resolveTargetIndex(deep, startTop + 5f, slots, 26, 3f)

        assertEquals(21, deep)
        assertEquals(start, returned)
    }
}
