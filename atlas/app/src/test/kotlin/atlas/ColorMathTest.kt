package atlas

import androidx.compose.ui.graphics.Color
import atlas.ui.colorToHsv
import atlas.ui.hexOf
import atlas.ui.hexOf
import atlas.ui.hsvToColor
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {
    private fun near(a: Float, b: Float, tol: Float = 0.004f) = abs(a - b) <= tol

    @Test
    fun `HSV 六个主色相落在预期 RGB`() {
        val cases = listOf(
            Triple(0f, 1f, 1f) to Triple(1f, 0f, 0f),
            Triple(120f, 1f, 1f) to Triple(0f, 1f, 0f),
            Triple(240f, 1f, 1f) to Triple(0f, 0f, 1f),
            Triple(0f, 0f, 1f) to Triple(1f, 1f, 1f),
            Triple(0f, 0f, 0f) to Triple(0f, 0f, 0f),
            Triple(60f, 1f, 1f) to Triple(1f, 1f, 0f),
            Triple(180f, 1f, 1f) to Triple(0f, 1f, 1f),
            Triple(300f, 1f, 1f) to Triple(1f, 0f, 1f),
        )
        cases.forEach { (hsv, rgb) ->
            val c = hsvToColor(hsv.first, hsv.second, hsv.third)
            assertTrue(
                near(c.red, rgb.first) && near(c.green, rgb.second) && near(c.blue, rgb.third),
                "HSV$hsv 应为 RGB$rgb，实际 ${c.red},${c.green},${c.blue}",
            )
        }
    }

    @Test
    fun `灰阶的饱和度为 0`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { v ->
            assertTrue(near(colorToHsv(hsvToColor(0f, 0f, v)).second, 0f), "亮度 $v 的灰饱和度应为 0")
        }
    }

    @Test
    fun `RGB 转 HSV 再转回应还原原色`() {
        val probes = listOf(
            Color(0xFF1E66F5.toInt()), Color(0xFF89B4FA.toInt()), Color(0xFFFABD2F.toInt()),
            Color(0xFF73D0FF.toInt()), Color(0xFF528BFF.toInt()), Color(0xFFD3869B.toInt()),
            Color(0xFF000000.toInt()), Color(0xFFFFFFFF.toInt()), Color(0xFF808080.toInt()),
        )
        probes.forEach { src ->
            val (h, s, v) = colorToHsv(src)
            val back = hsvToColor(h, s, v)
            assertTrue(
                near(back.red, src.red) && near(back.green, src.green) && near(back.blue, src.blue),
                "${hexOf(src)} 往返后变成 ${hexOf(back)}",
            )
        }
    }

    @Test
    fun `色相 360 度等价于 0 度且不越界`() {
        assertTrue(near(hsvToColor(360f, 1f, 1f).red, 1f), "360° 应等同 0°")
        assertTrue(near(hsvToColor(-120f, 1f, 1f).blue, 1f), "负色相应回绕到 240°")
    }

    @Test
    fun `透明度独立于 RGB 保留`() {
        val c = hsvToColor(200f, 0.5f, 0.8f, 0.35f)
        assertEquals(0.35f, c.alpha, 0.001f, "alpha 应原样保留")
        val (h, s, v) = colorToHsv(c)
        assertEquals(200f, h, 1f)
    }
}
