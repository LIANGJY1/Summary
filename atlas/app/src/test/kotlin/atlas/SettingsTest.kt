package atlas

import androidx.compose.ui.graphics.Color
import atlas.core.AppSettings
import atlas.core.SettingsStore
import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.ThemeSpec
import atlas.ui.parseHexColor
import atlas.ui.sanitizeHexInput
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsTest {
    @TempDir lateinit var tmp: File

    @Test
    fun `题目源文档配置可以持久化并读取`() {
        val store = SettingsStore(File(tmp, "settings.properties"))
        val expected = listOf("knowledge-base/language/java/", "knowledge-base/docs/面试.md")
        store.save(AppSettings(sourceQuestionPaths = expected, clickAnswerToEdit = false))
        val loaded = store.load()
        assertEquals(expected, loaded.sourceQuestionPaths)
        assertFalse(loaded.clickAnswerToEdit)
    }

    @Test
    fun `hex 输入净化与解析保持兼容`() {
        assertEquals("#1A56DB", sanitizeHexInput("#1a56db"))
        assertEquals("#A1B2C3D4", sanitizeHexInput("#a1b2c3d4"))
        assertEquals("", sanitizeHexInput("#zzz"))
        assertEquals(Color(0xFFAABBCC.toInt()), parseHexColor("#abc"))
        assertEquals(Color(0xFF1A56DB.toInt()), parseHexColor("#1A56DB"))
        assertEquals(null, parseHexColor("#12345"))
    }

    @Test
    fun `Atlas 两套模式的色值均可解析并稳定往返`() {
        listOf(AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark).forEach { spec ->
            spec.hexList().forEach { assertNotNull(parseHexColor(it)?.colorSpace) }
            assertEquals(spec.hexList(), requireNotNull(ThemeSpec.fromHexList(spec.hexList())).hexList())
        }
    }

    @Test
    fun `V2 自定义主题可持久化并完整读取`() {
        val custom = CustomTheme("夜航", AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark)
        val store = SettingsStore(File(tmp, "themes.properties"))
        store.save(AppSettings(themeName = custom.name, customThemes = listOf(custom.encode())))
        val loaded = store.load()
        val decoded = requireNotNull(CustomTheme.decode(loaded.customThemes.single()))
        assertEquals(custom, decoded)
        assertEquals("夜航", CustomTheme.nameOf(custom.encode()))
    }

    @Test
    fun `多条自定义主题用分号分隔不会互相拆散`() {
        val a = CustomTheme("甲主题", AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark)
        val b = CustomTheme("乙主题", AtlasThemes.ATLAS.light.copy(accent = parseHexColor("#FF704B8F")!!), AtlasThemes.ATLAS.dark)
        val store = SettingsStore(File(tmp, "custom-sep.properties"))
        store.save(AppSettings(customThemes = listOf(a.encode(), b.encode())))
        val decoded = store.load().customThemes.mapNotNull { CustomTheme.decode(it) }
        assertEquals(listOf("甲主题", "乙主题"), decoded.map { it.name })
        assertEquals(a, decoded[0])
        assertEquals(b, decoded[1])
    }

    @Test
    fun `非法或不完整 V2 主题被拒绝`() {
        val valid = CustomTheme("测试", AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark).encode()
        assertEquals(null, CustomTheme.decode(""))
        assertEquals(null, CustomTheme.decode("v2|只有名字"))
        assertEquals(null, CustomTheme.decode(valid.replaceFirst("#FF536477", "#zz")))
    }
}
