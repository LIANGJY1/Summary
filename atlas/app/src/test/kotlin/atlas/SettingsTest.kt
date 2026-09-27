package atlas

import androidx.compose.ui.graphics.Color
import atlas.core.AppSettings
import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.ThemeSpec
import atlas.core.SettingsStore
import atlas.ui.parseHexColor
import atlas.ui.sanitizeHexInput
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `题目源文档配置可以持久化并读取`() {
        val file = File(tmp, "settings.properties")
        val store = SettingsStore(file)
        val expected = listOf(
            "knowledge-base/language/java/",
            "knowledge-base/docs/面试.md",
        )

        store.save(AppSettings(sourceQuestionPaths = expected, clickAnswerToEdit = false, markdownStyle = "classic"))

        val loaded = store.load()
        assertEquals(expected, loaded.sourceQuestionPaths)
        assertFalse(loaded.clickAnswerToEdit)
        assertEquals("classic", loaded.markdownStyle)
    }

    @Test
    fun `hex 输入净化只保留井号与十六进制并限长`() {
        assertEquals("#1A56DB", sanitizeHexInput("#1a56db"))
        assertEquals("#1A56DB", sanitizeHexInput("1a56db"))
        assertEquals("#A1B2C3D4", sanitizeHexInput("#a1b2c3d4"))
        assertEquals("#ABCDEF12", sanitizeHexInput("#abcdef1234"), "超过 8 位按 AARRGGBB 截断")
        assertEquals("", sanitizeHexInput("#zzz"), "非十六进制字符被滤掉后为空，回到默认")
        assertEquals("", sanitizeHexInput(""))
    }

    @Test
    fun `hex 解析支持三位六位八位且非法值回退 null`() {
        assertEquals(Color(0xFFAABBCC.toInt()), parseHexColor("#abc"))
        assertEquals(Color(0xFF1A56DB.toInt()), parseHexColor("#1A56DB"))
        assertEquals(Color(0x801A56DB.toInt()), parseHexColor("#801A56DB"))
        assertEquals(null, parseHexColor(""))
        assertEquals(null, parseHexColor("#12345"))
        assertEquals(null, parseHexColor("nope"))
    }

    /**
     * 回归：`Color(ULong)` 造出的颜色解析色彩空间时会抛
     * `ArrayIndexOutOfBoundsException`（该版本把 sRGB 放在高 32 位，低 32 位当色彩空间 id 读会越界），
     * 表现为设置页画色块时崩溃。解析出来的颜色必须能取到 colorSpace。
     */
    @Test
    fun `解析出的颜色可以取到色彩空间`() {
        listOf("#abc", "#1A56DB", "#801A56DB", "#FDEBEB", "#0B6E7D", "#5FD3E8")
            .forEach { hex ->
                val c = requireNotNull(parseHexColor(hex)) { "$hex 应解析成功" }
                assertNotNull(c.colorSpace, "$hex 的色彩空间不应为 null")
            }
    }

    @Test
    fun `无法解析色彩空间的颜色被挡在渲染之前`() {
        // Color(ULong) 造出的低 32 位颜色在这里返回 null，而不是等到绘制时崩溃
        assertEquals(null, Color(0xFF1A56DBuL).let { if (runCatching { it.colorSpace }.isSuccess) it else null })
        assertNotNull(parseHexColor("#1A56DB"), "合法颜色不应被误挡")
    }

    @Test
    fun `全部内置主题的每个色值都能取到色彩空间`() {
        AtlasThemes.ALL.forEach { e ->
            e.spec.hexList().filter { it.isNotBlank() }.forEach { hex ->
                val c = requireNotNull(parseHexColor(hex)) { "${e.name} 的 $hex 应解析成功" }
                assertNotNull(c.colorSpace, "${e.name} 的 $hex 色彩空间不应为 null")
            }
        }
    }

    @Test
    fun `主题名与自定义主题可以持久化并读取`() {
        val file = File(tmp, "themes.properties")
        val store = SettingsStore(file)
        val custom = CustomTheme("夜航", AtlasThemes.DEFAULT.spec.darken())
        store.save(AppSettings(themeName = "夜航", customThemes = listOf(custom.encode())))

        val loaded = store.load()
        assertEquals("夜航", loaded.themeName)
        val decoded = loaded.customThemes.mapNotNull { CustomTheme.decode(it) }
        assertEquals(1, decoded.size)
        assertEquals("夜航", decoded[0].name)
        assertEquals(custom.spec.hexList(), decoded[0].spec.hexList())
    }

    @Test
    fun `Color 与 hex 往返一致`() {
        listOf(AtlasThemes.DEFAULT.spec, AtlasThemes.ALL[3].spec).forEach { spec ->
            val round = requireNotNull(ThemeSpec.fromHexList(spec.hexList()))
            assertEquals(spec.hexList(), round.hexList(), "hex → Color → hex 必须稳定")
        }
    }

    @Test
    fun `全部内置主题色值可解析且往返稳定`() {
        AtlasThemes.ALL.forEach { e ->
            val round = requireNotNull(ThemeSpec.fromHexList(e.spec.hexList())) { e.name }
            assertEquals(e.spec.hexList(), round.hexList(), e.name)
            e.spec.hexList().filter { it.isNotBlank() }.forEach {
                assertNotNull(parseHexColor(it)?.colorSpace, "${e.name} 的 $it 色彩空间不应为 null")
            }
        }
    }

    @Test
    fun `按名字在列表里定位并改写自定义主题`() {
        val a = CustomTheme("晨雾", AtlasThemes.DEFAULT.spec).encode()
        val b = CustomTheme("夜航", AtlasThemes.byName("Gruvbox Dark").spec).encode()
        val list = listOf(a, b)

        assertEquals("晨雾", CustomTheme.nameOf(a))
        assertEquals("夜航", CustomTheme.nameOf(b))
        assertEquals("夜航", CustomTheme.nameOf(b + "|追加字段"), "名字只看首段，不受尾部内容影响")

        // 编辑：UI 是 map + 按名替换，改动必须真的落到那条记录上
        val edited = CustomTheme("晨雾", AtlasThemes.byName("Catppuccin Macchiato").spec)
        val after = list.map { if (CustomTheme.nameOf(it) == "晨雾") edited.encode() else it }
        assertEquals(edited.encode(), after[0], "被编辑的记录应被替换")
        assertEquals(b, after[1], "其余记录应原样保留")
        assertEquals(edited.spec.hexList(), requireNotNull(CustomTheme.decode(after[0])).spec.hexList())

        // 删除：filterNot 按名剔除
        val left = list.filterNot { CustomTheme.nameOf(it) == "夜航" }
        assertEquals(1, left.size, "应只剩一条")
        assertEquals("晨雾", CustomTheme.nameOf(left[0]))
    }

    @Test
    fun `自定义主题编码可解码且非法输入被拒`() {
        val ct = CustomTheme("测试", AtlasThemes.DEFAULT.spec)
        val back = requireNotNull(CustomTheme.decode(ct.encode()))
        assertEquals(ct.name, back.name, "编码解码应还原同一个主题")
        assertEquals(ct.spec.hexList(), back.spec.hexList(), "18 项应完整往返")
        assertEquals(null, CustomTheme.decode(""), "空串应被拒")
        assertEquals(null, CustomTheme.decode("只有名字"), "缺色值应被拒")
        assertEquals(null, CustomTheme.decode("|#" + ct.spec.hexList().joinToString("|#")), "空名应被拒")
        assertEquals(
            null,
            CustomTheme.decode("测试|" + ct.spec.hexList().dropLast(1).joinToString("|")),
            "少一字段应被拒",
        )
        val broken = ct.spec.hexList().mapIndexed { i, h -> if (i == 0) "#zz" else h }
        assertEquals(null, CustomTheme.decode("测试|" + broken.joinToString("|")), "非法色值应被拒")
    }

    @Test
    fun `多条自定义主题用分号分隔不会互相拆散`() {
        val a = CustomTheme("甲主题", AtlasThemes.DEFAULT.spec)
        val b = CustomTheme("乙主题", AtlasThemes.ALL[1].spec)
        val file = File(tmp, "custom-sep.properties")
        val store = SettingsStore(file)
        store.save(AppSettings(customThemes = listOf(a.encode(), b.encode())))

        val decoded = store.load().customThemes.mapNotNull { CustomTheme.decode(it) }
        assertEquals(2, decoded.size, "两条主题都应完整还原")
        assertEquals(listOf("甲主题", "乙主题"), decoded.map { it.name })
        assertEquals(a.spec.hexList(), decoded[0].spec.hexList())
        assertEquals(b.spec.hexList(), decoded[1].spec.hexList())
    }

    @Test
    fun `未知主题名回退到默认主题`() {
        assertEquals(AtlasThemes.DEFAULT.name, AtlasThemes.byName("不存在的主题").name)
        assertEquals(AtlasThemes.ALL[1].name, AtlasThemes.byName(AtlasThemes.ALL[1].name).name)
    }
}
