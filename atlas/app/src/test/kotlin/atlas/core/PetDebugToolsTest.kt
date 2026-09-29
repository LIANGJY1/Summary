package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PetDebugToolsTest {

    @Test
    fun `settings payload keeps remote quotes and selected serial`() {
        assertEquals(
            listOf(
                "/sdk/adb", "-s", "usb-01", "shell", "settings", "put", "global",
                "pet_debug_event", "\"send_failed 14\"",
            ),
            PetDebugTools.settingsCommand("/sdk/adb", "usb-01", "send_failed 14").args,
        )
    }

    @Test
    fun `ipc command uses explicit receiver so a stopped test app starts`() {
        assertEquals(
            listOf(
                "/sdk/adb", "-s", "usb-01", "shell", "am", "broadcast",
                "-n", "com.yadea.petipctest/.AdbReceiver",
                "-a", "com.yadea.petipctest.SEND", "--ei", "event", "14",
            ),
            PetDebugTools.ipcCommand("/sdk/adb", "usb-01", 14).args,
        )
    }

    @Test
    fun `scenario catalog covers all 98 rows in source order`() {
        val cases = PetDebugTools.scenarios
        assertEquals(98, cases.size)
        assertEquals((1..98).toList(), cases.map { it.number })
        assertEquals("first boot default on -> show only, wait ivi ready", cases.first().name)
        assertEquals("long idle ignored when switch off", cases.last().name)
        assertEquals(98, cases.map { it.name }.distinct().size)
    }

    @Test
    fun `scenario sequences compile to commands waits or explicit limitations`() {
        PetDebugTools.scenarios.forEach { scenario ->
            val steps = PetDebugTools.scenarioSteps(scenario, "/sdk/adb", "usb-01")
            assertTrue(steps.isNotEmpty() || scenario.limitation != null, "case ${scenario.number} has no executable steps or limitation")
        }
        assertTrue(PetDebugTools.scenarios.single { it.number == 2 }.limitation != null)
        assertTrue(PetDebugTools.scenarios.single { it.number == 76 }.limitation != null)
    }

    @Test
    fun `quick action catalog exposes every injection family and environment setup`() {
        val ids = PetDebugTools.quickActions.map { it.id }.toSet()
        assertTrue(setOf(
            "fresh_on", "clear_logcat", "connect_ipc", "weather_none", "send_failed_opening",
            "birthday_ipc", "festival_spring_ipc", "play_start", "play_done", "media_play", "media_pause",
        ).all { it in ids })
    }

    @Test
    fun `quick actions include all fallback recommendation injections and failed action codes`() {
        val sequences = PetDebugTools.quickActions.map { it.sequence }.toSet()
        assertTrue("pet birthday" in sequences)
        (0..5).forEach { assertTrue("pet festival $it" in sequences, "missing festival $it") }
        listOf(0, 1, 2, 3, 4, 5, 6, 11, 12, 13, 14, 70, 80, 90, 100).forEach { code ->
            assertTrue("pet send_failed $code" in sequences, "missing send_failed $code")
        }
    }
}
