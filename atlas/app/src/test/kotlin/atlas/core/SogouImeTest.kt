package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SogouImeTest {

    @Test
    fun `pgrep 输出含非空行即判定进程存在`() {
        assertTrue(SogouIme.pgrepHasMatch("12345\n"))
        assertTrue(SogouIme.pgrepHasMatch("12345\n6789\n"))
        assertFalse(SogouIme.pgrepHasMatch(""))
        assertFalse(SogouIme.pgrepHasMatch("\n"))
    }

    @Test
    fun `pgrep 模式用字符类避免匹配包裹 bash 自身`() {
        val wrapper = "bash --noprofile --norc -c pgrep -f \"[s]ogoupinyin-service\""
        val realProcess = "/opt/sogoupinyin/files/bin/sogoupinyin-service"
        val pattern = SogouIme.servicePgrepArgs().last()
        val regex = Regex(pattern)
        assertFalse(regex.containsMatchIn(wrapper), "包裹 bash 的命令行不能被自己匹配到")
        assertTrue(regex.containsMatchIn(realProcess), "真实引擎进程要能命中")
    }

    @Test
    fun `pkill 模式同样不匹配包裹 bash 自身但命中引擎与看门狗`() {
        val pattern = SogouIme.pkillCommand().substringAfter("pkill -f '").substringBefore("'")
        val regex = Regex(pattern)
        val wrapper = "bash --noprofile --norc -c ${SogouIme.pkillCommand()}"
        assertFalse(regex.containsMatchIn(wrapper))
        assertTrue(regex.containsMatchIn(SogouIme.SERVICE_PATH))
        assertTrue(regex.containsMatchIn(SogouIme.WATCHDOG_PATH))
    }

    @Test
    fun `一键修复序列为 清残留-看门狗-引擎-fcitx`() {
        val running = SogouIme.Status(true, true, true, true)
        val cmds = SogouIme.repairCommands(running)
        assertEquals(5, cmds.size)
        assertTrue(cmds[0].startsWith("pkill"))
        assertEquals("sleep 1", cmds[1])
        assertTrue(cmds[2].endsWith(SogouIme.WATCHDOG_PATH + " >/dev/null 2>&1 &"))
        assertTrue(cmds[3].endsWith(SogouIme.SERVICE_PATH + " >/dev/null 2>&1 &"))
        assertTrue(cmds[4].contains("fcitx -r"), "fcitx 在运行时用 -r 原地重启")
        // fcitx 未运行时用 -d 启动
        val allDead = SogouIme.Status(true, false, false, false)
        assertTrue(SogouIme.repairCommands(allDead).last().contains("fcitx -d"))
    }

    @Test
    fun `未安装时修复序列为空`() {
        assertTrue(SogouIme.repairCommands(SogouIme.Status(false, false, false, false)).isEmpty())
        assertTrue(SogouIme.repairCommands(null).isNotEmpty(), "无检测结果时按默认序列修复")
    }

    @Test
    fun `诊断按缺失组件给建议`() {
        assertTrue(SogouIme.Status(true, true, true, true).healthy)
        assertTrue(SogouIme.Status(true, true, true, true).advice().contains("无需修复"))
        assertTrue(SogouIme.Status(true, false, true, true).advice().contains("引擎未运行"))
        assertTrue(SogouIme.Status(true, true, false, true).advice().contains("看门狗未运行"))
        assertTrue(SogouIme.Status(true, true, true, false).advice().contains("fcitx 框架未运行"))
        assertTrue(SogouIme.Status(false, false, false, false).advice().contains("未检测到搜狗输入法"))
    }

    @Test
    fun `检测命令形态正确`() {
        assertEquals(listOf("pgrep", "-f", "[s]ogoupinyin-service"), SogouIme.servicePgrepArgs())
        assertEquals(listOf("pgrep", "-f", "[s]ogoupinyin-watchdog"), SogouIme.watchdogPgrepArgs())
        assertEquals(listOf("pgrep", "-x", "fcitx"), SogouIme.fcitxPgrepArgs())
    }
}
