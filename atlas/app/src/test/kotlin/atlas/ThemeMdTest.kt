package atlas

import atlas.core.AppSettings
import atlas.core.SettingsStore
import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.Theme
import atlas.ui.ThemeSpec
import atlas.ui.hexOf
import atlas.ui.parseHexColor
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Markdown 语义色并入主题后的行为：每套主题自带、可编辑、可永久存储。 */
class ThemeMdTest {

    @AfterEach
    fun reset() = Theme.apply(dark = true, spec = AtlasThemes.DEFAULT.spec.darken())

    private val tmp: java.io.File get() = java.io.File(System.getProperty("java.io.tmpdir"), "atlas-md-${System.nanoTime()}").apply { mkdirs() }

    private fun mdSpec() = CustomTheme(
        "测试",
        ThemeSpec(
            accent = parseHexColor("#FF4F6EF7")!!, okGreen = parseHexColor("#FF2E9E5B")!!,
            warnOrange = parseHexColor("#FFD98A2B")!!, badRed = parseHexColor("#FFD9534F")!!,
            muted = parseHexColor("#FF8A8F98")!!, codeBg = parseHexColor("#FFF5F6F8")!!,
            inlineCodeFg = parseHexColor("#FF0B6E7D")!!, background = parseHexColor("#FFFFFFFF")!!,
            surface = parseHexColor("#FFF7F8FA")!!, surfaceVariant = parseHexColor("#FFEDEFF3")!!,
            onSurface = parseHexColor("#FF1B1E24")!!, outlineVariant = parseHexColor("#FFD7DBE2")!!,
            mdHeading = parseHexColor("#FF112233")!!, mdBold = parseHexColor("#FF223344")!!,
            mdLink = parseHexColor("#FF334455")!!, mdQuote = parseHexColor("#FF445566")!!,
            mdInlineCode = parseHexColor("#FF556677")!!, mdInlineCodeBg = parseHexColor("#FF778899")!!,
        ),
    )

    @Test
    fun `主题自带的 markdown 色可持久化并读回`() {
        val ct = mdSpec()
        val file = File(tmp, "md-in-theme.properties")
        SettingsStore(file).save(AppSettings(customThemes = listOf(ct.encode())))
        val decoded = SettingsStore(file).load().customThemes.mapNotNull { CustomTheme.decode(it) }
        assertEquals(1, decoded.size)
        assertEquals(ct.spec.hexList(), decoded[0].spec.hexList(), "18 项应完整往返")
        assertEquals(hexOf(ct.spec.mdHeading!!), hexOf(decoded[0].spec.mdHeading!!))
        assertEquals(hexOf(ct.spec.mdInlineCodeBg!!), hexOf(decoded[0].spec.mdInlineCodeBg!!))
    }

    @Test
    fun `未指定 markdown 色时由基色推导`() {
        val s = ThemeSpec(
            accent = parseHexColor("#FF4F6EF7")!!, okGreen = parseHexColor("#FF2E9E5B")!!,
            warnOrange = parseHexColor("#FFD98A2B")!!, badRed = parseHexColor("#FFD9534F")!!,
            muted = parseHexColor("#FF8A8F98")!!, codeBg = parseHexColor("#FFF5F6F8")!!,
            inlineCodeFg = parseHexColor("#FF0B6E7D")!!, background = parseHexColor("#FFFFFFFF")!!,
            surface = parseHexColor("#FFF7F8FA")!!, surfaceVariant = parseHexColor("#FFEDEFF3")!!,
            onSurface = parseHexColor("#FF1B1E24")!!, outlineVariant = parseHexColor("#FFD7DBE2")!!,
        )
        assertNull(s.mdHeading)
        assertEquals(hexOf(s.accent), hexOf(s.md().heading), "标题默认取强调色")
        assertEquals(hexOf(s.accent), hexOf(s.md().bold))
        assertEquals(hexOf(s.muted), hexOf(s.md().quote), "引用默认取次要文字")
        assertEquals(hexOf(s.inlineCodeFg), hexOf(s.md().inlineCode))
        assertNull(s.md().inlineCodeBg, "底色默认不加")
    }

    @Test
    fun `内置主题的 markdown 色随主题生效`() {
        Theme.apply(dark = true, spec = AtlasThemes.DEFAULT.spec.darken())
        val a = hexOf(Theme.MdHeading)
        Theme.apply(dark = true, spec = AtlasThemes.ALL.last().spec.darken())
        assertTrue(a != hexOf(Theme.MdHeading), "换主题 markdown 标题色应随之变")
    }

    @Test
    fun `浅深色派生会带上 markdown 色`() {
        val s = mdSpec().spec
        listOf(s.lighten(), s.darken()).forEach { d ->
            val c = requireNotNull(parseHexColor(hexOf(d.md().heading))) { "派生色应可解析" }
            assertNotNull(c.colorSpace, "派生出的 markdown 色必须能解析")
        }
    }

    @Test
    fun `兼容旧的 12 色主题记录`() {
        val base = mdSpec().spec
        val legacy = base.hexList().take(ThemeSpec.BASE)
        val decoded = requireNotNull(ThemeSpec.fromHexList(legacy)) { "旧格式应仍能读" }
        assertEquals(legacy, decoded.hexList().take(ThemeSpec.BASE), "12 项基色应保留")
        assertNull(decoded.mdHeading, "旧记录没有 markdown 色，应走推导")
    }

    @Test
    fun `未指定的 markdown 色在落盘时固化为推导值`() {
        val ct = mdSpec()
        val blanked = ct.spec.copy(mdHeading = null, mdInlineCodeBg = null)
        // 存生效值而非 null 占位：日后推导规则变了，已存主题的观感也不漂移。
        assertEquals(hexOf(ct.spec.accent), hexOf(blanked.md().heading), "落盘时应写入推导出的强调色")
        val round = requireNotNull(ThemeSpec.fromHexList(blanked.hexList())) { "应可解析" }
        assertEquals(hexOf(ct.spec.accent), hexOf(round.mdHeading!!), "读回后仍是同一个值")
        assertNull(round.mdInlineCodeBg, "底色本就未指定，仍为 null")
    }

    @Test
    fun `清空任一基色应被拒而不是抛异常`() {
        val full = mdSpec().spec.hexList()
        for (i in 0 until ThemeSpec.BASE) {
            val cleared = full.toMutableList().also { it[i] = "" }
            assertNull(ThemeSpec.fromHexList(cleared), "基色第 $i 项清空应返回 null")
        }
    }

    @Test
    fun `清空 markdown 色应被接受并回落推导`() {
        val full = mdSpec().spec.hexList()
        for (i in ThemeSpec.BASE until ThemeSpec.SIZE) {
            val cleared = full.toMutableList().also { it[i] = "" }
            assertNotNull(ThemeSpec.fromHexList(cleared), "markdown 第 $i 项允许留空")
        }
    }

    @Test
    fun `全部内置主题的 18 项往返稳定`() {
        AtlasThemes.ALL.forEach { e ->
            listOf(e.spec.lighten(), e.spec.darken()).forEach { s ->
                val round = requireNotNull(ThemeSpec.fromHexList(s.hexList())) { "${e.name} 应可解析" }
                assertEquals(s.hexList(), round.hexList(), "${e.name} 18 项应稳定")
            }
        }
    }
}
