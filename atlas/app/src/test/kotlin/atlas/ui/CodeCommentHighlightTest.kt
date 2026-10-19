package atlas.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeCommentHighlightTest {

    @Test
    fun `cpp line comments dimmed, include not`() {
        val r = codeCommentRanges(
            listOf("#include <cstdio>", "int x = 1; // init", "// full line"),
            "cpp",
        )
        assertEquals(emptyList(), r[0])
        assertEquals(listOf(11 to 18), r[1])
        assertEquals(listOf(0 to 12), r[2])
    }

    @Test
    fun `url inside string is not comment`() {
        val r = codeCommentRanges(listOf("""std::cout << "https://e.com";"""), "cpp")
        assertEquals(emptyList(), r[0])
    }

    @Test
    fun `escaped quote does not close string`() {
        val r = codeCommentRanges(listOf("""char *s = "a\"b"; // c"""), "cpp")
        assertEquals(listOf(18 to 22), r[0])
    }

    @Test
    fun `block comment spans lines`() {
        val r = codeCommentRanges(
            listOf("/* header", "still here", "*/ int y = 2;"),
            "cpp",
        )
        assertEquals(listOf(0 to 9), r[0])
        assertEquals(listOf(0 to 10), r[1])
        assertEquals(listOf(0 to 2), r[2])
    }

    @Test
    fun `unterminated block comment dimmed to eol`() {
        val r = codeCommentRanges(listOf("int a; /* TODO"), "cpp")
        assertEquals(listOf(7 to 14), r[0])
    }

    @Test
    fun `hash comments need word boundary in shell`() {
        val r = codeCommentRanges(
            listOf("# comment", "    # indented", """echo "# not"""", "x=${'$'}{v#p}", "cmd # tail"),
            "bash",
        )
        assertEquals(listOf(0 to 9), r[0])
        assertEquals(listOf(4 to 14), r[1])
        assertEquals(emptyList(), r[2])
        assertEquals(emptyList(), r[3])
        assertEquals(listOf(4 to 10), r[4])
    }

    @Test
    fun `sql dashdash and block comment`() {
        val r = codeCommentRanges(listOf("SELECT 1; -- tail", "/* blk */"), "sql")
        assertEquals(listOf(10 to 17), r[0])
        assertEquals(listOf(0 to 9), r[1])
    }

    @Test
    fun `unknown language and blank lang get no ranges`() {
        val lines = listOf("// x", "# y")
        assertEquals(List(2) { emptyList<Pair<Int, Int>>() }, codeCommentRanges(lines, "text"))
        assertEquals(List(2) { emptyList<Pair<Int, Int>>() }, codeCommentRanges(lines, ""))
        assertNull(commentSyntaxFor("text"))
    }
}
