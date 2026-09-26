package atlas

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
