package atlas.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp

class UiTokensTest {
    @Test
    fun `light and dark themes expose the same readable layout scale`() {
        val light = AtlasUiTokens.forTheme(dark = false)
        val dark = AtlasUiTokens.forTheme(dark = true)

        assertTrue(light.spacing.page > 0.dp)
        assertTrue(light.spacing.card >= light.spacing.item)
        assertTrue(light.typography.body.fontSize >= light.typography.secondary.fontSize)
        assertTrue(dark.spacing.page == light.spacing.page)
        assertTrue(dark.typography.itemTitle.fontSize == light.typography.itemTitle.fontSize)
    }
}
