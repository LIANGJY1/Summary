package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import atlas.AppStore
import atlas.core.Log

/** 主题色板与主题增删改对话框：桌面设置页与 Android 配色页共用。
 *  2026-10-04 自 SettingsView.kt 拆出（SettingsView.kt 含 AWT 依赖、Android 构建整体排除）。 */

/** 主题色板：一块双色预览 + 名称。 */
@Composable
internal fun ThemeSwatch(
    label: String,
    sub: String,
    spec: ThemeSpec,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(min = 120.dp, max = 260.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (active) Theme.Selected else Color.Transparent)
            .border(
                1.dp,
                if (active) Theme.Accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp).clip(MaterialTheme.shapes.small)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(spec.background))
            Box(Modifier.weight(1f).fillMaxHeight().background(spec.surface))
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small)) {
            listOf(spec.mdH1, spec.mdH2, spec.mdLink, spec.okGreen, spec.mdInlineCode)
                .forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(c)) }
        }
        Text(
            label,
            fontSize = 12.sp,
            color = if (active) Theme.Accent else Theme.Muted,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, color = Theme.Muted, maxLines = 1)
    }
}

/** 自定义主题编辑器：浅色、深色分别编辑，完整 V2 记录始终原子写回。 */
@Composable
internal fun ThemeEditDialog(store: AppStore, themeName: String, onDismiss: () -> Unit) {
    val custom = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }
    val target = custom.firstOrNull { it.name == themeName }
    if (target == null) {
        onDismiss()
        return
    }
    var draft by remember(target.name) { mutableStateOf(target) }
    var editingDark by remember(target.name) { mutableStateOf(store.settings.darkTheme) }
    val ui = atlasUiTokens()
    var editing by remember(target.name, editingDark) { mutableStateOf<List<String>?>(null) }
    var problem by remember(target.name, editingDark) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val spec = draft.spec(editingDark)
    val hexes = editing ?: spec.hexList()

    fun put(index: Int, raw: String) {
        val next = hexes.toMutableList()
        next[index] = sanitizeHexInput(raw)
        editing = next
        val bad = next.withIndex().firstOrNull { (i, h) -> h.isNotEmpty() && parseHexColor(h) == null }
        if (bad != null) {
            problem = "${ThemeSpec.LABELS[bad.index]}：色值格式不对（需 #RRGGBB 或 #AARRGGBB）"
            return
        }
        val blank = next.indexOfFirst { it.isBlank() }
        if (blank >= 0) {
            problem = "${ThemeSpec.LABELS[blank]} 是必填项，不能留空"
            return
        }
        val parsed = ThemeSpec.fromHexList(next)
        if (parsed == null) {
            problem = "配色不完整，暂未保存"
            return
        }
        problem = null
        draft = if (editingDark) draft.copy(dark = parsed) else draft.copy(light = parsed)
        store.settings = store.settings.copy(
            customThemes = store.settings.customThemes.map { raw2 ->
                if (CustomTheme.nameOf(raw2) == target.name) draft.encode() else raw2
            },
        )
        store.saveSettings()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(520.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("编辑主题 · ${target.name}", style = ui.typography.sectionTitle, color = Theme.MdH1)
                Text("浅色与深色独立保存；改动立即生效。", fontSize = 11.sp, color = Theme.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "浅色", true to "深色").forEach { (dark, label) ->
                        val active = editingDark == dark
                        Text(
                            label,
                            Modifier
                                .clickable {
                                    editingDark = dark
                                    editing = null
                                    problem = null
                                }
                                .background(
                                    if (active) Theme.Selected else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            color = if (active) Theme.Accent else Theme.Muted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                var group by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("界面配色" to 0, "Markdown 配色" to 1).forEach { (label, idx) ->
                        Text(
                            label,
                            Modifier
                                .clickable { group = idx }
                                .background(
                                    if (group == idx) Theme.Selected else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            fontSize = 13.sp,
                            color = if (group == idx) Theme.Accent else Theme.Muted,
                        )
                    }
                }
                Text(
                    if (group == 0) "三级表面与状态色按用途命名。"
                    else "标题、链接、引用与代码分别使用独立语义色。",
                    fontSize = 11.sp, color = Theme.Muted,
                )
                if (problem != null) {
                    Text(
                        problem!!,
                        fontSize = 12.sp,
                        color = Theme.BadRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Theme.BadRed.copy(alpha = 0.10f), MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                val from = if (group == 0) 0 else ThemeSpec.BASE
                val to = if (group == 0) ThemeSpec.BASE else ThemeSpec.SIZE
                Column(Modifier.height(300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (from until to).forEach { i ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(ThemeSpec.LABELS[i], Modifier.width(76.dp), fontSize = 13.sp)
                            val parsed = remember(hexes[i]) { parseHexColor(hexes[i]) }
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(parsed ?: Color.Transparent)
                                    .border(1.dp, Theme.Muted.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                    .clickable { picking = i },
                            )
                            OutlinedTextField(
                                value = hexes[i],
                                onValueChange = { put(i, it) },
                                singleLine = true,
                                modifier = Modifier.width(132.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        store.settings = store.settings.copy(
                            customThemes = store.settings.customThemes.filterNot {
                                CustomTheme.nameOf(it) == target.name
                            },
                            themeName = if (store.settings.themeName == target.name) AtlasThemes.NAME else store.settings.themeName,
                        )
                        store.saveSettings()
                        Log.i("删除自定义主题 → ${target.name}")
                        onDismiss()
                    }) { Text("删除该主题", color = Theme.BadRed) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
    picking?.let { idx ->
        val here = parseHexColor(hexes[idx]) ?: spec.hexList().getOrNull(idx)?.let(::parseHexColor) ?: Color.White
        ColorPickerDialog(
            title = "${ThemeSpec.LABELS[idx]} · ${target.name}",
            initial = here,
            themeColors = spec.hexList().mapNotNull(::parseHexColor).distinct(),
            onPick = { picked ->
                put(idx, hexOf(picked).removePrefix("#"))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

internal fun createCustomTheme(name: String): CustomTheme =
    CustomTheme(name.trim(), AtlasThemes.ATLAS.light, AtlasThemes.ATLAS.dark)

/** 新建自定义主题：固定复制 Atlas 的完整浅深双模式。 */
@Composable
internal fun CreateThemeDialog(store: AppStore, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val existing = store.settings.customThemes.mapNotNull { CustomTheme.decode(it) }.map { it.name }.toSet()
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val reserved = trimmed in AtlasThemes.ALL.map { it.name }
    val dup = trimmed.isNotEmpty() && (trimmed in existing || reserved)
    val valid = trimmed.isNotEmpty() && !dup

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(460.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("新建自定义主题", style = atlasUiTokens().typography.sectionTitle, color = Theme.Accent)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("主题名称") },
                    isError = dup,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dup) Text("主题名称已被占用或属于内置主题，请换一个名字", fontSize = 11.sp, color = Theme.BadRed)
                Text("将复制 Atlas 的浅色与深色配色，创建后可分别编辑。", fontSize = 12.sp, color = Theme.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeSwatch("Atlas", "浅色", AtlasThemes.ATLAS.light, false, {}, Modifier.weight(1f))
                    ThemeSwatch("Atlas", "深色", AtlasThemes.ATLAS.dark, false, {}, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        enabled = valid,
                        onClick = {
                            val theme = createCustomTheme(trimmed)
                            store.settings = store.settings.copy(
                                customThemes = store.settings.customThemes + theme.encode(),
                                themeName = trimmed,
                            )
                            store.saveSettings()
                            Log.i("基于 Atlas 新建自定义主题 → $trimmed")
                            onCreated(trimmed)
                            onDismiss()
                        },
                    ) { Text("创建") }
                }
            }
        }
    }
}

/** 删除自定义主题的二次确认。 */
@Composable
internal fun DeleteThemeDialog(store: AppStore, name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(400.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("删除主题", style = atlasUiTokens().typography.sectionTitle, color = Theme.BadRed)
                Text("确定删除「$name」？该操作不可撤销。", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        store.settings = store.settings.copy(
                            customThemes = store.settings.customThemes.filterNot {
                                CustomTheme.nameOf(it) == name
                            },
                            themeName = if (store.settings.themeName == name) AtlasThemes.NAME else store.settings.themeName,
                        )
                        store.saveSettings()
                        Log.i("删除自定义主题 → $name")
                        onDismiss()
                    }) { Text("删除", color = Theme.BadRed) }
                }
            }
        }
    }
}
