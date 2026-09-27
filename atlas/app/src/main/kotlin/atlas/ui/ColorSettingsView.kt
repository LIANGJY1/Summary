package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Log

/**
 * 配色二级页。主题画廊 11 套 ×3 列 + 自定义主题列表在主设置页会占掉大半屏，
 * 把它们连同增删改一起收进这里，主页面只留一行入口。
 */
@Composable
fun ColorSettingsPage(store: AppStore, onBack: () -> Unit) {
    val ui = atlasUiTokens()
    var editingTheme by remember { mutableStateOf<String?>(null) }
    var deletingTheme by remember { mutableStateOf<String?>(null) }
    var showCreateTheme by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxWidth().widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(ui.spacing.section)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "‹ 返回",
                    Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 14.sp, color = Theme.Accent,
                )
                Text("配色主题", style = ui.typography.pageTitle)
            }

                Text("配色主题", fontWeight = FontWeight.SemiBold)
                val custom = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }
                val dark = store.settings.darkTheme
                fun selectTheme(name: String) {
                    store.settings = store.settings.copy(themeName = name)
                    store.saveSettings()
                    Log.i("切换配色主题 → $name")
                }

                Text("内置 ${AtlasThemes.ALL.size} 套", fontSize = 12.sp, color = Theme.Muted)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AtlasThemes.ALL.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { entry ->
                                ThemeSwatch(
                                    label = entry.name,
                                    sub = if (dark) "深色" else "浅色",
                                    spec = AtlasThemes.specOf(entry.name, dark),
                                    active = store.settings.themeName == entry.name,
                                    onClick = { selectTheme(entry.name) },
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("我的主题", fontSize = 12.sp, color = Theme.Muted, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { showCreateTheme = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("新建", fontSize = 12.sp) }
                }
                if (custom.isEmpty()) {
                    Text("还没有自定义主题。点「新建」可基于当前配色生成，命名后永久保留。",
                        fontSize = 11.sp, color = Theme.Muted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        custom.forEach { ct ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ThemeSwatch(
                                    label = ct.name,
                                    sub = "",
                                    spec = ct.spec,
                                    active = store.settings.themeName == ct.name,
                                    onClick = { selectTheme(ct.name) },
                                    modifier = Modifier.weight(1f),
                                )
                                Column {
                                    Text("编辑", Modifier.clickable { editingTheme = ct.name },
                                        fontSize = 12.sp, color = Theme.Accent)
                                    Text("删除", Modifier.clickable { deletingTheme = ct.name },
                                        fontSize = 12.sp, color = Theme.BadRed)
                                }
                            }
                        }
                    }
                }
                Text("点选即生效并记住；自定义主题保存在设置文件里，重启仍在。",
                    fontSize = 11.sp, color = Theme.Muted)

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
