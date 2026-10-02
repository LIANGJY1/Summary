package atlas

import atlas.core.AppSettings
import atlas.core.SettingsStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 双层配置（PRD §6.4.23）：本机层=引导与设备相关键；仓库同步层=其余用户配置，随库所在 git 仓库同步 */
class SettingsLayeredTest {
    @TempDir lateinit var tmp: File

    private fun syncedFile(libraryPath: String) = File(libraryPath, "atlas/config/settings.properties")

    @Test
    fun `仓库层缺失时本机层旧全量配置原地生效（迁移语义）`() {
        val local = File(tmp, "local/settings.properties")
        val store = SettingsStore(local)
        store.save(AppSettings(theme = "dark", themeName = "雪白", libraryPath = "/home/a/kb"))
        // 仓库层尚未落盘
        val loaded = store.load { syncedFile(it) }
        assertEquals("/home/a/kb", loaded.libraryPath)
        assertEquals("dark", loaded.theme)
        assertEquals("雪白", loaded.themeName)
        assertFalse(syncedFile("/home/a/kb").exists())
    }

    @Test
    fun `仓库层覆盖同步键但本机键以本机层为准`() {
        val local = File(tmp, "local/settings.properties")
        val kb = File(tmp, "kb").apply { mkdirs() }
        val store = SettingsStore(local)
        store.save(AppSettings(theme = "dark", globalFontSize = 15, libraryPath = kb.absolutePath, selectedSourcePath = "/device/only.md"))
        // 另一台设备的仓库层（模拟 git pull 下来）：主题与字号与本机不同；libraryPath 是本机键不应被仓库层携带
        val synced = syncedFile(kb.absolutePath)
        val other = SettingsStore(File(tmp, "other/settings.properties"))
        other.save(AppSettings(theme = "light", globalFontSize = 13, libraryPath = "/other/device/kb"))
        // other 的本机文件是全量（未分流），直接把它当作仓库层文件会夹带 libraryPath ——
        // 用真实分流流程重新生成：让 other 以本机自己的 kb 路径分流保存
        other.save(AppSettings(theme = "light", globalFontSize = 13, libraryPath = kb.absolutePath), synced)

        val loaded = store.load { syncedFile(it) }
        assertEquals("light", loaded.theme) // 同步键：仓库层胜
        assertEquals(13, loaded.globalFontSize)
        assertEquals(kb.absolutePath, loaded.libraryPath) // 本机键：不被覆盖
        assertEquals("/device/only.md", loaded.selectedSourcePath)
    }

    @Test
    fun `保存后两层各含各的键`() {
        val local = File(tmp, "local/settings.properties")
        val kb = File(tmp, "kb").apply { mkdirs() }
        val store = SettingsStore(local)
        store.save(
            AppSettings(theme = "dark", customThemes = emptyList(), libraryPath = kb.absolutePath, feishuIp = "10.0.0.2"),
            syncedFile(kb.absolutePath),
        )
        val localText = local.readText()
        val syncedText = syncedFile(kb.absolutePath).readText()
        assertTrue("libraryPath" in localText)
        assertTrue("feishuIp" in localText)
        assertFalse("theme=" in localText, "同步键不得留在本机层")
        assertTrue("theme" in syncedText)
        assertFalse("libraryPath" in syncedText, "本机键不得进仓库层")
        assertFalse("feishuIp" in syncedText)
        // 回读：两层合并还原
        val loaded = store.load { syncedFile(it) }
        assertEquals("dark", loaded.theme)
        assertEquals(kb.absolutePath, loaded.libraryPath)
        assertEquals("10.0.0.2", loaded.feishuIp)
    }

    @Test
    fun `旧 fontScale 百分比键迁移为具体全局字号`() {
        val local = File(tmp, "local/settings.properties")
        local.parentFile.mkdirs()
        // 110% 按 14sp 基准换算 = 15sp；新键存在时优先生效
        local.writeText("fontScale=1.1\nglobalFontSize=16\n")
        assertEquals(16, SettingsStore(local).load().globalFontSize)
        local.writeText("fontScale=0.9\n")
        assertEquals(13, SettingsStore(local).load().globalFontSize)
        assertEquals(14, SettingsStore(File(tmp, "empty/settings.properties")).load().globalFontSize)
    }
}
