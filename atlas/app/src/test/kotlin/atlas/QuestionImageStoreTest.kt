package atlas

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import atlas.core.SourceQuestions
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class QuestionImageStoreTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `saves png assets once and removes unreferenced assets on editor cancellation`() {
        val root = File(tmp, "library").apply { mkdirs() }
        val document = File(root, "knowledge-base/docs/q.md").apply {
            parentFile.mkdirs()
            writeText("**Q1: 问题？**\n\n答案。", Charsets.UTF_8)
        }
        val store = AppStore(File(tmp, "config"))
        store.settings = store.settings.copy(
            libraryPath = root.absolutePath,
            sourceQuestionPaths = listOf("knowledge-base/"),
        )
        store.openLibrary(root.absolutePath, rescanIfNeeded = false)
        val source = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, 0xFF123456.toInt())
        val png = ByteArrayOutputStream().use { output ->
            ImageIO.write(source, "png", output)
            output.toByteArray()
        }

        val first = assertNotNull(store.saveQuestionImage("knowledge-base/docs/q.md", "Q1: [done] 问题？", png))
        val second = assertNotNull(store.saveQuestionImage("knowledge-base/docs/q.md", "另一道题", png))
        assertEquals(first, second)
        val decodedPath = java.net.URLDecoder.decode(first.replace("+", "%2B"), Charsets.UTF_8)
        val asset = File(document.parentFile, decodedPath)
        assertTrue(asset.isFile)
        assertTrue(asset.name.startsWith("问题-"))
        assertTrue(asset.name.matches(Regex(".+-[a-f0-9]{64}\\.png")))

        store.cleanupUnreferencedQuestionImages(listOf(first))

        assertFalse(asset.exists())
    }

    @Test
    fun `deleting rendered image removes its markdown and unreferenced asset`() {
        val root = File(tmp, "library").apply { mkdirs() }
        val path = "knowledge-base/docs/q.md"
        val document = File(root, path).apply {
            parentFile.mkdirs()
            writeText("**Q1: 图片题？**\n\n答案。", Charsets.UTF_8)
        }
        val store = AppStore(File(tmp, "config-delete"))
        store.settings = store.settings.copy(
            libraryPath = root.absolutePath,
            sourceQuestionPaths = listOf("knowledge-base/"),
        )
        store.openLibrary(root.absolutePath, rescanIfNeeded = false)
        val png = questionPng()
        val reference = assertNotNull(store.saveQuestionImage(path, "图片题？", png)).let { "![]($it)" }
        document.writeText("**Q1: 图片题？**\n\n${reference}\n\n答案。", Charsets.UTF_8)
        val entry = SourceQuestions.parse(path, document.readText(Charsets.UTF_8), listOf("knowledge-base/")).single()
        val assetName = java.net.URLDecoder.decode(reference.substringAfter("../_assets/").substringBefore(')'), Charsets.UTF_8)
        val asset = File(root, "knowledge-base/_assets/$assetName")

        assertTrue(store.deleteQuestionImageReference(entry, reference))

        assertFalse(document.readText(Charsets.UTF_8).contains(reference))
        assertFalse(asset.exists())
    }

    @Test
    fun `deleting one reference keeps an asset still used by another document`() {
        val root = File(tmp, "shared-library").apply { mkdirs() }
        val path = "knowledge-base/docs/q.md"
        val document = File(root, path).apply {
            parentFile.mkdirs()
            writeText("**Q1: 图片题？**\n\n答案。", Charsets.UTF_8)
        }
        val store = AppStore(File(tmp, "config-shared"))
        store.settings = store.settings.copy(
            libraryPath = root.absolutePath,
            sourceQuestionPaths = listOf("knowledge-base/"),
        )
        store.openLibrary(root.absolutePath, rescanIfNeeded = false)
        val reference = assertNotNull(store.saveQuestionImage(path, "图片题？", questionPng())).let { "![]($it)" }
        document.writeText("**Q1: 图片题？**\n\n$reference\n\n答案。", Charsets.UTF_8)
        val sharedDocument = File(root, "knowledge-base/其他/共享.md").apply {
            parentFile.mkdirs()
            writeText("此处继续使用：$reference", Charsets.UTF_8)
        }
        val entry = SourceQuestions.parse(path, document.readText(Charsets.UTF_8), listOf("knowledge-base/")).single()
        val assetName = java.net.URLDecoder.decode(reference.substringAfter("../_assets/").substringBefore(')'), Charsets.UTF_8)
        val asset = File(root, "knowledge-base/_assets/$assetName")

        assertTrue(store.deleteQuestionImageReference(entry, reference))

        assertFalse(document.readText(Charsets.UTF_8).contains(reference))
        assertTrue(sharedDocument.readText(Charsets.UTF_8).contains(reference))
        assertTrue(asset.isFile)
    }

    private fun questionPng(): ByteArray {
        val source = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, 0xFF123456.toInt())
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(source, "png", output)
            output.toByteArray()
        }
    }
}
