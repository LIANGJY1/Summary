package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AaosDebugTest {

    @Test
    fun `预设只包含设备调试命令`() {
        assertTrue(AaosDebug.presets.isNotEmpty())
        assertTrue(AaosDebug.presets.all { it.command.startsWith("adb ") })
    }

    @Test
    fun `命令环境固定所选设备并优先使用系统 adb`() {
        val env = AaosDebug.commandEnvironment("emulator-5554", "/sdk/platform-tools/adb", "/usr/bin")
        assertEquals("emulator-5554", env["ANDROID_SERIAL"])
        assertEquals("/sdk/platform-tools:/usr/bin", env["PATH"])
    }
}
