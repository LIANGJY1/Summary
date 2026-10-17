package atlas.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownEditingTest {
    private fun value(text: String, start: Int, end: Int = start) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun `ctrl b is bold and ctrl i is italic`() {
        assertEquals("**" to "**", markdownShortcutWrap(Key.B))
        assertEquals("*" to "*", markdownShortcutWrap(Key.I))
        assertEquals("`" to "`", markdownShortcutWrap(Key.Grave))
        assertNull(markdownShortcutWrap(Key.X))
    }

    @Test
    fun `wraps selected text and keeps it selected`() {
        val result = value("五层划分组件", 0, 2).toggleMarkdownWrap("**")
        assertEquals("**五层**划分组件", result.text)
        assertEquals(TextRange(2, 4), result.selection)
    }

    @Test
    fun `toggles off when selection already wrapped`() {
        val result = value("**职责**", 0, 6).toggleMarkdownWrap("**")
        assertEquals("职责", result.text)
        assertEquals(TextRange(0, 2), result.selection)
    }

    @Test
    fun `inserts empty pair under cursor when nothing selected`() {
        val result = value("abc", 1).toggleMarkdownWrap("`")
        assertEquals("a``bc", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun `italic wrap uses single asterisk`() {
        val result = value("混合编译", 0, 4).toggleMarkdownWrap("*")
        assertEquals("*混合编译*", result.text)
        assertEquals(TextRange(1, 5), result.selection)
    }

    @Test
    fun `pastes image as a separate markdown block at the caret`() {
        val result = value("before after", 7).insertMarkdownImage("![图片](../_assets/a.png)")
        assertEquals("before \n\n![图片](../_assets/a.png)\n\nafter", result.text)
        assertEquals(TextRange("before \n\n![图片](../_assets/a.png)\n\n".length), result.selection)
    }

    @Test
    fun `pastes image into an empty field without adding surrounding blank lines`() {
        val result = value("", 0).insertMarkdownImage("![图片](../_assets/a.png)")
        assertEquals("![图片](../_assets/a.png)", result.text)
        assertEquals(TextRange(result.text.length), result.selection)
    }
}
