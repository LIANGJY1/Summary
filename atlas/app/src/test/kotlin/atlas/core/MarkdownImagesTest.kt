package atlas.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownImagesTest {
    @Test
    fun `moving a question rebases relative image paths and leaves remote images alone`() {
        val root = File("/tmp/atlas-md-image-test")
        val source = File(root, "knowledge-base/a.md")
        val target = File(root, "knowledge-base/01-android/topics/b.md")
        val markdown = "![截图](_assets/abc.png)\n\n![web](https://example.com/a.png)"

        val actual = MarkdownImages.rebaseRelativeLinks(markdown, source, target)

        assertEquals(
            "![截图](../../_assets/abc.png)\n\n![web](https://example.com/a.png)",
            actual,
        )
    }

    @Test
    fun `rebases encoded spaces in relative image path`() {
        val root = File("/tmp/atlas-md-image-test")
        val source = File(root, "knowledge-base/a.md")
        val target = File(root, "knowledge-base/deep/b.md")

        val actual = MarkdownImages.rebaseRelativeLinks("![图](_assets/my%20image.png)", source, target)

        assertEquals("![图](../_assets/my%20image.png)", actual)
    }
}
