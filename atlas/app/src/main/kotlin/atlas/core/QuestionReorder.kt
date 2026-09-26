package atlas.core

/**
 * 拖拽重排时单个槽位的视口几何。
 *
 * @param index 该槽位在当前预览顺序中的位次
 * @param offset 槽位顶边相对列表视口顶边的偏移（像素，可为负——表示已部分滚出视口上方）
 * @param size 槽位高度（像素）
 */
data class ReorderSlot(val index: Int, val offset: Float, val size: Int)

/**
 * 拖拽重排的位次计算。纯函数，不持有任何跨帧状态，因此可独立单测。
 *
 * 关键不变量：**所有几何都取自调用方传入的「本帧已组合」槽位**（Compose 侧即
 * `LazyListState.layoutInfo.visibleItemsInfo`）。列表滚动改变的只是槽位的 offset，
 * 而本函数每次调用都重新读取，不依赖任何缓存——所以滚动不会让目标位次失真。
 *
 * 反例（历史缺陷）：早先实现把每张卡片的 `positionInParent()` 缓存进 Map，滚动使被拖
 * 卡片滚出组合范围后其 `onGloballyPositioned` 停止触发，缓存值冻结在旧位置，于是目标
 * 位次每滚动一题就整体漂移一格。
 */
object QuestionReorder {

    /**
     * 求被拖项当前应落入的位次。
     *
     * 换位线取「当前槽位顶边与相邻槽位顶边的中点」，上下都用相邻槽位的**顶边**，两侧必须完全
     * 对称：跨越同一条缝时，下移用的线与上移用的线必须是同一个值，否则换下去立刻又换回来，
     * 循环在两个位次间来回弹跳直到步数上限。早先实现下行按自身行距、上行按相邻行距推导换位线，
     * 行高不均时两侧不对称，会提前连环换位并在下一帧弹回（表现为列表乱跳）；这里统一用实测
     * 顶边中点消除该不对称。
     *
     * @param current 被拖项在当前预览顺序中的位次
     * @param grabY 被拖卡片跟随指针后的顶边（视口坐标）
     * @param slots 本帧已组合的槽位，[ReorderSlot.index] 对应预览顺序
     * @param total 列表总项数
     * @param hysteresis 换位滞回量（像素），在换位线两侧留出一条死区，防指针压线时抖动
     * @return 目标位次，已钳制在 `0 until total`
     */
    fun resolveTargetIndex(
        current: Int,
        grabY: Float,
        slots: List<ReorderSlot>,
        total: Int,
        hysteresis: Float,
    ): Int {
        if (total <= 0) return 0
        val byIndex = slots.associateBy { it.index }
        val self = byIndex[current]
        // 被拖卡片滚出组合范围时无法锚定它的槽位，改用「视口内其余卡片」直接数出落点：
        // 视口上方的位次一定在指针之前，视口内中线在指针之前的每张卡片也各算一个。
        // 早先实现在这里直接冻结位次，可那样目标永远进不了可见窗口——用户越想把它拖出视口，
        // 它就越是被钉死在原处，形成死锁。
        if (self == null) return insertionIndexAmongVisible(current, grabY, slots, total, hysteresis)
        var target = current
        var top = self.offset
        var guard = 0
        while (guard++ < total) {
            val next = byIndex[target + 1]
            val prev = byIndex[target - 1]
            when {
                next != null && grabY > (top + next.offset) / 2f + hysteresis -> {
                    // 每步都从实测槽位重取 top，而不是按行距累加：累加会把行高误差和
                    // 滚动量一起攒进误差，步数越多偏得越远。
                    top = next.offset
                    target = next.index
                }

                prev != null && grabY < (top + prev.offset) / 2f - hysteresis -> {
                    top = prev.offset
                    target = prev.index
                }

                else -> break
            }
        }
        return target.coerceIn(0, total - 1)
    }

    /**
     * 被拖卡片不在组合范围内时的落点：数出「有多少张卡片排在指针之前」，那就是它的目标位次。
     *
     * 只有已组合的槽位参与计数——它们每帧都重新测量，滚动不会让结果失真；未组合的部分要么在视口
     * 上方（整段都在指针之前，按首项位次一次性计入），要么在视口下方（都在指针之后，不计）。
     */
    private fun insertionIndexAmongVisible(
        current: Int,
        grabY: Float,
        slots: List<ReorderSlot>,
        total: Int,
        hysteresis: Float,
    ): Int {
        val others = slots.filter { it.index != current }.sortedBy { it.index }
        if (others.isEmpty()) return current.coerceIn(0, total - 1)
        val above = others.count { grabY > it.offset + it.size / 2f + hysteresis }
        return (others.first().index + above).coerceIn(0, total - 1)
    }
}
