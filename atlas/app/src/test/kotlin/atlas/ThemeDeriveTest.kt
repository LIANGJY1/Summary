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
    fun `内置主题只有 Atlas 且两套色板为人工定值`() {
        assertEquals(listOf("Atlas"), AtlasThemes.ALL.map { it.name })
        assertEquals("#FF1B1B1C", hexOf(AtlasThemes.ATLAS.dark.background))
        assertEquals("#FFF3F3F1", hexOf(AtlasThemes.ATLAS.light.background))
        assertEquals("#FFC8CDD4", hexOf(AtlasThemes.ATLAS.dark.md().h1))
        assertEquals("#FF536477", hexOf(AtlasThemes.ATLAS.light.md().h2))
        assertNotEquals(AtlasThemes.ATLAS.light.hexList(), AtlasThemes.ATLAS.dark.hexList())
    }

    @Test
    fun `每套模式固定二十项并可解析`() {
        listOf(AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark).forEach { spec ->
            assertEquals(ThemeSpec.SIZE, spec.hexList().size)
            spec.hexList().forEach { assertNotNull(parseHexColor(it)?.colorSpace) }
        }
    }
}
