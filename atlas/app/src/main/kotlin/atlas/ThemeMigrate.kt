package atlas

import atlas.core.AppSettings
import atlas.ui.AtlasThemes
import atlas.ui.CustomTheme
import atlas.ui.ThemeSpec

/** 合法旧自定义主题升级为 V2；未知内置名与坏记录安全回落到 Atlas。
 *  平台无关，原在桌面 Main.kt，Android 端复用（2026-10-04 拆出）。 */
internal fun migrateThemeSettings(s: AppSettings): AppSettings {
    val decoded = s.customThemes.mapNotNull { CustomTheme.decode(it) }
    val migrated = decoded.map { it.encode() }
    val customNames = decoded.map { it.name }.toSet()
    val builtInNames = AtlasThemes.ALL.map { it.name }.toSet()
    val selected = when {
        s.themeName in customNames -> s.themeName
        s.themeName in builtInNames -> s.themeName
        else -> AtlasThemes.DEFAULT.name
    }
    return s.copy(themeName = selected, customThemes = migrated)
}

/** 主题名 → [ThemeSpec]：同名自定义优先，其次内置主题，未知名称回退 Atlas。 */
internal fun resolveTheme(s: AppSettings): ThemeSpec {
    val name = s.themeName
    if (name.isNotBlank()) {
        s.customThemes.asSequence()
            .mapNotNull { CustomTheme.decode(it) }
            .firstOrNull { it.name == name }
            ?.let { return it.spec(s.darkTheme) }
        AtlasThemes.ALL.firstOrNull { it.name == name }?.let { return it.spec(s.darkTheme) }
    }
    return AtlasThemes.DEFAULT.spec(s.darkTheme)
}
