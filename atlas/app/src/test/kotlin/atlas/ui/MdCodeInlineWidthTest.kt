package atlas.ui

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertTrue

class MdCodeInlineWidthTest {
    @Test
    fun `long inline code chip reserves room for its final glyph`() {
        val code = "ActivityThread.performLaunchActivity()"
        val fontSize = 14.sp
        val textWidthEstimate = code.length * 0.62f * fontSize.value
        val horizontalPaddingAndCurrentSlack = 14f

        assertTrue(
            mdChipWidthSp(code, fontSize).value >=
                textWidthEstimate + horizontalPaddingAndCurrentSlack + fontSize.value,
            "Long code chips need at least one glyph of width beyond the current estimate",
        )
    }
}
