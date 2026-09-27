package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 应用自定义色板：MaterialTheme 之外业务用到的语义色，随明暗主题切换 */
data class AtlasPalette(
    val accent: Color, val okGreen: Color, val warnOrange: Color,
    val badRed: Color, val muted: Color, val codeBg: Color,
    /** 卡片/浮层底：由主题表面层派生，比页面底略凸以形成层次。 */
    val elevated: Color,
    /** 输入框/凹陷控件底。 */
    val inputBg: Color,
    /** 内容面：比页面底略亮，但不制造厚重卡片感。 */
    val panel: Color,
    /** 交互状态面。 */
    val hover: Color,
    val selected: Color,
    val pressed: Color,
    val focus: Color,
    val info: Color,
    val borderStrong: Color,
    /** 代码块专用表面。 */
    val codeBlock: Color,
    /** 本主题生效后的 Markdown 语义色（已按基色补全）。 */
    val md: MdSpec,
)

/** 页面级视觉规范，避免每个页面自行散落字号和间距常量。 */
data class AtlasSpacing(
    val page: androidx.compose.ui.unit.Dp,
    val section: androidx.compose.ui.unit.Dp,
    val card: androidx.compose.ui.unit.Dp,
    val item: androidx.compose.ui.unit.Dp,
    val control: androidx.compose.ui.unit.Dp,
)

data class AtlasTypography(
    val pageTitle: TextStyle,
    val sectionTitle: TextStyle,
    val itemTitle: TextStyle,
    val body: TextStyle,
    val secondary: TextStyle,
    val caption: TextStyle,
)

data class AtlasUiTokens(
    val spacing: AtlasSpacing,
    val typography: AtlasTypography,
    val contentMaxWidth: androidx.compose.ui.unit.Dp,
    val readingMaxWidth: androidx.compose.ui.unit.Dp,
) {
    companion object {
        fun forTheme(dark: Boolean): AtlasUiTokens = AtlasUiTokens(
            spacing = AtlasSpacing(
                page = 16.dp,
                section = 12.dp,
                card = 14.dp,
                item = 8.dp,
                control = 40.dp,
            ),
            typography = AtlasTypography(
                pageTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                sectionTitle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
                itemTitle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif),
                body = TextStyle(fontSize = 15.sp, lineHeight = 26.sp, fontFamily = FontFamily.SansSerif, letterSpacing = 0.1.sp),
                secondary = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.SansSerif),
                caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            ),
            contentMaxWidth = 1440.dp,
            readingMaxWidth = 760.dp,
        )
    }
}

val LocalAtlasUiTokens = staticCompositionLocalOf { AtlasUiTokens.forTheme(dark = true) }

@Composable
fun atlasUiTokens(): AtlasUiTokens = LocalAtlasUiTokens.current

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

private fun ThemeSpec.toPalette(dark: Boolean) = AtlasPalette(
    accent = accent, okGreen = okGreen, warnOrange = warnOrange,
    badRed = badRed, muted = muted, codeBg = codeBg,
    elevated = elevatedSurface(dark),
    inputBg = inputBg(dark),
    panel = panelSurface(),
    hover = hoverSurface(),
    selected = selectedSurface(),
    pressed = pressedSurface(),
    focus = infoColor(),
    info = infoColor(),
    borderStrong = borderStrong(),
    codeBlock = codeBlockSurface(),
    md = md(),
)

/**
 * 语义色入口：属性读取是 snapshot state 读取，在组合期间发生（含 renderInline 等
 * 组合期调用的普通函数），主题切换后相关作用域自动重组。
 */
object Theme {
    private val palette = mutableStateOf(AtlasThemes.DEFAULT.dark.toPalette(dark = true))

    val Accent: Color get() = palette.value.accent
    val OkGreen: Color get() = palette.value.okGreen
    val WarnOrange: Color get() = palette.value.warnOrange
    val BadRed: Color get() = palette.value.badRed
    val Muted: Color get() = palette.value.muted
    val CodeBg: Color get() = palette.value.codeBg
    val Elevated: Color get() = palette.value.elevated
    val InputBg: Color get() = palette.value.inputBg
    val Panel: Color get() = palette.value.panel
    val Hover: Color get() = palette.value.hover
    val Selected: Color get() = palette.value.selected
    val Pressed: Color get() = palette.value.pressed
    val Focus: Color get() = palette.value.focus
    val Info: Color get() = palette.value.info
    val BorderStrong: Color get() = palette.value.borderStrong
    val CodeBlock: Color get() = palette.value.codeBlock

