package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页通用小件：桌面设置页与 Android 设置页共用（2026-10-04 自 SettingsView.kt 拆出，
 *  因 SettingsView.kt 含 JFileChooser 等 AWT 依赖、Android 构建整体排除本文件不复可用）。 */

/** 主设置页通往子页的一行入口：左侧色块 + 标题 + 当前值 + 右箭头。 */
@Composable
internal fun SettingsEntryRow(
    title: String,
    subtitle: String,
    swatch: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp)
                .background(swatch, RoundedCornerShape(6.dp))
                .border(1.dp, Theme.Muted.copy(alpha = 0.45f), RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = Theme.Muted)
        }
        Text("›", fontSize = 20.sp, color = Theme.Muted)
    }
}

/** 设置分区：用单层容器承载一组相关控件，避免设置页出现层层嵌套的卡片。 */
@Composable
internal fun SettingsSection(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val ui = atlasUiTokens()
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Theme.Elevated,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = ui.typography.sectionTitle, color = Theme.MdH2)
            if (!description.isNullOrBlank()) Text(description, fontSize = 12.sp, color = Theme.Muted)
            content()
        }
    }
}

/** 二级页内的小节组：仅标题 + 描述，无底色容器。 */
@Composable
internal fun SettingsGroup(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Theme.MdH2)
        if (!description.isNullOrBlank()) Text(description, fontSize = 11.sp, color = Theme.Muted)
        content()
    }
}

@Composable
internal fun SettingsActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
