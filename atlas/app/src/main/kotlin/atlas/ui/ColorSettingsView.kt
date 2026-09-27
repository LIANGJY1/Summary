package atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Log

/** Atlas 双模式预览与用户主题管理；唯一内置主题不提供编辑和删除入口。 */
@Composable
fun ColorSettingsPage(store: AppStore, onBack: () -> Unit) {
    val ui = atlasUiTokens()
    var editingTheme by remember { mutableStateOf<String?>(null) }
    var deletingTheme by remember { mutableStateOf<String?>(null) }
    var showCreateTheme by remember { mutableStateOf(false) }
    val custom = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }
    val dark = store.settings.darkTheme

    fun selectTheme(name: String) {
        store.settings = store.settings.copy(themeName = name)
        store.saveSettings()
        Log.i("切换配色主题 → $name")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ui.spacing.page),
        verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 980.dp),
            verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "‹ 返回",
                    Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 14.sp,
                    color = Theme.Accent,
                )
                Text("配色主题", style = ui.typography.pageTitle, color = Theme.MdH1)
            }

            SettingsSection("Atlas 基线", "以统一的语义色服务界面与 Markdown 阅读；浅色和深色独立调校。") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeSwatch(
                        label = "Atlas", sub = "浅色", spec = AtlasThemes.ATLAS.light,
                        active = !dark && store.settings.themeName == AtlasThemes.NAME,
                        onClick = { store.settings = store.settings.copy(theme = "light", themeName = AtlasThemes.NAME); store.saveSettings() },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeSwatch(
                        label = "Atlas", sub = "深色", spec = AtlasThemes.ATLAS.dark,
                        active = dark && store.settings.themeName == AtlasThemes.NAME,
                        onClick = { store.settings = store.settings.copy(theme = "dark", themeName = AtlasThemes.NAME); store.saveSettings() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SettingsSection("我的主题", "自定义主题会保存在本机；内置 Atlas 始终保留为安全基线。") {
                SettingsActionRow {
                    OutlinedButton(onClick = { showCreateTheme = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("基于 Atlas 新建", fontSize = 12.sp) }
                }
                if (custom.isEmpty()) {
                    Text("还没有自定义主题。可复制 Atlas 后分别调整浅色与深色。", fontSize = 11.sp, color = Theme.Muted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        custom.forEach { theme ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeSwatch(label = theme.name, sub = if (dark) "深色" else "浅色", spec = theme.spec(dark), active = store.settings.themeName == theme.name, onClick = { selectTheme(theme.name) }, modifier = Modifier.weight(1f))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("编辑", Modifier.clickable { editingTheme = theme.name }, fontSize = 12.sp, color = Theme.Accent)
                                    Text("删除", Modifier.clickable { deletingTheme = theme.name }, fontSize = 12.sp, color = Theme.BadRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateTheme) CreateThemeDialog(
        store = store,
        onCreated = { editingTheme = it },
        onDismiss = { showCreateTheme = false },
    )
    deletingTheme?.let { name -> DeleteThemeDialog(store, name) { deletingTheme = null } }
    editingTheme?.let { name ->
        ThemeEditDialog(store = store, themeName = name, onDismiss = { editingTheme = null })
    }
}
