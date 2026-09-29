package atlas.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DeviceToolsTest {

    @Test
    fun `推送 APK 命令序列按 bash 脚本语义组装`() {
        val cmds = DeviceTools.pushLauncherCommands("/sdk/adb", "/data/NsrLauncher.apk")
        assertEquals(5, cmds.size)
        assertEquals(listOf("/sdk/adb", "root"), cmds[0])
        assertEquals(listOf("/sdk/adb", "wait-for-device"), cmds[1])
        assertEquals(listOf("/sdk/adb", "remount"), cmds[2])
        assertEquals(listOf("/sdk/adb", "push", "/data/NsrLauncher.apk", "/system_ext/priv-app/NsrLauncher"), cmds[3])
        assertEquals(listOf("/sdk/adb", "reboot"), cmds[4])
    }

    @Test
    fun `模拟器参数区分冷启动`() {
        assertEquals(
            listOf("/emulator", "-avd", "3DAA", "-writable-system"),
            DeviceTools.emulatorArgs("/emulator", "3DAA", coldBoot = false),
        )
        assertEquals(
            listOf("/emulator", "-avd", "3DAA", "-writable-system", "-no-snapshot"),
            DeviceTools.emulatorArgs("/emulator", "3DAA", coldBoot = true),
        )
    }

    @Test
    fun `ps 输出解析出目标包名 PID`() {
        val ps = """
            USER           PID   PPID  VSZ    RSS WCHAN            ADDR S NAME
            root          1234     1   123456 123456 0                   0 S com.yadea.launcher
            u0_a99        2345   800   999999 88888 0                   0 S com.other.app
        """.trimIndent()
        assertEquals("1234", DeviceTools.parsePid(ps, DeviceTools.LAUNCHER_PACKAGE))
        assertEquals("2345", DeviceTools.parsePid(ps, "com.other.app"))
        assertNull(DeviceTools.parsePid(ps, "com.not.running"))
        assertNull(DeviceTools.parsePid("", DeviceTools.LAUNCHER_PACKAGE))
    }

    @Test
    fun `行首斜杠清理保留缩进去掉单个空格`(@TempDir dir: File) {
        val input = "  // 注释一行\n//顶格注释\ncode()  // 行中注释不处理\n\t//tab 缩进"
        assertEquals("  注释一行\n顶格注释\ncode()  // 行中注释不处理\n\ttab 缩进", DeviceTools.stripCommentSlashes(input))
    }

    @Test
    fun `截图文件名自增不覆盖`(@TempDir dir: File) {
        val first = DeviceTools.nextScreenshotFile(dir)
        assertEquals("screenshot_001.png", first.name)
        first.writeBytes(byteArrayOf(1))
        assertEquals("screenshot_002.png", DeviceTools.nextScreenshotFile(dir).name)
        File(dir, "screenshot_002.png").writeBytes(byteArrayOf(1))
        assertEquals("screenshot_003.png", DeviceTools.nextScreenshotFile(dir).name)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `工具清单含八个条目且分组正确`() {
        val tools = DeviceTools.Tool.entries
        assertEquals(7, tools.size) // logcat 流式工具独立于一次性清单
        assertTrue(tools.any { it.id == "push_launcher" && it.group == "设备" })
        assertTrue(tools.any { it.id == "start_3daa" && it.group == "模拟器" })
        assertTrue(tools.any { it.id == "remove_comment_slashes" && it.group == "实用" })
    }

    @Test
    fun `logcat 与截图命令组装`() {
        assertEquals(listOf("/sdk/adb", "logcat", "-c"), DeviceTools.clearLogcatArgs("/sdk/adb"))
        assertEquals(listOf("/sdk/adb", "logcat"), DeviceTools.logcatArgs("/sdk/adb"))
        assertEquals(listOf("/sdk/adb", "exec-out", "screencap", "-p"), DeviceTools.screenshotArgs("/sdk/adb"))
        assertNotNull(DeviceTools.logcatArgs("/sdk/adb"))
    }

    @Test
    fun `指定 serial 时命令插入 -s 参数`() {
        val cmds = DeviceTools.pushLauncherCommands("/sdk/adb", "/data/NsrLauncher.apk", serial = "emulator-5554")
        assertEquals(5, cmds.size)
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "root"), cmds[0])
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "wait-for-device"), cmds[1])
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "remount"), cmds[2])
        assertEquals(
            listOf("/sdk/adb", "-s", "emulator-5554", "push", "/data/NsrLauncher.apk", "/system_ext/priv-app/NsrLauncher"),
            cmds[3],
        )
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "reboot"), cmds[4])
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "shell", "ps", "-A"), DeviceTools.listProcessesArgs("/sdk/adb", "emulator-5554"))
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "shell", "kill", "1234"), DeviceTools.killPidArgs("/sdk/adb", "1234", "emulator-5554"))
        assertEquals(listOf("/sdk/adb", "-s", "192.168.1.8:5555", "logcat"), DeviceTools.logcatArgs("/sdk/adb", "192.168.1.8:5555"))
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "logcat", "-c"), DeviceTools.clearLogcatArgs("/sdk/adb", "emulator-5554"))
        assertEquals(listOf("/sdk/adb", "-s", "emulator-5554", "exec-out", "screencap", "-p"), DeviceTools.screenshotArgs("/sdk/adb", "emulator-5554"))
        assertEquals(listOf("/sdk/adb", "devices"), DeviceTools.devicesArgs("/sdk/adb"))
    }

    @Test
    fun `默认设备优先非无线条目`() {
        assertEquals("emulator-5554", DeviceTools.pickDefaultSerial(listOf("192.168.1.8:5555", "emulator-5554")))
        assertEquals("15564219080011W", DeviceTools.pickDefaultSerial(listOf("15564219080011W", "192.168.1.8:5555")))
        assertEquals("192.168.1.8:5555", DeviceTools.pickDefaultSerial(listOf("192.168.1.8:5555")))
        assertNull(DeviceTools.pickDefaultSerial(emptyList()))
    }
}
