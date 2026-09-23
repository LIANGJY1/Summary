package atlas

import atlas.core.SourceQuestions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceQuestionMoveTest {

    @TempDir
    lateinit var tmp: File

    private fun buildStore(): Triple<AppStore, File, File> {
        val config = File(tmp, "config-move-${System.nanoTime()}")
        val store = AppStore(config)
        val root = File(tmp, "move-lib-${System.nanoTime()}").apply { mkdirs() }
        val sourceFile = File(root, "knowledge-base/android/a.md").apply { parentFile.mkdirs() }
        sourceFile.writeText(
            "**Q1: 留守问题？**\n\n留守答案。\n\n**Q2: 被移动问题？**\n\n被移动答案。",
            Charsets.UTF_8,
        )
        val targetFile = File(root, "knowledge-base/language/b.md").apply { parentFile.mkdirs() }
        targetFile.writeText("# b\n\n**Q1: 已有问题？**\n\n已有答案。", Charsets.UTF_8)
        store.settings = store.settings.copy(
            libraryPath = root.absolutePath,
            sourceQuestionPaths = listOf("knowledge-base/"),
        )
        store.openLibrary(root.absolutePath, rescanIfNeeded = false)
        return Triple(store, sourceFile, targetFile)
    }

    @Test
    fun `移动题目到另一份映射文档并按目标题号续排`() {
        val (store, sourceFile, targetFile) = buildStore()

        val entry = store.allSourceQuestions.first { it.question == "被移动问题？" }
        assertEquals("knowledge-base/android/a.md", entry.sourcePath)

        assertTrue(store.moveSourceQuestion(entry, "knowledge-base/language/b.md"))

        val sourceText = sourceFile.readText(Charsets.UTF_8)
        val targetText = targetFile.readText(Charsets.UTF_8)
        assertFalse(sourceText.contains("被移动问题"), "源文档不应再包含被移动的题目")
        assertTrue(sourceText.contains("**Q1: 留守问题？**"), "源文档剩余题目保留")
        assertTrue(targetText.contains("**Q2: 被移动问题？**\n\n被移动答案。"), "目标文档从最大题号之后续排")

        val moved = store.allSourceQuestions.single { it.question == "被移动问题？" }
        assertEquals("knowledge-base/language/b.md", moved.sourcePath)
        assertEquals(2, moved.number)
    }

    @Test
    fun `拒绝移动到同一文档或未映射路径且不改动文件`() {
        val (store, sourceFile, targetFile) = buildStore()
        val entry = store.allSourceQuestions.first { it.question == "留守问题？" }
        val sourceBefore = sourceFile.readText(Charsets.UTF_8)

        assertFalse(store.moveSourceQuestion(entry, "knowledge-base/android/a.md"))
        assertFalse(store.moveSourceQuestion(entry, "issue/x.md"))

        assertEquals(sourceBefore, sourceFile.readText(Charsets.UTF_8))
        assertTrue(targetFile.readText(Charsets.UTF_8).contains("已有问题？"))
    }

    @Test
    fun `源文档被外部修改后拒绝移动并重载`() {
        val (store, sourceFile, _) = buildStore()
        val entry = store.allSourceQuestions.first { it.question == "被移动问题？" }
        sourceFile.writeText(
            sourceFile.readText(Charsets.UTF_8).replace("留守答案", "外部改动答案"),
            Charsets.UTF_8,
        )

        assertFalse(store.moveSourceQuestion(entry, "knowledge-base/language/b.md"))
        assertFalse(
            store.allSourceQuestions.first { it.question == "被移动问题？" }.sourcePath == "knowledge-base/language/b.md",
        )
    }
}
