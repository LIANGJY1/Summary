package atlas.ui

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertTrue

class MdCodeInlineWidthTest {
    @Test
    fun `inline code chip avoids reserving a full extra glyph`() {
        val code = "ActivityThread.performLaunchActivity()"
        val fontSize = 14.sp
        val textWidthEstimate = code.length * 0.62f * fontSize.value
        val horizontalPaddingAndSmallSafetyMargin = 14f
        val width = mdChipWidthSp(code, fontSize).value

        assertTrue(width >= textWidthEstimate + horizontalPaddingAndSmallSafetyMargin)
        assertTrue(width < textWidthEstimate + horizontalPaddingAndSmallSafetyMargin + fontSize.value)
    }
}