    val MdH1: Color get() = palette.value.md.h1
    val MdH2: Color get() = palette.value.md.h2
    val MdH3: Color get() = palette.value.md.h3
    val MdBold: Color get() = palette.value.md.bold
    val MdLink: Color get() = palette.value.md.link
    val MdQuote: Color get() = palette.value.md.quote
    val MdInlineCode: Color get() = palette.value.md.inlineCode
    val MdInlineCodeBg: Color get() = palette.value.md.inlineCodeBg

    /** 由 [AtlasTheme] 在 SideEffect 中调用（组合期间写状态不安全） */
    fun apply(dark: Boolean, spec: ThemeSpec) {
        palette.value = spec.toPalette(dark)
    }
}

/** 输入净化：只保留 `#` 与十六进制字符，统一大写，长度上限 9（`#AARRGGBB`）。 */
fun sanitizeHexInput(raw: String): String {
    val cleaned = raw.filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        .replace("#", "").uppercase()
    return if (cleaned.isEmpty()) "" else "#" + cleaned.take(8)
}

/**
 * `#RGB` / `#RRGGBB` / `#AARRGGBB` → Color；空串、格式非法或构造出的颜色无法解析色彩空间时返回 null，
 * 由调用方回退默认。这里多一道 `colorSpace` 校验：拼错内部布局的 Color 在绘制时才炸
 * （`getColorSpace` 抛 `ArrayIndexOutOfBoundsException`）并崩掉整个界面，而本函数读的是持久化输入，
 * 必须在返回前就挡掉。
 */
fun parseHexColor(raw: String): Color? {
    val c = raw.colorOrNull() ?: return null
    return if (runCatching { c.colorSpace }.isSuccess) c else null
}

private fun String.colorOrNull(): Color? {
    val hex = trim().removePrefix("#")
    if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (hex.length) {
        3 -> {
            val r = hex[0].digitToInt(16).toLong() * 17
            val g = hex[1].digitToInt(16).toLong() * 17
            val b = hex[2].digitToInt(16).toLong() * 17
            Color(0xFF000000L or (r shl 16) or (g shl 8) or b)
        }
        6 -> Color(0xFF000000L or (hex.toLongOrNull(16) ?: return null))
        8 -> Color(hex.toLongOrNull(16) ?: return null)
        else -> null
    }
}

@Composable
fun AtlasTheme(
    dark: Boolean,
    fontScale: Float = 1f,
    spec: ThemeSpec = AtlasThemes.specOf(AtlasThemes.DEFAULT.name, dark),
    content: @Composable () -> Unit,
) {
    val baseDensity = LocalDensity.current
    val safeFontScale = fontScale.coerceIn(0.8f, 1.4f)
    SideEffect { Theme.apply(dark, spec) }
    CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, safeFontScale),
        LocalAtlasUiTokens provides AtlasUiTokens.forTheme(dark),
    ) {
        MaterialTheme(colorScheme = spec.toMaterialScheme(dark)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = spec.background,
                contentColor = spec.onSurface,
            ) { content() }
        }
    }
}

@Composable
fun VDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)))

@Composable
fun AtlasPanel(
    modifier: Modifier = Modifier,
    color: Color = Theme.Panel,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
        content = content,
    )
}

@Composable
fun StatusChip(text: String, color: Color = Theme.Accent) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.12f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = color, fontSize = 12.sp)
    }
}

/** 代码块语言标识：作为代码块的顶部栏展示，不与正文争夺胶囊徽标的视觉层级。 */
@Composable
private fun CodeLanguageLabel(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(Theme.MdInlineCodeBg.copy(alpha = 0.18f))
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(2.dp).height(12.dp)
                    .background(Theme.Accent.copy(alpha = 0.75f), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text.uppercase(),
                color = Theme.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        )
    }
}

