package atlas.core

/**
 * 零依赖文本 diff，题库「未提交改动」行内着色用。
 *
 * 算法：先裁掉公共前后缀，再对小规模中段做逐字符/逐行 LCS 求差异；
 * 中段超过 [lcsLimit] 时不再精算，整体视为变化，避免 O(n·m) 内存放大
 * （题面/答案都是小文本，正常永远走不到回退分支）。
 */
object TextDiff {

    /** 比较新旧文本，返回 [new] 中发生变化（改写/新增）的字符区间（已合并相邻区间） */
    fun changedRangesInNew(old: String, new: String, lcsLimit: Int = 800): List<IntRange> {
        if (old == new || new.isEmpty()) return emptyList()
        var start = 0
        val minLen = minOf(old.length, new.length)
        while (start < minLen && old[start] == new[start]) start++
        var endOld = old.length
        var endNew = new.length
        while (endOld > start && endNew > start && old[endOld - 1] == new[endNew - 1]) { endOld--; endNew-- }
        if (endNew <= start) return emptyList()
        val midOldLen = endOld - start
        val midNewLen = endNew - start
        if (midOldLen == 0 || midOldLen > lcsLimit || midNewLen > lcsLimit) {
            // 纯插入或超大改写：中段整体标色（删除不产生 new 侧区间，无可标内容）
            return listOf(start until endNew)
        }
        val flags = unmatchedInNew(old.substring(start, endOld), new.substring(start, endNew))
        return mergeRuns(flags, offset = start)
    }

    /** 行级比较，返回 [new] 按 lines() 拆分后发生变化的行下标集合 */
    fun changedLinesInNew(old: String, new: String, lcsLimit: Int = 400): Set<Int> {
        if (old == new) return emptySet()
        val oldLines = old.lines()
        val newLines = new.lines()
        var start = 0
        val minLen = minOf(oldLines.size, newLines.size)
        while (start < minLen && oldLines[start] == newLines[start]) start++
        var endOld = oldLines.size
        var endNew = newLines.size
        while (endOld > start && endNew > start && oldLines[endOld - 1] == newLines[endNew - 1]) { endOld--; endNew-- }
        if (endNew <= start) return emptySet()
        val midOld = oldLines.subList(start, endOld)
        val midNew = newLines.subList(start, endNew)
        if (midOld.isEmpty() || midOld.size > lcsLimit || midNew.size > lcsLimit) {
            return (start until endNew).toSet()
        }
        val dp = Array(midOld.size + 1) { ShortArray(midNew.size + 1) }
        for (i in midOld.size - 1 downTo 0) for (j in midNew.size - 1 downTo 0) {
            dp[i][j] = if (midOld[i] == midNew[j]) (dp[i + 1][j + 1] + 1).toShort() else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val dirty = HashSet<Int>()
        var i = 0
        var j = 0
        while (i < midOld.size && j < midNew.size) {
            when {
                midOld[i] == midNew[j] -> { i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> { dirty.add(start + j); j++ }
            }
        }
        while (j < midNew.size) { dirty.add(start + j); j++ }
        return dirty
    }

    /** a、b 均不超过 lcsLimit：返回 b 中每个位置是否不在与 a 的 LCS 匹配里（即变化/新增） */
    private fun unmatchedInNew(a: String, b: String): BooleanArray {
        val dp = Array(a.length + 1) { ShortArray(b.length + 1) }
        for (i in a.length - 1 downTo 0) for (j in b.length - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) (dp[i + 1][j + 1] + 1).toShort() else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val flags = BooleanArray(b.length)
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            when {
                a[i] == b[j] -> { i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> { flags[j] = true; j++ }
            }
        }
        while (j < b.length) flags[j++] = true
        return flags
    }

    private fun mergeRuns(flags: BooleanArray, offset: Int): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < flags.size) {
            if (flags[i]) {
                val from = i
                while (i < flags.size && flags[i]) i++
                ranges += (offset + from)..(offset + i - 1)
            } else i++
        }
        return ranges
    }
}
