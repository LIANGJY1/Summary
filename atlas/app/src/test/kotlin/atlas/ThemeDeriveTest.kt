package atlas

import atlas.ui.AtlasThemes
import atlas.ui.ThemeSpec
import atlas.ui.hexOf
import atlas.ui.parseHexColor
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

class ThemeDeriveTest {
    @Test
    fun `内置主题包含 Atlas 与黑曜且两套色板为人工定值`() {
        assertEquals(listOf("Atlas", "黑曜"), AtlasThemes.ALL.map { it.name })
        assertEquals("#FF1E2126", hexOf(AtlasThemes.ATLAS.dark.background))
        assertEquals("#FFF2F3F1", hexOf(AtlasThemes.ATLAS.light.background))
        assertEquals("#FFE9EDF2", hexOf(AtlasThemes.ATLAS.dark.md().h1))
        assertEquals("#FF3E5B7F", hexOf(AtlasThemes.ATLAS.light.md().h2))
        assertNotEquals(AtlasThemes.ATLAS.light.hexList(), AtlasThemes.ATLAS.dark.hexList())
        assertEquals("#FF151515", hexOf(AtlasThemes.BLACK.dark.background))
        assertEquals("#FF1B1B1B", hexOf(AtlasThemes.BLACK.dark.surface))
        assertEquals("#FFD2D2D2", hexOf(AtlasThemes.BLACK.dark.onSurface))
        assertEquals("#FF686868", hexOf(AtlasThemes.BLACK.dark.outline))
        assertEquals("#FFE0E0E0", hexOf(AtlasThemes.BLACK.dark.md().bold))
        assertEquals("#FF9BB1CE", hexOf(AtlasThemes.BLACK.dark.md().link))
        assertEquals("#FFF4F4F2", hexOf(AtlasThemes.BLACK.light.background))
    }

    @Test
    fun `每套模式固定二十项并可解析`() {
        AtlasThemes.ALL.flatMap { listOf(it.light, it.dark) }.forEach { spec ->
            assertEquals(ThemeSpec.SIZE, spec.hexList().size)
            spec.hexList().forEach { assertNotNull(parseHexColor(it)?.colorSpace) }
        }
    }
}
