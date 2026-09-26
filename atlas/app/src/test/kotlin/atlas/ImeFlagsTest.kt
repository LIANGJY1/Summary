package atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImeFlagsTest {

    @Test
    fun `注入 JBR 新版 XIM 客户端开关`() {
        installImeCompatFlags()
        assertEquals("true", System.getProperty("jb.awt.newXimClient.enabled"))
        assertEquals("true", System.getProperty("jb.awt.newXimClient.preferBelowTheSpot"))
    }

    @Test
    fun `重复注入幂等且不覆盖外部显式设置`() {
        System.setProperty("jb.awt.newXimClient.enabled", "false")
        try {
            installImeCompatFlags()
            installImeCompatFlags()
            assertEquals("false", System.getProperty("jb.awt.newXimClient.enabled"), "外部显式设置应保留")
            assertEquals("true", System.getProperty("jb.awt.newXimClient.preferBelowTheSpot"))
        } finally {
            System.clearProperty("jb.awt.newXimClient.enabled")
        }
    }

    /** 用了反射探测 JDK 内部类，必须保证在任何运行时上都不抛异常——它跑在 main() 的启动路径上。 */
    @Test
    fun `运行时能力探测不抛异常`() {
        logImeRuntimeCapability()
        assertTrue(true)
    }
}
