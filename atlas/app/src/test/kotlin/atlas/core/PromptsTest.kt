package atlas.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PromptsTest {

    @Test
    fun `md 协议写读往返`(@TempDir dir: File) {
        val file = File(dir, "prompts.md")
        val entries = listOf(
            Prompts.Entry("源码注释提问", "请逐段注释以下源码：\n\n```\ncode here\n```"),
            Prompts.Entry("进度整理", "把下面的工作整理成进度报告。\n第二行内容。"),
        )
        Prompts.save(file, entries)
        assertEquals(entries, Prompts.load(file))
    }

    @Test
    fun `缺失文件与空文件返回空列表`(@TempDir dir: File) {
        assertTrue(Prompts.load(File(dir, "nope.md")).isEmpty())
        val empty = File(dir, "empty.md").apply { writeText("") }
        assertTrue(Prompts.load(empty).isEmpty())
    }

    @Test
    fun `无标题的散行被忽略`(@TempDir dir: File) {
        val file = File(dir, "prompts.md")
        file.writeText("散行没有标题\n## A\n内容 A\n")
        assertEquals(listOf(Prompts.Entry("A", "内容 A")), Prompts.load(file))
    }

    @Test
    fun `保存空列表写出空文件`(@TempDir dir: File) {
        val file = File(dir, "prompts.md")
        Prompts.save(file, emptyList())
        assertTrue(file.isFile)
        assertEquals(0, file.length())
        assertTrue(Prompts.load(file).isEmpty())
    }

    @Test
    fun `标题与正文两侧空白被裁剪`(@TempDir dir: File) {
        val file = File(dir, "prompts.md")
        Prompts.save(file, listOf(Prompts.Entry("  标题  ", "\n  正文 \n")))
        assertEquals(listOf(Prompts.Entry("标题", "正文")), Prompts.load(file))
    }
}
