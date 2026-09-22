package atlas.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTableTest {
    @Test
    fun `parses gfm table header and rows`() {
        val table = parseMarkdownTable(
            listOf(
                "| 类型 | 语法 |",
                "| --- | --- |",
                "| 普通参数 | 显式 this |",
                "| 隐式参数 | 接收者 |",
            ),
            0,
        )

        requireNotNull(table)
        assertEquals(listOf("类型", "语法"), table.headers)
        assertEquals(listOf("普通参数", "显式 this"), table.rows[0])
        assertEquals(4, table.endExclusive)
    }

    @Test
    fun `normalizes short rows to the header column count`() {
        val table = parseMarkdownTable(
            listOf(
                "| 项目 | 说明 |",
                "| --- | --- |",
                "| 只有一列 |",
            ),
            0,
        )

        requireNotNull(table)
        assertEquals(listOf("只有一列", ""), table.rows.single())
    }
}
