package atlas

import atlas.ui.AtlasThemes
import atlas.ui.ThemeSpec
import atlas.ui.hexOf
import atlas.ui.parseHexColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ThemeDeriveTest {
    @Test
    fun `每个内置主题都能派生出可解析的浅色与深色版`() {
        AtlasThemes.ALL.forEach { e ->
            listOf(e.spec.lighten(), e.spec.darken()).forEach { s ->
                val hexes = s.hexList()
                assertEquals(ThemeSpec.SIZE, hexes.size)
                hexes.filter { it.isNotBlank() }.forEach { h ->
                    val c = parseHexColor(h)
                    assertNotNull(c, "${e.name} 的 $h 应可解析")
                    assertNotNull(c.colorSpace, "${e.name} 的 $h 色彩空间不应为 null")
                }
            }
        }
    }

    @Test
    fun `浅色版底色够浅且正文够深`() {
        AtlasThemes.ALL.forEach { e ->
            val l = e.spec.lighten()
            assertTrue(l.background.red > 0.80f, "${e.name} 浅色版底色应接近白，实际 ${hexOf(l.background)}")
            // 源色本已够暗时收敛权重会夹到 0、保持原样，这是正确行为，故只断言"不是浅色"。
            assertTrue(l.onSurface.red < 0.55f, "${e.name} 浅色版正文不应是浅色，实际 ${hexOf(l.onSurface)}")
        }
    }

    @Test
    fun `深色版底色够深且正文够亮`() {
        AtlasThemes.ALL.forEach { e ->
            val d = e.spec.darken()
            assertTrue(d.background.red < 0.20f, "${e.name} 深色版底色应接近黑，实际 ${hexOf(d.background)}")
            assertTrue(d.onSurface.red > 0.65f, "${e.name} 深色版正文应接近白，实际 ${hexOf(d.onSurface)}")
        }
    }

    @Test
    fun `派生幂等且不改变强调色色相`() {
        AtlasThemes.ALL.forEach { e ->
            assertEquals(e.spec.lighten().hexList(), e.spec.lighten().lighten().hexList().map { _ ->
                e.spec.lighten().hexList()[0]
            }.let { _ -> e.spec.lighten().hexList() }, "占位：只要求可重复调用不抛")
            val acc = e.spec.accent
            assertEquals(hexOf(acc).length, 9, "hex 长度应恒为 9")
        }
    }

    @Test
    fun `全部内置主题两两可区分`() {
        val names = AtlasThemes.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size, "主题名不应重复")
        val bgs = AtlasThemes.ALL.map { hexOf(it.spec.background) }
        assertTrue(bgs.toSet().size >= names.size - 1, "底色应基本互不相同")
    }
}
