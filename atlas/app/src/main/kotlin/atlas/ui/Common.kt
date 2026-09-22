package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 应用自定义色板：MaterialTheme 之外业务用到的语义色，随明暗主题切换 */
data class AtlasPalette(
    val accent: Color, val okGreen: Color, val warnOrange: Color,
    val badRed: Color, val muted: Color, val codeBg: Color,
)

internal data class MarkdownTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val endExclusive: Int,
)

private fun markdownTableCells(line: String): List<String> {
    val normalized = line.trim().removePrefix("|").removeSuffix("|")
    return normalized.split('|').map { it.trim() }
}

private fun isMarkdownTableSeparator(line: String): Boolean =
    markdownTableCells(line).size >= 2 && markdownTableCells(line).all { it.matches(Regex(":?-{3,}:?")) }

internal fun parseMarkdownTable(lines: List<String>, start: Int): MarkdownTable? {
    if (start + 1 >= lines.size || !lines[start].contains('|') || !isMarkdownTableSeparator(lines[start + 1])) return null
    val headers = markdownTableCells(lines[start])
    if (headers.size < 2) return null
    val rows = ArrayList<List<String>>()
    var index = start + 2
    while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
        rows += markdownTableCells(lines[index]).let { cells ->
            when {
                cells.size < headers.size -> cells + List(headers.size - cells.size) { "" }
                cells.size > headers.size -> cells.take(headers.size - 1) + cells.drop(headers.size - 1).joinToString(" | ")
                else -> cells
            }
        }
        index++
    }
    return MarkdownTable(headers, rows, index)
}

private val LIGHT_PALETTE = AtlasPalette(
    accent = Color(0xFF4F6EF7), okGreen = Color(0xFF2E9E5B), warnOrange = Color(0xFFD98A2B),
    badRed = Color(0xFFD9534F), muted = Color(0xFF8A8F98), codeBg = Color(0xFFF5F6F8),
)

private val DARK_PALETTE = AtlasPalette(
    accent = Color(0xFF8AA2FF), okGreen = Color(0xFF63C98A), warnOrange = Color(0xFFE8AE5E),
    badRed = Color(0xFFEF7B77), muted = Color(0xFF9AA3B2), codeBg = Color(0xFF1E2129),
)

/**
 * 语义色入口：属性读取是 snapshot state 读取，在组合期间发生（含 renderInline 等
 * 组合期调用的普通函数），主题切换后相关作用域自动重组。
 */
object Theme {
    private val palette = mutableStateOf(LIGHT_PALETTE)

    val Accent: Color get() = palette.value.accent
    val OkGreen: Color get() = palette.value.okGreen
    val WarnOrange: Color get() = palette.value.warnOrange
    val BadRed: Color get() = palette.value.badRed
    val Muted: Color get() = palette.value.muted
    val CodeBg: Color get() = palette.value.codeBg

    /** 由 [AtlasTheme] 在 SideEffect 中调用（组合期间写状态不安全） */
    fun apply(dark: Boolean) { palette.value = if (dark) DARK_PALETTE else LIGHT_PALETTE }
}

@Composable
fun AtlasTheme(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val safeFontScale = fontScale.coerceIn(0.8f, 1.4f)
    SideEffect { Theme.apply(dark) }
    CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, safeFontScale),
    ) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun VDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))

@Composable
fun StatusChip(text: String, color: Color = Theme.Accent) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = color, fontSize = 12.sp)
    }
}

/** 顶部导航专用数量徽标：保留提示语义，但不与主导航标签争夺层级。 */
@Composable
fun NavBadge(text: String, color: Color = Theme.Accent) {
    Surface(shape = RoundedCornerShape(7.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            color = color,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
        )
    }
}

/** 行内标记：**粗体**、`代码`、[文本](url) 渲染为带样式文本（链接仅展示） */
fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = text
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) {
                    append(s.substring(i + 2, end)); addStyle(SpanStyle(fontWeight = FontWeight.Bold), length - (end - i - 2), length)
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) {
                    append(s.substring(i + 1, end)); addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14808080)), length - (end - i - 1), length)
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '[' -> {
                val close = s.indexOf(']', i + 1)
                if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                    val urlEnd = s.indexOf(')', close + 2)
                    if (urlEnd > 0) {
                        append(s.substring(i + 1, close))
                        addStyle(SpanStyle(color = Theme.Accent), length - (close - i - 1), length)
                        i = urlEnd + 1
                    } else { append(s[i]); i++ }
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

/**
 * 轻量 markdown 渲染（v1 内置实现，ADR：替代 mikepenz 库以零依赖——支持标题/列表/引用/
 * 代码块/分隔线/行内标记；复杂 GFM 交给「用系统编辑器打开」）
 */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val lines = md.lines()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                parseMarkdownTable(lines, i)?.let { table ->
                    MarkdownTableView(table)
                    i = table.endExclusive - 1
                    true
                } == true -> Unit
                line.trimStart().startsWith("```") -> {
                    val lang = line.trimStart().removePrefix("```")
                    val buf = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { buf.add(lines[i]); i++ }
                    i++
                    Surface(shape = RoundedCornerShape(8.dp), color = Theme.CodeBg) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            if (lang.isNotBlank()) Text(lang, fontSize = 11.sp, color = Theme.Muted)
                            buf.forEach { Text(renderInline(it), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp) }
                        }
                    }
                }
                line.startsWith("### ") -> Text(renderInline(line.removePrefix("### ")), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                line.startsWith("## ") -> Text(renderInline(line.removePrefix("## ")), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                line.startsWith("# ") -> Text(renderInline(line.removePrefix("# ")), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                line.startsWith("> ") -> Text(
                    renderInline(line.removePrefix("> ")),
                    Modifier.padding(start = 10.dp).fillMaxWidth(),
                    color = Theme.Muted, fontStyle = FontStyle.Italic, fontSize = 14.sp,
                )
                line.trim() == "---" -> VDivider()
                Regex("^\\s*[-*] ").containsMatchIn(line) -> Row(Modifier.fillMaxWidth()) {
                    Text("•  ", color = Theme.Muted)
                    Text(renderInline(line.trimStart().removePrefix("- ").removePrefix("* ")), fontSize = 14.sp, lineHeight = 20.sp)
                }
                Regex("^\\s*[0-9]+[.、)] ").containsMatchIn(line) -> Text(renderInline(line.trim()), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 6.dp))
                line.isBlank() -> {}
                else -> Text(renderInline(line), fontSize = 14.sp, lineHeight = 20.sp)
            }
            i++
        }
    }
}

@Composable
private fun MarkdownTableView(table: MarkdownTable) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        MarkdownTableRow(table.headers, header = true)
        table.rows.forEach { MarkdownTableRow(it, header = false, columnCount = table.headers.size) }
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, header: Boolean, columnCount: Int = cells.size) {
    Row(Modifier.fillMaxWidth()) {
        repeat(columnCount) { index ->
            val value = cells.getOrNull(index).orEmpty()
            Text(
                renderInline(value),
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(
                        if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}