/** 顶部导航专用数量徽标：保留提示语义，但不与主导航标签争夺层级。 */
@Composable
fun NavBadge(text: String, color: Color = Theme.Accent) {
    Surface(shape = RoundedCornerShape(7.dp), color = color.copy(alpha = 0.11f)) {
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
                    append(s.substring(i + 2, end)); addStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = Theme.MdBold,
                        ),
                        length - (end - i - 2),
                        length,
                    )
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) {
                    append(s.substring(i + 1, end)); addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            // 行内代码是辅助结构：保留等宽与底色，但不再使用高饱和强调色抢正文焦点。
                            color = Theme.MdInlineCode.copy(alpha = 0.92f),
                            background = Theme.MdInlineCodeBg.copy(alpha = 0.18f),
                        ),
                        length - (end - i - 1),
                        length,
                    )
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '[' -> {
                val close = s.indexOf(']', i + 1)
                if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                    val urlEnd = s.indexOf(')', close + 2)
                    if (urlEnd > 0) {
                        append(s.substring(i + 1, close))
                        addStyle(SpanStyle(color = Theme.MdLink), length - (close - i - 1), length)
                        i = urlEnd + 1
                    } else { append(s[i]); i++ }
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

private val markdownBulletPattern = Regex("^\\s*[-*] ")
private val markdownNumberedPattern = Regex("^\\s*([0-9]+)(?:[.]\\s+|[、)]\\s*)(.+)$")
private val markdownTaskPattern = Regex("^\\s*[-*] \\[([ xX])\\] (.+)$")

/** 答案 diff 行着色：相对 git HEAD 变化的行整行文字标橙（追加在内联样式之上） */
private fun colorIfDirty(annotated: AnnotatedString, lineIndex: Int, dirtyLines: Set<Int>): AnnotatedString =
    if (lineIndex in dirtyLines) {
        buildAnnotatedString {
            append(annotated)
            addStyle(SpanStyle(color = Theme.WarnOrange.copy(alpha = 0.72f)), 0, length)
        }
    } else annotated

/**
 * 轻量 markdown 渲染（v1 内置实现，ADR：替代 mikepenz 库以零依赖——支持标题/列表/引用/
 * 代码块/分隔线/行内标记；复杂 GFM 交给「用系统编辑器打开」）。
 * [dirtyLines]：需要高亮的行下标（md.lines() 坐标），题库 git 改动行内着色用。
 */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, dirtyLines: Set<Int> = emptySet()) =
    ReaderMarkdownText(md, modifier, dirtyLines)

