package atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import kotlin.math.roundToInt

/** 字号与行距二级页：外观页只留一个入口，五组细调项集中在此（§6.4.25）。
 *  2026-10-04 自 SettingsView.kt 拆出为共享文件（Android 端复用；SettingsView.kt 本体被
 *  Android 构建排除）。 */

@Composable
fun TypographySettingsPage(store: AppStore, onBack: () -> Unit) {
    val ui = atlasUiTokens()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ui.spacing.page),
        verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
    ) {
        Column(
            Modifier.widthIn(max = 980.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ui.spacing.section),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "‹ 返回",
                    Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 14.sp,
                    color = Theme.Accent,
                )
                Text("字号与行距", style = ui.typography.pageTitle, color = Theme.MdH1)
            }

            SettingsSection("字号与行距", "拖动立即生效并预览；改动随仓库同步层配置走，多设备一致") {
                SettingsTypographyGroups(store)
            }
        }
    }
}

/** 全局字号 / 题面 / 内容 / 行距 / 标签五组的唯一实现：外观页入口摘要与二级页共用 */
@Composable
private fun SettingsTypographyGroups(store: AppStore) {
        SettingsGroup("全局文字大小", "11–20 sp 具体字号（14 sp 为基准），拖动后立即预览") {
            val size = store.settings.globalFontSize
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Slider(
                    value = size.toFloat(),
                    onValueChange = { store.settings = store.settings.copy(globalFontSize = it.roundToInt().coerceIn(11, 20)) },
                    onValueChangeFinished = { store.saveSettings() },
                    valueRange = 11f..20f,
                    steps = 8,
                    modifier = Modifier.weight(1f),
                )
                Text("$size sp", Modifier.width(54.dp), color = Theme.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        SettingsGroup("题库题面字号", "工作台题面与同源题库标题，10–18 sp") {
            val qSize = store.settings.questionFontSize
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Slider(
                    value = qSize.toFloat(),
                    onValueChange = { store.settings = store.settings.copy(questionFontSize = it.roundToInt().coerceIn(10, 18)) },
                    onValueChangeFinished = { store.saveSettings() },
                    valueRange = 10f..18f,
                    steps = 7,
                    modifier = Modifier.weight(1f),
                )
                Text("$qSize sp", Modifier.width(54.dp), color = Theme.Accent, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("预览", fontSize = 11.sp, color = Theme.Muted)
                Text("如何快速判断 Android 项目基于哪个版本？", fontSize = qSize.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val contentSize = store.settings.contentFontSize
        val lineHeightPct = store.settings.contentLineHeight
        SettingsGroup("题库内容字号", "答案、闪卡与预览的正文基准，10–24 sp；标题与代码按比例跟随") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Slider(
                    value = contentSize.toFloat(),
                    onValueChange = { store.settings = store.settings.copy(contentFontSize = it.roundToInt().coerceIn(10, 24)) },
                    onValueChangeFinished = { store.saveSettings() },
                    valueRange = 10f..24f,
                    steps = 13,
                    modifier = Modifier.weight(1f),
                )
                Text("$contentSize sp", Modifier.width(54.dp), color = Theme.Accent, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "两棵源码树都通过 version_defaults.mk 提供版本默认值。",
                fontSize = contentSize.sp,
                lineHeight = (contentSize * lineHeightPct / 100f).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SettingsGroup("内容行间距", "按默认行距的百分比（140% 为默认），作用于答案正文、列表与代码块") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Slider(
                    value = lineHeightPct.toFloat(),
                    onValueChange = { store.settings = store.settings.copy(contentLineHeight = (it.roundToInt() / 10 * 10).coerceIn(100, 220)) },
                    onValueChangeFinished = { store.saveSettings() },
                    valueRange = 100f..220f,
                    steps = 11,
                    modifier = Modifier.weight(1f),
                )
                Text("$lineHeightPct%", Modifier.width(54.dp), color = Theme.Accent, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "第一行预览：PLATFORM_VERSION_LAST_STABLE := 13\n第二行预览：拖大百分比后两行正文的间距随之变化。",
                fontSize = contentSize.sp,
                lineHeight = (contentSize * lineHeightPct / 100f).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SettingsGroup("题目标签字号", "只调整题库卡片的标签，10–18 sp") {
            val tagSize = store.settings.questionTagFontSize
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Slider(
                    value = tagSize.toFloat(),
                    onValueChange = { store.settings = store.settings.copy(questionTagFontSize = it.roundToInt().coerceIn(10, 18)) },
                    onValueChangeFinished = { store.saveSettings() },
                    valueRange = 10f..18f,
                    steps = 7,
                    modifier = Modifier.weight(1f),
                )
                Text("$tagSize sp", Modifier.width(54.dp), color = Theme.Accent, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("预览", fontSize = 11.sp, color = Theme.Muted)
                Text("#init  #SELinux", fontSize = tagSize.sp, color = Theme.Tag)
            }
        }
}
