package atlas

import atlas.core.AppSettings
import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.Theme
import atlas.ui.ThemeSpec
import atlas.ui.hexOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 回归：点选内置主题曾经完全不生效——`resolveTheme` 只在自定义主题里按名查找，
 * 找不到就恒返回默认主题，界面看着"点了没反应"。这里锁死"换主题 = 换色"。
 */
class ThemeSwitchTest {

    @AfterEach
    fun reset() {
        Theme.apply(dark = true, spec = AtlasThemes.DEFAULT.spec.darken())
    }

    private fun resolve(s: AppSettings) = resolveTheme(s)

    private fun gruvboxDark() = AtlasThemes.ALL.first { it.name == "Gruvbox Dark" }.spec.darken()

    @Test
    fun `选中不同内置主题会解析出不同的配色`() {
        val specs = AtlasThemes.ALL.associate { e ->
            e.name to resolve(AppSettings(theme = "dark", themeName = e.name))
        }
        val hexes = specs.map { (_, spec) -> spec.hexList() }
        assertEquals(AtlasThemes.ALL.size, hexes.toSet().size, "每套内置主题都应解析出唯一配色")
        assertTrue(hexes.all { it.size == ThemeSpec.SIZE }, "应含 12 基色 + 6 markdown")
    }

    @Test
    fun `每个内置主题的强调色都真的被采用`() {
        AtlasThemes.ALL.forEach { e ->
            val resolved = resolve(AppSettings(theme = "dark", themeName = e.name))
            assertEquals(hexOf(AtlasThemes.specOf(e.name, true).accent), hexOf(resolved.accent), e.name)
            assertEquals(hexOf(AtlasThemes.specOf(e.name, true).background), hexOf(resolved.background), "${e.name} 深色底")
        }
    }

    @Test
    fun `深浅色切换会改变解析结果`() {
        val name = AtlasThemes.DEFAULT.name
        assertNotEquals(
            resolve(AppSettings(theme = "light", themeName = name)).hexList(),
            resolve(AppSettings(theme = "dark", themeName = name)).hexList(),
            "同一主题的深浅两版应不同",
        )
    }

    @Test
    fun `主题为空时回落到默认主题`() {
        assertEquals(
            AtlasThemes.specOf(AtlasThemes.DEFAULT.name, true).hexList(),
            resolve(AppSettings(theme = "dark")).hexList(),
        )
    }

    @Test
    fun `自定义主题优先于同名内置主题`() {
        val custom = CustomTheme(AtlasThemes.DEFAULT.name, gruvboxDark())
        val resolved = resolve(
            AppSettings(theme = "dark", themeName = custom.name, customThemes = listOf(custom.encode())),
        )
        assertEquals(custom.spec.hexList(), resolved.hexList(), "同名时应以自定义为准")
    }

    @Test
    fun `markdown 语义色在未覆盖时跟随主题`() {
        val mocha = AtlasThemes.DEFAULT.spec.darken()
        val gruv = gruvboxDark()
        Theme.apply(dark = true, spec = mocha)
        val mochaHeading = hexOf(Theme.MdHeading)
        val mochaBold = hexOf(Theme.MdBold)
        val mochaLink = hexOf(Theme.MdLink)
        val mochaQuote = hexOf(Theme.MdQuote)
        val mochaCode = hexOf(Theme.MdInlineCode)

        Theme.apply(dark = true, spec = gruv)
        assertNotEquals(mochaHeading, hexOf(Theme.MdHeading), "标题应随主题变")
        assertNotEquals(mochaBold, hexOf(Theme.MdBold), "加粗应随主题变")
        assertNotEquals(mochaLink, hexOf(Theme.MdLink), "链接应随主题变")
        assertNotEquals(mochaQuote, hexOf(Theme.MdQuote), "引用应随主题变")
        assertNotEquals(mochaCode, hexOf(Theme.MdInlineCode), "行内代码应随主题变")
        assertEquals(hexOf(gruv.accent), hexOf(Theme.MdHeading), "标题默认取主题强调色")
        assertEquals(hexOf(gruv.muted), hexOf(Theme.MdQuote), "引用默认取主题次要文字")
    }

    @Test
    fun `主题自带的 markdown 覆盖压过基色推导`() {
        val base = AtlasThemes.DEFAULT.spec.darken()
        val withMd = base.copy(
            mdHeading = atlas.ui.parseHexColor("#FF00FF00"),
            mdBold = atlas.ui.parseHexColor("#FF00FF00"),
        )
        Theme.apply(dark = true, spec = withMd)
        assertEquals("#FF00FF00", hexOf(Theme.MdHeading), "主题内的 markdown 覆盖应压过推导")
        assertEquals("#FF00FF00", hexOf(Theme.MdBold))
    }
}
