package atlas

import atlas.ui.AtlasThemes
import atlas.ui.hexOf
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

private fun argb(hex: String): Long = hex.removePrefix("#").toLong(16)

private fun lum(hex: String): Double {
    val v = argb(hex)
    fun ch(s: Long): Double {
        val c = s / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * ch((v shr 16) and 0xFF) + 0.7152 * ch((v shr 8) and 0xFF) + 0.0722 * ch(v and 0xFF)
}

private fun ratio(fg: String, bg: String): Double {
    val a = lum(fg); val b = lum(bg)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

class ThemeContrastTest {
    @Test
    fun `对比度函数本身正确`() {
        assertTrue(ratio("#FFFFFFFF", "#FF000000") in 20.9..21.1, "黑白应为 WCAG 上限 21")
        assertTrue(ratio("#FF808080", "#FF808080") in 0.99..1.01, "同色应为 1")
    }

    @Test
    fun `Atlas 两种模式的文字与交互色均达 WCAG AA`() {
        val fails = mutableListOf<String>()
        fun need(tag: String, label: String, fg: String, bg: String, min: Double) {
            val r = ratio(fg, bg)
            if (r < min) fails += "%s %s %.2f < %.1f".format(tag, label, r, min)
        }
        AtlasThemes.ALL.forEach { e ->
            listOf(e.light to "浅", e.dark to "深").forEach { (s, mode) ->
                val t = "${e.name}/$mode"
                val bg = hexOf(s.background)
                val sf = hexOf(s.surface)
                val m = s.md()
                need(t, "正文/底", hexOf(s.onSurface), bg, 4.5)
                need(t, "正文/面", hexOf(s.onSurface), sf, 4.5)
                need(t, "次要/底", hexOf(s.muted), bg, 4.5)
                need(t, "次要/面", hexOf(s.muted), sf, 4.5)
                need(t, "强调/底", hexOf(s.accent), bg, 3.0)
                need(t, "轮廓/底", hexOf(s.outline), bg, 3.0)
                need(t, "H1/底", hexOf(m.h1), bg, 3.0)
                need(t, "H2/底", hexOf(m.h2), bg, 3.0)
                need(t, "H3/底", hexOf(m.h3), bg, 4.5)
                need(t, "链接/底", hexOf(m.link), bg, 4.5)
                need(t, "引用/底", hexOf(m.quote), bg, 4.5)
                need(t, "行内码/底", hexOf(m.inlineCode), bg, 4.5)
                need(t, "行内码/面", hexOf(m.inlineCode), sf, 4.5)
            }
        }
        assertTrue(
            fails.isEmpty(),
            "共 ${fails.size} 处对比度不足：\n" + fails.joinToString("\n") { "  $it" },
        )
    }
}
