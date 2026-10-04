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
import atlas.ui.MobileKnowledgeReader
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
            if (store.libraryReady) {
                store.saveSettings()
                Log.i("主题设置已迁移到双模式 V2")
            } else {
                // 首次启动尚未打开知识库时，仓库同步配置目录可能不存在，且 Android
                // 还未获得共享存储权限。先在内存中迁移；openLibrary() 成功选择库后会保存。
                Log.i("主题设置已在内存中迁移；打开知识库后再持久化")
            }
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

    var tab by remember { mutableStateOf("浏览") }
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

    // 系统返回键：预览/搜索 → 设置子页 → 知识库阅读首页
    BackHandler(
        enabled = previewRel != null || showPalette ||
            (tab == "设置" && settingsSection != "root") || tab != "浏览",
    ) {
        when {
            previewRel != null -> previewRel = null
            showPalette -> showPalette = false
            tab == "设置" && settingsSection != "root" -> settingsSection = "root"
            tab != "浏览" -> tab = "浏览"
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 简洁品牌栏；文档搜索放在阅读页内。
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.Panel)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Theme.Accent)
        }
        // 内容
        Box(Modifier.weight(1f)) {
            when (tab) {
                "浏览" -> MobileKnowledgeReader(store)
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
        // 手机主导航只保留知识库阅读、题目浏览和显示设置。
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.Panel)
                .padding(vertical = 4.dp),
        ) {
            listOf("浏览", "题库", "设置").forEach { t ->
                val active = tab == t
                BottomTab(
                    label = t,
                    active = active,
                    badge = null,
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
