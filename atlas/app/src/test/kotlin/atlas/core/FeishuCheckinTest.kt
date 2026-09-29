package atlas.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FeishuCheckinTest {

    // ---------- adb devices 解析 ----------

    @Test
    fun `解析 devices 输出区分 USB 与无线在线状态`() {
        val output = """
            List of devices attached
            15564219080011W	device usb:1-8 product:PD2136 model:V2136A
            10.9.3.138:5555	device product:PD2136
            daemon not running; starting now at tcp:5037

        """.trimIndent()
        val devices = FeishuCheckin.parseDevices(output)
        assertEquals(2, devices.size)
        assertEquals("15564219080011W" to "device", devices[0])
        assertEquals("10.9.3.138:5555" to "device", devices[1])
    }

    @Test
    fun `usbSerial 排除无线条目且忽略非在线状态`() {
        val output = """
            List of devices attached
            10.9.3.138:5555	device
            15564219080011W	unauthorized

        """.trimIndent()
        assertNull(FeishuCheckin.usbSerial(output))

        val online = """
            List of devices attached
            10.9.3.138:5555	device
            15564219080011W	device

        """.trimIndent()
        assertEquals("15564219080011W", FeishuCheckin.usbSerial(online))
    }

    // ---------- bounds 与 dump XML 解析 ----------

    @Test
    fun `bounds 换算中心点且坏格式返回 null`() {
        assertEquals(540 to 1960, FeishuCheckin.parseBoundsCenter("[420,1923][660,1998]"))
        assertEquals(540 to 1188, FeishuCheckin.parseBoundsCenter("[0,0][1080,2376]"))
        assertNull(FeishuCheckin.parseBoundsCenter("[420,1923]"))
        assertNull(FeishuCheckin.parseBoundsCenter(""))
    }

    @Test
    fun `解析真实签到页 dump 提取文本与中心坐标`() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.ss.android.lark"
                    content-desc="飞书的标题栏。" checkable="false" clickable="false" enabled="true"
                    password="false" selected="false" bounds="[0,0][1080,2376]" />
              <node index="1" text="考勤签到" resource-id="" class="android.widget.Button" package="com.ss.android.lark"
                    content-desc="" checkable="false" clickable="true" enabled="true"
                    password="false" selected="false" bounds="[420,1923][660,1998]" />
              <node index="2" text="" resource-id="" class="android.widget.TextView" package="com.ss.android.lark"
                    content-desc="办公Wi-Fi" checkable="false" clickable="false" enabled="true"
                    password="false" selected="false" bounds="[489,2190][645,2232]" />
            </hierarchy>
        """.trimIndent()
        val nodes = FeishuCheckin.parseDump(xml)
        assertEquals(3, nodes.size)
        assertEquals(FeishuCheckin.UiNode("", "飞书的标题栏。", 540, 1188), nodes[0])
        assertEquals(540 to 1960, FeishuCheckin.findNode(nodes, "考勤签到")?.let { it.cx to it.cy })
        assertEquals(567 to 2211, FeishuCheckin.findNode(nodes, "办公Wi-Fi")?.let { it.cx to it.cy })
    }

    @Test
    fun `坏 XML 与缺 bounds 的节点安全跳过`() {
        assertTrue(FeishuCheckin.parseDump("<hierarchy><node text='x'").isEmpty())
        val xml = """
            <hierarchy>
              <node text="无坐标" content-desc="" bounds="" />
              <node text="正常" content-desc="" bounds="[0,0][10,10]" />
            </hierarchy>
        """.trimIndent()
        assertEquals(listOf(FeishuCheckin.UiNode("正常", "", 5, 5)), FeishuCheckin.parseDump(xml))
    }

    // ---------- findNode 语义（照抄 python _tap_node） ----------

    @Test
    fun `findNode 单次遍历按文档序取第一个命中`() {
        val nodes = listOf(
            FeishuCheckin.UiNode("取消自动更新", "", 10, 10),
            FeishuCheckin.UiNode("取消", "", 20, 20),
        )
        // partial=true：包含即命中，先到先得（python 同语义）
        assertEquals(10 to 10, FeishuCheckin.findNode(nodes, "取消")?.let { it.cx to it.cy })
        // partial=false：必须精确相等
        assertEquals(20 to 20, FeishuCheckin.findNode(nodes, "取消", partial = false)?.let { it.cx to it.cy })
    }

    @Test
    fun `findNode 同时匹配 text 与 content-desc`() {
        val nodes = listOf(FeishuCheckin.UiNode("", "进入考勤系统", 100, 200))
        assertEquals(100 to 200, FeishuCheckin.findNode(nodes, "考勤系统")?.let { it.cx to it.cy })
        assertNull(FeishuCheckin.findNode(nodes, "不存在"))
    }

    // ---------- 弹窗与会话过期检测 ----------

    @Test
    fun `USB 已连接弹窗要求精确文本匹配`() {
        val popup = listOf(FeishuCheckin.UiNode("USB 已连接", "", 540, 300), FeishuCheckin.UiNode("取消", "", 540, 500))
        assertTrue(FeishuCheckin.hasUsbConnectedPopup(popup))
        assertFalse(FeishuCheckin.hasUsbConnectedPopup(listOf(FeishuCheckin.UiNode("USB 已连接的设备列表", "", 0, 0))))
    }

    @Test
    fun `签到超时弹窗按包含匹配`() {
        val dialog = listOf(
            FeishuCheckin.UiNode("签到超时,请重新签到", "", 540, 1030),
            FeishuCheckin.UiNode("我知道了", "", 540, 1202),
        )
        assertTrue(FeishuCheckin.hasCheckinTimeout(dialog))
        assertFalse(FeishuCheckin.hasCheckinTimeout(listOf(FeishuCheckin.UiNode("打卡成功", "", 540, 900))))
        assertEquals(540 to 1202, FeishuCheckin.findNode(dialog, "我知道了")?.let { it.cx to it.cy })
    }

    // ---------- PIN 脱敏与 PIN 文件 ----------

    @Test
    fun `命令日志打码 KEYCODE 数字序列`() {
        assertEquals(
            "adb shell input keyevent KEYCODE_* KEYCODE_*",
            FeishuCheckin.maskPinInCommand("adb shell input keyevent KEYCODE_1 KEYCODE_2"),
        )
        assertEquals("input tap 540 1960", FeishuCheckin.maskPinInCommand("input tap 540 1960"))
    }

    @Test
    fun `PIN 文件写读一致且权限为 0600`(@TempDir dir: File) {
        FeishuCheckin.writePin(dir, "123963")
        assertEquals("123963", FeishuCheckin.readPin(dir))
        val perms = java.nio.file.Files.getPosixFilePermissions(FeishuCheckin.pinFile(dir).toPath())
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
        assertEquals("", FeishuCheckin.readPin(File(dir, "nope")))
    }
}
