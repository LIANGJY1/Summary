package atlas

import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.Theme
import atlas.ui.hexOf
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ThemeMdTest {
    @AfterEach fun reset() = Theme.apply(dark = true, spec = AtlasThemes.ATLAS.dark)

    @Test
    fun `V2 主题完整往返浅深模式`() {
        val original = CustomTheme("测试", AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark)
        val encoded = original.encode()
        assertTrue(encoded.startsWith("v2|测试|"))
        assertEquals(original, CustomTheme.decode(encoded))
    }

    @Test
    fun `Atlas markdown 语义具有稳定层级`() {
        val md = AtlasThemes.ATLAS.dark.md()
        assertEquals("#FFC8CDD4", hexOf(md.h1))
        assertEquals("#FFB9C1CC", hexOf(md.h2))
        assertEquals("#FFD0D1D2", hexOf(md.h3))
        assertNotEquals(hexOf(md.h3), hexOf(md.bold))
        assertNotEquals(hexOf(md.h2), hexOf(md.link))
    }

    @Test
    fun `Theme 暴露分级 Markdown 语义`() {
        Theme.apply(dark = true, spec = AtlasThemes.ATLAS.dark)
        assertEquals("#FFC8CDD4", hexOf(Theme.MdH1))
        assertEquals("#FFB9C1CC", hexOf(Theme.MdH2))
        assertEquals("#FFD0D1D2", hexOf(Theme.MdH3))
        assertEquals("#FFE0E1E2", hexOf(Theme.MdBold))
        assertEquals("#FFC1C4C8", hexOf(Theme.MdInlineCode))
    }
}
