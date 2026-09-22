package atlas

import atlas.core.AppSettings
import atlas.core.SettingsStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
