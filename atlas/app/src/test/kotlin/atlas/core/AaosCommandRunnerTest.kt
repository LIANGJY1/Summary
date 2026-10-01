package atlas.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AaosCommandRunnerTest {

    @Test
    fun `合并流式输出并保留退出码`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val runner = AaosCommandRunner(scope)
            assertNull(runner.start("printf 'hello\\n'; printf 'error\\n' >&2; exit 7", "test-device", "/usr/bin/adb"))
            withTimeout(5_000) { while (runner.state.value.running) delay(10) }
            assertEquals(7, runner.state.value.exitCode)
            assertTrue(runner.state.value.lines.any { it.contains("hello") })
            assertTrue(runner.state.value.lines.any { it.contains("error") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `停止命令会结束子进程`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val runner = AaosCommandRunner(scope)
            assertNull(runner.start("sleep 30 & echo CHILD_PID:$!; wait", "test-device", "/usr/bin/adb"))
            val pidLine = withTimeout(5_000) {
                while (true) {
                    runner.state.value.lines.firstOrNull { it.startsWith("CHILD_PID:") }?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            val childPid = pidLine.substringAfter(':').toLong()
            runner.stop()
            withTimeout(5_000) {
                while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)) delay(10)
            }
            assertEquals(-2, runner.state.value.exitCode)
            assertTrue(!runner.state.value.running)
        } finally {
            scope.cancel()
        }
    }
}
