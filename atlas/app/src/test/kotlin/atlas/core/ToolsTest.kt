package atlas.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsTest {

    @Test
    fun `命令组装为 python3 加脚本加输入`() {
        assertEquals(
            listOf("python3", "/path/to/hc_log_auto.py", "/data/20260923.zip"),
            Tools.hcLogCommand("/path/to/hc_log_auto.py", "/data/20260923.zip"),
        )
    }

    @Test
    fun `输入预校验放行目录与压缩包扩展名`(@TempDir dir: File) {
        assertTrue(Tools.isAcceptableInput(dir)) // 目录判断要求真实存在
        assertTrue(Tools.isAcceptableInput(File("/data/logs.zip")))
        assertTrue(Tools.isAcceptableInput(File("/data/logs.7z")))
        assertTrue(Tools.isAcceptableInput(File("/data/logs.tar.gz")))
        assertFalse(Tools.isAcceptableInput(File("/data/notes.txt")))
        assertFalse(Tools.isAcceptableInput(File("/data/log.enc")))
    }

    @Test
    fun `规范化拖入路径支持 file URI 与纯路径`() {
        assertEquals("/home/liang/Downloads/a.zip", Tools.normalizeDroppedPath("file:/home/liang/Downloads/a.zip"))
        assertEquals("/home/liang/Downloads/a.zip", Tools.normalizeDroppedPath("file:///home/liang/Downloads/a.zip"))
        assertEquals("/home/liang/Downloads/a.zip", Tools.normalizeDroppedPath("/home/liang/Downloads/a.zip"))
        assertEquals("/home/liang/My Docs/a.zip", Tools.normalizeDroppedPath("file:///home/liang/My%20Docs/a.zip"))
        assertEquals("::not-a-uri::", Tools.normalizeDroppedPath("::not-a-uri::"))
    }

    @Test
    fun `解析成功输出提取摘要与输出目录`() {
        val output = """
            密钥: 27HM 开发秘钥    日志目录: /data/20260923/Logcat
            完成: 解密 3 个, 解压 2 个 .lz4, 失败 0 个
            输出目录: /data/20260923/解密_解压_Logcat
        """.trimIndent()
        val run = Tools.parseToolOutput(output, exitCode = 0)

        assertEquals(0, run.exitCode)
        assertEquals("完成: 解密 3 个, 解压 2 个 .lz4, 失败 0 个", run.summary)
        assertEquals("/data/20260923/解密_解压_Logcat", run.outputDir)
        assertFalse(run.running)
        assertTrue(run.tail.contains("完成: 解密 3 个, 解压 2 个 .lz4, 失败 0 个"))
    }

    @Test
    fun `解析失败输出保留尾部且摘要目录可为空`() {
        val run = Tools.parseToolOutput("Traceback (most recent call last):", exitCode = 1)

        assertEquals(1, run.exitCode)
        assertNull(run.summary)
        assertNull(run.outputDir)
        assertEquals(listOf("Traceback (most recent call last):"), run.tail)
    }
}
