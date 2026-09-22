package atlas

import atlas.core.AppSettings
import atlas.core.SettingsStore
import kotlin.test.assertEquals
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

        store.save(AppSettings(sourceQuestionPaths = expected))

        assertEquals(expected, store.load().sourceQuestionPaths)
    }
}