@Composable
private fun ReaderMarkdownText(md: String, modifier: Modifier = Modifier, dirtyLines: Set<Int> = emptySet()) {
    val ui = atlasUiTokens()
    val lines = md.lines()
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = ui.readingMaxWidth).align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                when {
                parseMarkdownTable(lines, i)?.let { table ->
                    ReaderMarkdownTableView(table)
                    i = table.endExclusive - 1
                    true
                } == true -> Unit
                line.trimStart().startsWith("```") -> {
                    val lang = line.trimStart().removePrefix("```").trim()
                    val buf = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { buf.add(lines[i]); i++ }
                    i++
                    if (lang.equals("mermaid", true) || (lang.isBlank() && buf.firstOrNull()?.trimStart()?.startsWith("flowchart") == true)) {
                        MermaidFlowchartView(buf)
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Theme.CodeBlock,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            tonalElevation = 0.dp,
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                if (lang.isNotBlank()) CodeLanguageLabel(lang)
                                SelectionContainer {
                                    Column(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                            .padding(horizontal = 16.dp, vertical = 15.dp),
                                    ) {
                                        buf.forEach { code ->
                                            Text(
                                                code,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                lineHeight = 20.sp,
                                                softWrap = false,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                line.startsWith("### ") -> Text(
                    colorIfDirty(renderInline(line.removePrefix("### ")), i, dirtyLines),
                    modifier = Modifier.padding(top = 7.dp),
                    style = ui.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 24.sp), color = Theme.MdH3,
                )
                line.startsWith("## ") -> Text(
                    colorIfDirty(renderInline(line.removePrefix("## ")), i, dirtyLines),
                    modifier = Modifier.padding(top = 10.dp),
                    style = ui.typography.sectionTitle.copy(fontSize = 19.sp, lineHeight = 28.sp), color = Theme.MdH2,
                )
                line.startsWith("# ") -> Text(
                    colorIfDirty(renderInline(line.removePrefix("# ")), i, dirtyLines),
                    modifier = Modifier.padding(top = 12.dp),
                    style = ui.typography.pageTitle.copy(fontSize = 25.sp, lineHeight = 34.sp), color = Theme.MdH1,
                )
                line.startsWith("> ") -> Row(
                    Modifier.fillMaxWidth()
                        .background(Theme.MdQuote.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Box(Modifier.width(2.dp).height(24.dp).background(Theme.MdQuote.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
                    Text(
                        colorIfDirty(renderInline(line.removePrefix("> ")), i, dirtyLines),
                        Modifier.padding(start = 12.dp).fillMaxWidth(),
                        color = Theme.MdQuote,
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                    )
                }
                line.trim() == "---" -> VDivider()
                markdownTaskPattern.find(line) != null -> {
                    val task = markdownTaskPattern.find(line)!!
                    val checked = task.groupValues[1].equals("x", ignoreCase = true)
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Surface(
                            Modifier.padding(top = 2.dp).size(17.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = if (checked) Theme.OkGreen.copy(alpha = 0.18f) else Theme.InputBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (checked) Theme.OkGreen else Theme.BorderStrong.copy(alpha = 0.7f)),
                        ) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text(if (checked) "✓" else "", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Theme.OkGreen)
                            }
                        }
                        Text(
                            colorIfDirty(renderInline(task.groupValues[2]), i, dirtyLines),
                            Modifier.padding(start = 10.dp).fillMaxWidth(),
                            style = ui.typography.body.copy(color = if (checked) Theme.Muted else MaterialTheme.colorScheme.onSurface),
                        )
                    }
                }
                Regex("^\\s*[-*] ").containsMatchIn(line) -> Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    Text("•", color = Theme.MdH2, fontSize = 15.sp)
                    Text(
                        colorIfDirty(renderInline(line.trimStart().removePrefix("- ").removePrefix("* ")), i, dirtyLines),
                        Modifier.padding(start = 10.dp),
                        style = ui.typography.body,
                    )
                }
                markdownNumberedPattern.find(line) != null -> {
                    val numbered = markdownNumberedPattern.find(line)!!
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text(
                            "${numbered.groupValues[1]}.",
                            Modifier.width(26.dp),
                            color = Theme.MdH2,
                            fontWeight = FontWeight.SemiBold,
                            style = ui.typography.body,
                        )
                        Text(
                            colorIfDirty(renderInline(numbered.groupValues[2]), i, dirtyLines),
                            Modifier.fillMaxWidth(),
                            style = ui.typography.body,
                        )
                    }
                }
                line.trimStart().startsWith("flowchart") || line.trimStart().startsWith("graph ") -> {
                    // 无围栏的 Mermaid 段：声明行 + 后续缩进/空行，直到首个顶格非空行
                    val block = ArrayList<String>()
                    var j = i
                    while (j < lines.size && (j == i || lines[j].isBlank() || lines[j].startsWith(" ") || lines[j].startsWith("\t"))) {
                        block.add(lines[j]); j++
                    }
                    while (block.isNotEmpty() && block.last().isBlank()) block.removeAt(block.lastIndex)
                    MermaidFlowchartView(block)
                    i = j - 1
                }
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(
                    colorIfDirty(renderInline(line), i, dirtyLines),
                    style = ui.typography.body.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.96f)),
                )
            }
                i++
            }
        }
    }
}

@Composable
private fun ReaderMarkdownTableView(table: MarkdownTable) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, (table.headers.size * 170).dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(tableWidth)) {
                ReaderMarkdownTableRow(table.headers, header = true)
                table.rows.forEach { ReaderMarkdownTableRow(it, header = false, columnCount = table.headers.size) }
            }
        }
    }
}

@Composable
private fun ReaderMarkdownTableRow(cells: List<String>, header: Boolean, columnCount: Int = cells.size) {
    // IntrinsicSize.Min + fillMaxHeight：换行格撑起整行高度，其余格子的背景/边框同步拉伸对齐
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        repeat(columnCount) { index ->
            Text(
                renderInline(cells.getOrNull(index).orEmpty()),
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (header) 0.8f else 0.4f))
                    .background(if (header) Theme.MdH2.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
