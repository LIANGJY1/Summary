package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** USB 一键转无线的纯解析部分：`ip route` → wlan0 地址（§6.4.18） */
class FeishuCheckinUsbWirelessTest {

    @Test
    fun `解析 wlan0 的 src 地址`() {
        val output = """
            10.19.58.0/24 dev wlan0 proto kernel scope link src 10.19.58.23 metric 316
            192.168.58.0/24 dev rndis0 proto kernel scope link src 192.168.58.5
        """.trimIndent()
        assertEquals("10.19.58.23", FeishuCheckin.parseWlanIp(output))
    }

    @Test
    fun `多条 wlan0 路由取第一条`() {
        val output = """
            10.19.58.0/24 dev wlan0 proto kernel scope link src 10.19.58.23 metric 316
            10.19.59.0/24 dev wlan0 proto kernel scope link src 10.19.59.99 metric 406
        """.trimIndent()
        assertEquals("10.19.58.23", FeishuCheckin.parseWlanIp(output))
    }

    @Test
    fun `无 wlan0 路由或空输出返回 null`() {
        assertNull(FeishuCheckin.parseWlanIp("192.168.58.0/24 dev rndis0 proto kernel scope link src 192.168.58.5"))
        assertNull(FeishuCheckin.parseWlanIp(""))
        assertNull(FeishuCheckin.parseWlanIp("wlan0: error fetching interface"))
    }
}
