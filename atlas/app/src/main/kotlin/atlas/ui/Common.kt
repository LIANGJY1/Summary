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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
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
                page = 20.dp,
                section = 16.dp,
                card = 16.dp,
                item = 8.dp,
                control = 40.dp,
            ),
            typography = AtlasTypography(
                pageTitle = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                sectionTitle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                itemTitle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif),
                body = TextStyle(fontSize = 14.sp, lineHeight = 23.sp, fontFamily = FontFamily.SansSerif),
                secondary = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.SansSerif),
                caption = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
            ),
            contentMaxWidth = 1240.dp,
            readingMaxWidth = 1040.dp,
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
    /** 题库标签：从当前主题的次要文字向链接色轻微偏移，保留分类感而不抢题面。 */
    val Tag: Color get() = lerp(Muted, MdLink, 0.58f)
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
                .background(Theme.MdInlineCodeBg.copy(alpha = 0.20f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(2.dp).height(12.dp)
                    .background(Theme.Accent.copy(alpha = 0.75f), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text.uppercase(),
                color = Theme.MdQuote,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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

/**
 * 行内标记：**粗体**、`代码`、[文本](url) 渲染为带样式文本（链接仅展示）。
 *
 * [codeSink] 非 null 时，`代码` 段以 `mdcN` 占位符发出、原始文本收进 sink，
 * 由 [mdCodeInlineContents] 提供圆角芯片内容（SpanStyle 背景贴字矩形做不出药丸，
 * 2026-09-29 用户裁决）；为 null 时退化为等宽着色文本（无芯片）。
 */
fun renderInline(text: String, codeSink: MutableList<String>? = null): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = text
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) {
                    append(s.substring(i + 2, end)); addStyle(
                        SpanStyle(
                            // ExtraBold：黑曜深色正文偏柔灰，仅 Bold 字重差在长文里区分度不足
                            // （2026-09-29 用户反馈）；字重拉大比提亮颜色更稳，不与链接/警示色争语义。
                            fontWeight = FontWeight.ExtraBold,
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
                    val code = s.substring(i + 1, end)
                    if (codeSink != null) {
                        appendInlineContent("mdc${codeSink.size}", code)
                        codeSink.add(code)
                    } else {
                        append(code)
                        addStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, color = Theme.MdInlineCode),
                            length - code.length,
                            length,
                        )
                    }
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

// 行内代码芯片宽度估算：芯片字取正文 0.92 倍，等宽步进 ≈0.62em（DejaVu 0.602/JBMono 0.60），
// CJK 回退字形 ≈1.1em，加 12sp 水平内边距和 2sp 余量；不再额外预留一个字符，避免短代码两侧留白过宽。
private const val MD_CHIP_ASCII_EM = 0.62f
private const val MD_CHIP_CJK_EM = 1.1f

/** 光学下沉量：对冲 TextCenter 按字体 metrics 居中在 CJK 混排行里的「骑高」观感（可调） */
private val MD_CHIP_OPTICAL_DROP = 2.dp

internal fun mdChipWidthSp(code: String, chipFontSize: TextUnit): TextUnit =
    (code.sumOf { c -> (if (c.code > 0x2E80) MD_CHIP_CJK_EM else MD_CHIP_ASCII_EM).toDouble() }.toFloat()
        * chipFontSize.value + 14f).sp

/** 行内代码芯片的 inlineContent 表：圆角 Surface 药丸，贴身包裹代码文本 */
@Composable
private fun mdCodeInlineContents(codes: List<String>, fontSize: TextUnit): Map<String, InlineTextContent> =
    if (codes.isEmpty()) {
        emptyMap()
    } else {
        // 图二基准：芯片字比正文略小（0.92×）、药丸同高且垂直严格居中——
        // 占位符高度只取 1.5em 且所有芯片共用同一 spec，同一行内的药丸才会齐平不忽高忽低。
        // TextCenter 按字体 metrics 居中，中英混排（CJK 回退 ascent 大）的视觉中心比 metrics
        // 中心低，药丸会整体骑高——绘制层统一下沉补偿（offset 不影响布局，全部药丸同量保持齐平）。
        val chipFont = fontSize * 0.92f
        codes.mapIndexed { idx, code ->
            "mdc$idx" to InlineTextContent(
                Placeholder(
                    width = mdChipWidthSp(code, chipFont),
                    height = fontSize * 1.5f,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Surface(
                    Modifier.offset(y = MD_CHIP_OPTICAL_DROP).wrapContentSize(Alignment.Center),
                    shape = RoundedCornerShape(4.dp),
                    color = Theme.MdInlineCodeBg,
                ) {
                    Text(
                        code,
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = chipFont,
                        lineHeight = chipFont * 1.2f,
                        color = Theme.MdInlineCode,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }.toMap()
    }

/** renderInline + 芯片 map 的组合入口：所有行内文本 Text 调用统一走这里 */
@Composable
private fun MdInlineText(
    raw: String,
    lineIndex: Int,
    dirtyLines: Set<Int>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
) {
    val codes = remember(raw) { mutableListOf<String>() }
    val annotated = remember(raw) { renderInline(raw, codes) }
    Text(
        colorIfDirty(annotated, lineIndex, dirtyLines),
        modifier = modifier,
        style = style,
        color = color,
        inlineContent = mdCodeInlineContents(codes, style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 14.sp),
    )
}

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
    SelectionContainer {
        ReaderMarkdownText(md, modifier, dirtyLines)
    }

@Composable
private fun ReaderMarkdownText(md: String, modifier: Modifier = Modifier, dirtyLines: Set<Int> = emptySet()) {
    val ui = atlasUiTokens()
    val lines = md.lines()
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.widthIn(max = ui.readingMaxWidth).fillMaxWidth().align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(7.dp),
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
                line.startsWith("### ") -> MdInlineText(
                    line.removePrefix("### "), i, dirtyLines,
                    modifier = Modifier.padding(top = 7.dp),
                    style = ui.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 24.sp), color = Theme.MdH3,
                )
                line.startsWith("## ") -> MdInlineText(
                    line.removePrefix("## "), i, dirtyLines,
                    modifier = Modifier.padding(top = 10.dp),
                    style = ui.typography.sectionTitle.copy(fontSize = 19.sp, lineHeight = 28.sp), color = Theme.MdH2,
                )
                line.startsWith("# ") -> MdInlineText(
                    line.removePrefix("# "), i, dirtyLines,
                    modifier = Modifier.padding(top = 12.dp),
                    style = ui.typography.pageTitle.copy(fontSize = 25.sp, lineHeight = 34.sp), color = Theme.MdH1,
                )
                line.startsWith("> ") -> Row(
                    Modifier.fillMaxWidth()
                        .background(Theme.MdQuote.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Box(Modifier.width(2.dp).height(24.dp).background(Theme.MdQuote.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
                    MdInlineText(
                        line.removePrefix("> "), i, dirtyLines,
                        modifier = Modifier.padding(start = 12.dp).fillMaxWidth(),
                        style = TextStyle(fontSize = 15.sp, lineHeight = 25.sp),
                        color = Theme.MdQuote,
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
                        MdInlineText(
                            task.groupValues[2], i, dirtyLines,
                            modifier = Modifier.padding(start = 10.dp).fillMaxWidth(),
                            style = ui.typography.body.copy(color = if (checked) Theme.Muted else MaterialTheme.colorScheme.onSurface),
                        )
                    }
                }
                Regex("^\\s*[-*] ").containsMatchIn(line) -> Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    // 序号/圆点是结构标记：常态弱化为次要色、不加字重，把强调层级留给内容自身的 **粗体**
                    Text("•", color = Theme.Muted, fontSize = 15.sp)
                    MdInlineText(
                        line.trimStart().removePrefix("- ").removePrefix("* "), i, dirtyLines,
                        modifier = Modifier.padding(start = 10.dp),
                        style = ui.typography.body,
                    )
                }
                markdownNumberedPattern.find(line) != null -> {
                    val numbered = markdownNumberedPattern.find(line)!!
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text(
                            "${numbered.groupValues[1]}.",
                            Modifier.width(26.dp),
                            color = Theme.Muted,
                            style = ui.typography.body,
                        )
                        MdInlineText(
                            numbered.groupValues[2], i, dirtyLines,
                            modifier = Modifier.fillMaxWidth(),
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
                line.isBlank() -> Spacer(Modifier.height(3.dp))
                else -> MdInlineText(
                    line, i, dirtyLines,
                    style = ui.typography.body.copy(color = MaterialTheme.colorScheme.onSurface),
                )
            }
                i++
            }
        }
    }
}

/** README 长文按 Markdown 块虚拟化，避免打开时一次组合整篇文档。 */
@Composable
fun LazyMarkdownText(md: String, modifier: Modifier = Modifier) {
    val ui = atlasUiTokens()
    val blocks = remember(md) { markdownBlocks(md) }
    SelectionContainer {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            itemsIndexed(blocks, key = { index, block -> "$index:${block.hashCode()}" }) { _, block ->
                Box(Modifier.fillMaxWidth()) {
                    ReaderMarkdownText(
                        block,
                        Modifier.widthIn(max = ui.readingMaxWidth).fillMaxWidth().align(Alignment.CenterStart),
                    )
                }
            }
        }
    }
}

/** 保持围栏代码、表格和无围栏 Mermaid 完整；普通行单独成为可虚拟化的块。 */
private fun markdownBlocks(md: String): List<String> {
    val lines = md.lines()
    val blocks = ArrayList<String>(lines.size)
    var index = 0
    while (index < lines.size) {
        val start = index
        val table = parseMarkdownTable(lines, index)
        when {
            lines[index].trimStart().startsWith("```") -> {
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) index++
                if (index < lines.size) index++
            }
            table != null -> index = table.endExclusive
            lines[index].trimStart().startsWith("flowchart") || lines[index].trimStart().startsWith("graph ") -> {
                index++
                while (index < lines.size && (lines[index].isBlank() || lines[index].startsWith(" ") || lines[index].startsWith("\t"))) index++
            }
            else -> index++
        }
        blocks += lines.subList(start, index).joinToString("\n")
    }
    return blocks
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
            MdInlineText(
                cells.getOrNull(index).orEmpty(), 0, emptySet(),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (header) 0.8f else 0.4f))
                    .background(if (header) Theme.MdH2.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
