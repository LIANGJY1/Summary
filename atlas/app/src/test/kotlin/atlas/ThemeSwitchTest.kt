package atlas

import atlas.core.AppSettings
import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.createCustomTheme
import atlas.ui.hexOf
import atlas.ui.parseHexColor
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test

class ThemeSwitchTest {
    @Test
    fun `深浅切换解析 Atlas 对应模式`() {
        assertEquals(AtlasThemes.ATLAS.light, resolveTheme(AppSettings(theme = "light", themeName = "Atlas")))
        assertEquals(AtlasThemes.ATLAS.dark, resolveTheme(AppSettings(theme = "dark", themeName = "Atlas")))
        assertEquals(AtlasThemes.BLACK.light, resolveTheme(AppSettings(theme = "light", themeName = AtlasThemes.BLACK_NAME)))
        assertEquals(AtlasThemes.BLACK.dark, resolveTheme(AppSettings(theme = "dark", themeName = AtlasThemes.BLACK_NAME)))
        assertEquals(AtlasThemes.SNOW.light, resolveTheme(AppSettings(theme = "light", themeName = AtlasThemes.SNOW_NAME)))
        assertEquals(AtlasThemes.SNOW.dark, resolveTheme(AppSettings(theme = "dark", themeName = AtlasThemes.SNOW_NAME)))
    }

    @Test
    fun `同名自定义主题优先于内置 Atlas`() {
        val custom = CustomTheme(
            AtlasThemes.NAME,
            AtlasThemes.ATLAS.light.copy(accent = parseHexColor("#FF704B8F")!!),
            AtlasThemes.ATLAS.dark.copy(accent = parseHexColor("#FFF5A97F")!!),
        )
        val resolved = resolveTheme(AppSettings(theme = "dark", themeName = "Atlas", customThemes = listOf(custom.encode())))
        assertEquals(custom.dark, resolved)
    }

    @Test
    fun `黑曜内置选择保留而未知主题回退 Atlas且合法旧主题升级为 V2`() {
        val legacy = "markdown|" + listOf(
            "#FF8AADF4", "#FFA6DA95", "#FFF5A97F", "#FFED8796", "#FFA5ADCB", "#FF1E2030",
            "#FFC6A0F6", "#FF24273A", "#FF1E2030", "#FF181926", "#FFCAD3F5", "#FF5B6078",
        ).joinToString("|")
        val migrated = migrateThemeSettings(AppSettings(themeName = "Nord", customThemes = listOf(legacy, "broken")))
        assertEquals("Atlas", migrated.themeName)
        assertEquals(1, migrated.customThemes.size)
        assertEquals(true, migrated.customThemes.single().startsWith("v2|markdown|"))
        assertEquals(AtlasThemes.BLACK_NAME, migrateThemeSettings(AppSettings(themeName = AtlasThemes.BLACK_NAME)).themeName)
    }

    @Test
    fun `基于 Atlas 新建会复制独立双模式`() {
        val created = createCustomTheme("我的主题")
        val changed = created.copy(dark = created.dark.copy(accent = parseHexColor("#FF112233")!!))
        assertEquals(AtlasThemes.ATLAS.light.hexList(), changed.light.hexList())
        assertNotEquals(created.dark.hexList(), changed.dark.hexList())
        assertEquals("#FF112233", hexOf(changed.dark.accent))
    }
}
