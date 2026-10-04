package atlas.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.CommandPalette
import atlas.core.Log
import atlas.migrateThemeSettings
import atlas.resolveTheme
import atlas.ui.AtlasTheme
import atlas.ui.ColorSettingsPage
import atlas.ui.LearningView
import atlas.ui.LocalMarkdownContentStyle
import atlas.ui.MarkdownContentStyle
import atlas.ui.PreviewDialog
import atlas.ui.QuestionSection
import atlas.ui.Theme
import atlas.ui.TypographySettingsPage
import atlas.ui.TodayView
import java.io.File

/** 手机端根：与桌面 Main.kt 的 main()/AppRoot 对应，但无窗口系统——
 *  底部导航替代顶部页签，系统返回键替代鼠标侧键。工具页为 PC 专属，不进手机端。 */
@Composable
fun AtlasRoot(configDir: File) {
    val store = remember { AppStore(configDir = configDir) }
    LaunchedEffect(Unit) {
        Log.timed("应用 boot()", warnMs = 1000) { runCatching { store.boot() }.onFailure { Log.e("boot() 异常", it) } }
        // 与桌面一致：boot() 读取持久化设置之后再迁移主题，避免覆盖成默认值
        migrateThemeSettings(store.settings).takeIf { it != store.settings }?.let { migrated ->
            store.settings = migrated
            store.saveSettings()
            Log.i("主题设置已迁移到双模式 V2")
        }
    }
    // 全局字号/内容行距经 CompositionLocal 进 markdown 渲染器，与桌面同机制
    CompositionLocalProviderSafe(store)
}

@Composable
private fun CompositionLocalProviderSafe(store: AppStore) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMarkdownContentStyle provides MarkdownContentStyle(store.settings.contentFontSize, store.settings.contentLineHeight),
    ) {
        AtlasTheme(
            dark = store.settings.darkTheme,
            fontScale = store.settings.globalFontSize / 14f,
            spec = resolveTheme(store.settings),
        ) {
            Surface(Modifier.fillMaxSize()) { AtlasApp(store) }
        }
    }
}

@Composable
private fun AtlasApp(store: AppStore) {
    if (!store.libraryReady) {
        SetupScreen(store)
        return
    }

    var tab by remember { mutableStateOf("工作台") }
    var learnSection by remember { mutableStateOf("复习") }
    var settingsSection by remember { mutableStateOf("root") }
    var showPalette by remember { mutableStateOf(false) }
    var previewRel by remember { mutableStateOf<String?>(null) }
    val inbox = store.candidates.size
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(store.pendingPreview) {
        store.pendingPreview?.let { rel ->
            Log.i("打开预览浮层 → $rel")
            previewRel = rel
            store.pendingPreview = null
        }
    }

    // 系统返回键 = 桌面的鼠标侧键/取消路径：预览/搜索 → 设置子页 → 页签归位 工作台
    BackHandler(
        enabled = previewRel != null || showPalette ||
            (tab == "设置" && settingsSection != "root") || tab != "工作台",
    ) {
        when {
            previewRel != null -> previewRel = null
            showPalette -> showPalette = false
            tab == "设置" && settingsSection != "root" -> settingsSection = "root"
            tab != "工作台" -> tab = "工作台"
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：品牌 + 搜索入口（对应桌面 Ctrl+K）
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.Panel)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Theme.Accent)
            Spacer(Modifier.weight(1f))
            Text(
                "搜索",
                Modifier.clickable { Log.i("搜索入口点击"); showPalette = true },
                fontSize = 13.sp,
                color = Theme.Muted,
            )
        }
        // 内容
        Box(Modifier.weight(1f)) {
            when (tab) {
                "学习" -> LearningView(store, learnSection) { learnSection = it }
                "题库" -> QuestionSection(store, rootFocus)
                "设置" -> when (settingsSection) {
                    "配色" -> ColorSettingsPage(store) { settingsSection = "root" }
                    "字号与行距" -> TypographySettingsPage(store) { settingsSection = "root" }
                    else -> AndroidSettingsView(
                        store,
                        onOpenColors = { settingsSection = "配色" },
                        onOpenTypography = { settingsSection = "字号与行距" },
                    )
                }
                else -> TodayView(store) { destination ->
                    when (destination) {
                        "学习" -> { learnSection = "复习"; tab = "学习" }
                        "题库" -> { tab = "题库" }
                        else -> tab = "工作台"
                    }
                }
            }
        }
        // 状态栏（与桌面同款语义）
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.Panel)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("条目 ${store.notes.size}", fontSize = 11.sp, lineHeight = 13.sp, color = Theme.Muted)
            Text("待确认 $inbox", fontSize = 11.sp, lineHeight = 13.sp, color = Theme.Muted)
            Spacer(Modifier.weight(1f))
            store.toast.value?.let { Text(it, fontSize = 11.sp, lineHeight = 13.sp, color = Theme.OkGreen) }
        }
        // 底部导航（触屏拇指热区；设置页签复位到根）
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.Panel)
                .padding(vertical = 4.dp),
        ) {
            listOf("工作台", "学习", "题库", "设置").forEach { t ->
                val active = tab == t
                BottomTab(
                    label = t,
                    active = active,
                    badge = if (t == "工作台" && inbox > 0) "$inbox" else null,
                    onClick = {
                        Log.i("页签切换 → $t")
                        if (t == "设置") settingsSection = "root"
                        tab = t
                    },
                )
            }
        }
    }

    previewRel?.let { rel -> PreviewDialog(store, rel) { previewRel = null } }
    if (showPalette) CommandPalette(store) { showPalette = false }
}

/** 底部导航页签：与桌面 NavTab 同视觉语言（胶囊高亮），等宽铺满便于点按 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomTab(label: String, active: Boolean, badge: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .weight(1f)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .background(
                when {
                    active -> Theme.Selected
                    hovered -> Theme.Hover
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp),
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) Theme.Accent else Theme.Muted,
        )
        badge?.let {
            Spacer(Modifier.width(5.dp))
            Text(
                it,
                Modifier
                    .background(Theme.WarnOrange, RoundedCornerShape(8.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                fontSize = 10.sp,
                color = Color.White,
            )
        }
    }
}
