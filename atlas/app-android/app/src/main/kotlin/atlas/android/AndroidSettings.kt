package atlas.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Log
import atlas.platform.Platform
import atlas.resolveTheme
import atlas.ui.SettingsEntryRow
import atlas.ui.SettingsSection
import atlas.ui.Theme
import atlas.ui.atlasUiTokens
import java.io.File

/** 手机端设置页：桌面 SettingsView 的子集（录屏/设备工具箱/隐私目录等 PC 专属项不进手机端）。
 *  复用共享的 SettingsSection/SettingsEntryRow 骨架与配色、字号二级页。 */
@Composable
fun AndroidSettingsView(store: AppStore, onOpenColors: () -> Unit, onOpenTypography: () -> Unit) {
    var showBrowser by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = atlasUiTokens().typography.pageTitle, color = Theme.MdH1)

        SettingsSection("知识库", "手机端与桌面端共用同一份库文件，同步方式自选（git 客户端 / Syncthing / adb push）") {
            Text(store.settings.libraryPath, fontSize = 12.sp, color = Theme.Info)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showBrowser = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                ) { Text("切换目录") }
                OutlinedButton(
                    onClick = { store.rescan(full = false) },
                    enabled = !store.scanning.value,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                ) { Text(if (store.scanning.value) "扫描中…" else "重新扫描") }
            }
            store.scanMessage.value.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 11.sp, color = Theme.Muted)
            }
        }

        SettingsSection("外观", null) {
            SettingsEntryRow(
                "配色主题",
                store.settings.themeName.ifBlank { "Atlas" },
                resolveTheme(store.settings).accent,
                onOpenColors,
            )
            SettingsEntryRow("字号与行距", "全局 / 题面 / 内容字号与行距", Theme.Accent, onOpenTypography)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("深色模式", Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = store.settings.darkTheme,
                    onCheckedChange = { dark ->
                        store.settings = store.settings.copy(theme = if (dark) "dark" else "light")
                        store.saveSettings()
                        Log.i("深色模式 → $dark")
                    },
                )
            }
        }

        SettingsSection("关于", null) {
            Text("Atlas Android · 与桌面端共享核心代码（atlas/app）", fontSize = 12.sp, color = Theme.Muted)
            Text("检索、复习调度全部本地；出题、批改等 AI 能力由你的编码代理完成。", fontSize = 12.sp, color = Theme.Muted)
            Text(
                "手机端暂不支持：工具页（adb / 飞书打卡 / 录屏）、系统编辑器打开、git 改动标记。",
                fontSize = 12.sp, color = Theme.Muted, lineHeight = 18.sp,
            )
        }
    }

    if (showBrowser) {
        DirectoryBrowserDialog(
            initial = store.settings.libraryPath.ifBlank { Platform.defaultLibraryPath },
            onDismiss = { showBrowser = false },
            onPicked = { picked ->
                showBrowser = false
                try {
                    if (File(picked).isDirectory) store.openLibrary(picked, rescanIfNeeded = true)
                    else { Log.w("切换库失败：目录不存在 $picked"); store.showToast("目录不存在") }
                } catch (e: Exception) {
                    Log.e("切换库异常 path=$picked", e)
                    store.showToast("打开失败：${e.message}")
                }
            },
        )
    }
}
