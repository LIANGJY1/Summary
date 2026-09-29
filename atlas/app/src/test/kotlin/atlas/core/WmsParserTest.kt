package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WmsParserTest {

    private val dumpsys = """
        ROOT
         ROOT
          Display 0{"Display 0" init=0x0 refCount=0 mDisplayId=0}
           DisplayContent{c0ffee displayId=0 rootTaskId=1}
            Task{abcd12 taskId=42 userId=0}
             ActivityRecord{9abcde u0 com.example.app/.MainActivity t42}
              Window{7e4e3b u0 com.example.app/com.example.app.MainActivity}

    """.trimIndent().lines()

    @Test
    fun `解析缩进树结构与深度`() {
        val root = WmsParser.parse(dumpsys)
        assertEquals(-1, root.indentLevel)
        assertEquals(1, root.children.size) // 顶层 ROOT 行
        val outer = root.children[0]
        assertEquals(1, outer.children.size) // 第二层 " ROOT"
        val display = outer.children[0].children[0]
        assertEquals("  Display 0{\"Display 0\" init=0x0 refCount=0 mDisplayId=0}", display.rawText) // rawText 保留缩进（python 同语义）
        assertEquals("Display 0{\"Display 0\" init=0x0 refCount=0 mDisplayId=0}", display.rawText.trim())
        assertEquals(2, display.depth)
        val displayContent = display.children[0]
        val task = displayContent.children[0]
        val activity = task.children[0]
        val window = activity.children[0]
        assertEquals(6, window.depth)
    }

    @Test
    fun `节点摘要按类型提取`() {
        val flat = WmsParser.flatten(WmsParser.parse(dumpsys))

        val task = flat.first { it.category == WmsParser.Category.TASK }
        assertEquals("Task: 42", task.shortName) // taskId= 提取
        val activity = flat.first { it.category == WmsParser.Category.ACTIVITY }
        assertEquals("Activity: t42", activity.shortName) // details 末段
        val window = flat.first { it.category == WmsParser.Category.WINDOW }
        assertEquals("Window{u0 com.example.app/com.example.app.MainActivity}", window.shortName)
        val displayContent = flat.first { it.category == WmsParser.Category.DISPLAY }
        assertEquals("DisplayContent: displayId=0", displayContent.shortName)
        // 带引号 display 名的行不匹配节点正则，回退首词、默认分类（与 python 版一致）
        val display = flat.first { it.rawText.trim().startsWith("Display 0{") }
        assertEquals("Display", display.shortName)
        assertEquals(WmsParser.Category.DEFAULT, display.category)
    }

    @Test
    fun `无正则命中的行回退首词与高亮语义`() {
        // '#' 开头的行带 name 属性
        val (short1, cat1) = WmsParser.classify("#0 4ccfef8 WallpaperWindowContainer name=\"wallpaper\"")
        assertEquals("#0 4ccfef8 (wallpaper)", short1)
        assertEquals(WmsParser.Category.DEFAULT, cat1)
        // ROOT 特判
        val (short2, cat2) = WmsParser.classify("ROOT")
        assertEquals("ROOT", short2)
        assertEquals(WmsParser.Category.ROOT, cat2)
    }

    @Test
    fun `flatten 为先序且不含根`() {
        val flat = WmsParser.flatten(WmsParser.parse(dumpsys))
        assertEquals(7, flat.size)
        assertTrue(flat.none { it.indentLevel == -1 })
        assertEquals("ROOT", flat[0].shortName) // 顶层 ROOT 行（非合成根）
    }

    @Test
    fun `diff 标记增删改`() {
        val a = listOf("Window{a}", "Task: 1", "Activity: x")
        val b = listOf("Window{a}", "Task: 2", "Activity: x", "Window{b}")
        val (left, right) = WmsParser.diff(a.map { WmsParser.Node(it, 0, 0, it, WmsParser.Category.DEFAULT) },
            b.map { WmsParser.Node(it, 0, 0, it, WmsParser.Category.DEFAULT) })
        assertEquals(listOf(WmsParser.Diff.NONE, WmsParser.Diff.MODIFY, WmsParser.Diff.NONE), left)
        assertEquals(listOf(WmsParser.Diff.NONE, WmsParser.Diff.MODIFY, WmsParser.Diff.NONE, WmsParser.Diff.ADD), right)
    }

    @Test
    fun `opcodes 覆盖纯删纯插与相等`() {
        assertEquals(
            listOf(WmsParser.DiffOpcode(WmsParser.DiffOp.EQUAL, 0, 1, 0, 1)),
            WmsParser.diffOpcodes(listOf("x"), listOf("x")),
        )
        assertEquals(
            listOf(WmsParser.DiffOpcode(WmsParser.DiffOp.DELETE, 0, 2, 0, 0)),
            WmsParser.diffOpcodes(listOf("a", "b"), emptyList()),
        )
        assertEquals(
            listOf(WmsParser.DiffOpcode(WmsParser.DiffOp.INSERT, 0, 0, 0, 2)),
            WmsParser.diffOpcodes(emptyList(), listOf("a", "b")),
        )
    }

    @Test
    fun `预置命令与 python 版一致且含管道命令`() {
        assertEquals(9, WmsParser.DEFAULT_COMMANDS.size)
        assertEquals("dumpsys window containers", WmsParser.DEFAULT_COMMANDS.first())
        assertTrue(WmsParser.DEFAULT_COMMANDS.last().contains("awk"))
    }
}
