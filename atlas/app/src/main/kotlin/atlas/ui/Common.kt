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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** Markdown 内容排版风格：题库答案/闪卡/预览正文的基准字号与行距（设置中心「外观与阅读」可调）。 */
data class MarkdownContentStyle(val fontSizeSp: Int = 14, val lineHeightPercent: Int = 140)

/** 题库答案可覆盖阅读灰阶；其他 Markdown 页面继续使用主题原有语义色。 */
data class MarkdownReadingColors(
    val body: Color,
    val bold: Color,
    val boldWeight: FontWeight,
    val inlineCode: Color,
    val inlineCodeBackground: Color,
)

val LocalMarkdownReadingColors = staticCompositionLocalOf<MarkdownReadingColors?> { null }

val LocalMarkdownContentStyle = staticCompositionLocalOf { MarkdownContentStyle() }

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
    // 11–20sp 全局字号换算的 fontScale（11/14≈0.79）；夹宽放宽到 0.75–1.5 避免低端被夹掉
    val safeFontScale = fontScale.coerceIn(0.75f, 1.5f)
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
 * 代码保留为普通文字，以便排版器正常换行；圆角底色在排版完成后逐行绘制。
 */
fun renderInline(
    text: String,
    codeFontSize: TextUnit = TextUnit.Unspecified,
    readingColors: MarkdownReadingColors? = null,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = text
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) {
                    append(s.substring(i + 2, end)); addStyle(
                        SpanStyle(
                            fontWeight = readingColors?.boldWeight ?: FontWeight.ExtraBold,
                            color = readingColors?.bold ?: Theme.MdBold,
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
                    if (code.isNotEmpty()) {
                        append(MD_INLINE_CODE_GAP)
                        val start = length
                        append(code)
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = codeFontSize,
                                color = readingColors?.inlineCode ?: Theme.MdInlineCode,
                            ),
                            start,
                            length,
                        )
                        addStringAnnotation(MD_INLINE_CODE_TAG, code, start, length)
                        append(MD_INLINE_CODE_GAP)
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

private const val MD_INLINE_CODE_TAG = "md-inline-code"
private const val MD_INLINE_CODE_GAP = "\u202F\u202F\u202F"

private val markdownBulletPattern = Regex("^\\s*[-*] ")
private val markdownNumberedPattern = Regex("^\\s*([0-9]+)(?:[.]\\s+|[、)]\\s*)(.+)$")
private val markdownTaskPattern = Regex("^\\s*[-*] \\[([ xX])\\] (.+)$")

private fun isMarkdownListItem(line: String): Boolean =
    markdownNumberedPattern.containsMatchIn(line) ||
        markdownTaskPattern.containsMatchIn(line) ||
        markdownBulletPattern.containsMatchIn(line)

/** 列表项之间的单个源码空行不额外占一整行视觉间距，层级距离由列表项自身控制。 */
private fun isListSeparatorBlank(lines: List<String>, index: Int, followingLine: String? = null): Boolean =
    lines[index].isBlank() &&
        lines.getOrNull(index - 1)?.let(::isMarkdownListItem) == true &&
        (lines.getOrNull(index + 1) ?: followingLine)?.let(::isMarkdownListItem) == true

/** 列表层级栈条目：indent 为源码缩进列；counter 给子级有序列表提供 a/b/c 计数。 */
private class MdListLevel(val indent: Int, var counter: Int)

/** 嵌套有序列表的字母标记：1→a.、2→b.，26 之后进位为 aa.、ab.。 */
private fun mdOrderedLetterMarker(n: Int): String {
    var x = n.coerceAtLeast(1)
    var letters = ""
    while (x > 0) {
        x -= 1
        letters = ('a' + (x % 26)) + letters
        x /= 26
    }
    return "$letters."
}

/** 三级及更深有序列表的小写罗马数字标记：1→i.、2→ii.、9→ix.、10→x.。 */
private fun mdOrderedRomanMarker(n: Int): String {
    var x = n.coerceAtLeast(1)
    val sb = StringBuilder()
    for ((value, symbol) in listOf(
        1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
        50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
    )) {
        while (x >= value) {
            sb.append(symbol)
            x -= value
        }
    }
    return "$sb."
}

// 保留旧宽度估算器供历史宽度约束检查；实际渲染不再使用原子占位符。
private const val MD_CHIP_ASCII_EM = 0.62f
private const val MD_CHIP_CJK_EM = 1.1f

internal fun mdChipWidthSp(code: String, chipFontSize: TextUnit): TextUnit =
    (code.sumOf { c -> (if (c.code > 0x2E80) MD_CHIP_CJK_EM else MD_CHIP_ASCII_EM).toDouble() }.toFloat()
        * chipFontSize.value + 14f).sp

/** 所有行内文字共用的渲染入口；代码以文字参与换行，圆角底色按实际行片段绘制。 */
@Composable
private fun MdInlineText(
    raw: String,
    lineIndex: Int,
    dirtyLines: Set<Int>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
) {
    val readingColors = LocalMarkdownReadingColors.current
    val fontSize = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: LocalMarkdownContentStyle.current.fontSizeSp.sp
    val chipFont = fontSize * 0.92f
    val annotated = remember(raw, chipFont, readingColors, Theme.MdBold, Theme.MdLink, Theme.MdInlineCode) {
        renderInline(raw, chipFont, readingColors)
    }
    val displayed = colorIfDirty(annotated, lineIndex, dirtyLines)
    val codeRanges = displayed.getStringAnnotations(MD_INLINE_CODE_TAG, 0, displayed.length)
    if (codeRanges.isEmpty()) {
        Text(displayed, modifier = modifier, style = style, color = color)
        return
    }
    val density = LocalDensity.current
    val layoutState = remember(displayed) { mutableStateOf<TextLayoutResult?>(null) }
    val codeBackground = readingColors?.inlineCodeBackground ?: Theme.MdInlineCodeBg
    Text(
        displayed,
        modifier = modifier.drawBehind {
            val layout = layoutState.value ?: return@drawBehind
            val horizontalInset = with(density) { 6.dp.toPx() }
            val chipFontPx = with(density) { chipFont.toPx() }
            val chipVerticalPadding = with(density) { 3.dp.toPx() }
            val lineEdgeInset = with(density) { 1.dp.toPx() }
            val radius = with(density) { 4.dp.toPx() }
            codeRanges.forEach { range ->
                val firstLine = layout.getLineForOffset(range.start)
                val lastLine = layout.getLineForOffset(range.end - 1)
                for (line in firstLine..lastLine) {
                    val start = maxOf(range.start, layout.getLineStart(line))
                    val end = minOf(range.end, layout.getLineEnd(line, visibleEnd = true))
                    if (start >= end) continue
                    val firstGlyph = layout.getBoundingBox(start)
                    val lastGlyph = layout.getBoundingBox(end - 1)
                    val left = firstGlyph.left - horizontalInset
                    val right = lastGlyph.right + horizontalInset
                    // 字符框的高度会随换行后的字体 run / 行距分配变化：同一截图里
                    // 第一行约 20px，第二行却达到 29px。只用字符框求水平边界；
                    // 垂直方向按代码字号定高，居中放在每行的行框内。
                    val lineTop = layout.getLineTop(line).toFloat()
                    val lineBottom = layout.getLineBottom(line).toFloat()
                    val availableHeight = (lineBottom - lineTop - 2 * lineEdgeInset).coerceAtLeast(1f)
                    val pillHeight = minOf(chipFontPx + 2 * chipVerticalPadding, availableHeight)
                    val top = lineTop + (lineBottom - lineTop - pillHeight) / 2f
                    val bottom = top + pillHeight
                    if (right > left && bottom > top) {
                        drawRoundRect(
                            color = codeBackground,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    }
                }
            }
        },
        // 明确让首行与续行采用同一种行高余量分配；否则即使背景等高，
        // 默认首行裁切仍会让代码字形到药丸顶部的距离不同。
        style = style.copy(lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        )),
        color = color,
        onTextLayout = { layoutState.value = it },
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
 * 超过 [MD_STREAM_LINE_THRESHOLD] 行的长文走分块渐进渲染：首帧只组合开头几块，其余逐帧续挂。
 * 题库答案内联在 LazyColumn item 里（高度无界）无法做 Lazy 虚拟化，一次性整篇合成会阻塞 UI 数秒。
 */
@Composable
fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    dirtyLines: Set<Int> = emptySet(),
    maxWidth: Dp = Dp.Unspecified,
) =
    SelectionContainer {
        if (remember(md) { md.count { it == '\n' } + 1 } > MD_STREAM_LINE_THRESHOLD) {
            StreamedMarkdownText(remember(md) { markdownBlocks(md) }, modifier, dirtyLines, maxWidth)
        } else {
            ReaderMarkdownText(md, modifier, dirtyLines, maxWidth = maxWidth)
        }
    }

/** 长文渐进渲染阈值：超过该行数的 markdown 分块续挂 */
private const val MD_STREAM_LINE_THRESHOLD = 60

/** 首帧至少立即呈现的行数，其余每帧续挂一块 */
private const val MD_STREAM_FIRST_LINES = 20

/** markdown 分块：块文本 + 块首行在原文中的行号（脏行高亮的坐标换算用） */
private class MdBlock(val text: String, val startLine: Int, val followingLine: String?)

/** 首帧立即组合的块数：按块累加行数，凑满 [MD_STREAM_FIRST_LINES] 行即止（至少一块） */
private fun mdStreamFirstBlocks(blocks: List<MdBlock>): Int {
    var lines = 0
    for ((count, block) in blocks.withIndex()) {
        lines += block.text.count { it == '\n' } + 1
        if (lines >= MD_STREAM_FIRST_LINES) return count + 1
    }
    return blocks.size
}

/** 长答案渐进渲染：点击展开瞬间只合成开头几块，后续块逐帧续挂，组合成本摊开、UI 保持可交互。 */
@Composable
private fun StreamedMarkdownText(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    dirtyLines: Set<Int> = emptySet(),
    maxWidth: Dp = Dp.Unspecified,
) {
    val ui = atlasUiTokens()
    val visibleBlocks = remember(blocks) { mutableIntStateOf(mdStreamFirstBlocks(blocks)) }
    LaunchedEffect(blocks) {
        while (visibleBlocks.intValue < blocks.size) {
            withFrameNanos { }
            visibleBlocks.intValue++
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for (block in blocks.subList(0, visibleBlocks.intValue)) {
            Box(Modifier.fillMaxWidth()) {
                ReaderMarkdownText(
                    block.text,
                    Modifier.widthIn(max = ui.readingMaxWidth).fillMaxWidth().align(Alignment.CenterStart),
                    dirtyLines,
                    maxWidth,
                    lineOffset = block.startLine,
                    followingLine = block.followingLine,
                )
            }
        }
    }
}

@Composable
private fun ReaderMarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    dirtyLines: Set<Int> = emptySet(),
    maxWidth: Dp = Dp.Unspecified,
    lineOffset: Int = 0,
    followingLine: String? = null,
) {
    val ui = atlasUiTokens()
    val readingColors = LocalMarkdownReadingColors.current
    // 渐进渲染按块调用时传 [lineOffset]：把原文坐标的脏行集合平移成块内局部坐标
    val answerDirty = remember(dirtyLines, lineOffset) {
        if (lineOffset == 0) dirtyLines else dirtyLines.mapTo(mutableSetOf()) { it - lineOffset }
    }
    // 题库答案可传更窄的行宽（长行是阅读疲劳的主因之一）；不指定时沿用阅读宽
    val effectiveMax = if (maxWidth != Dp.Unspecified && maxWidth < ui.readingMaxWidth) maxWidth else ui.readingMaxWidth
    // 列表靠标记和缩进表达层级：子级字母/罗马数字也需有足够明度与字重，
    // 否则在长答案里会与普通续段落混在一起；正文仍由 Markdown 自身的 **强调** 决定字重。
    val markerBase = MaterialTheme.colorScheme.onSurface
    fun markerColor(depth: Int) = markerBase.copy(alpha = when (depth) {
        0 -> 0.78f
        1 -> 0.72f
        else -> 0.66f
    })
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    // 内容字号与行距（设置中心可调）：标题/正文/代码的 sp 值按基准等比缩放，140% 行距 = 内置默认
    val mdStyle = LocalMarkdownContentStyle.current
    val fontK = mdStyle.fontSizeSp / 14f
    val lineK = fontK * mdStyle.lineHeightPercent / 140f
    fun mdSp(v: Int) = (v * fontK).sp
    fun mdLh(v: Int) = (v * lineK).sp
    // 默认阅读正文略低于全局 onSurface；题库答案可单独覆盖，保留其它页面原色阶。
    val contentColor = readingColors?.body ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.93f)
    val codeTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    val bodyStyle = ui.typography.body.copy(fontSize = mdSp(14), lineHeight = mdLh(23), color = contentColor)
    val lines = md.lines()
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.widthIn(max = effectiveMax).fillMaxWidth().align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            var i = 0
            val listStack = mutableListOf<MdListLevel>()
            while (i < lines.size) {
                val line = lines[i]
                if (isListSeparatorBlank(lines, i, followingLine)) {
                    i++
                    continue
                }
                val sourceIndent = line.takeWhile { it == ' ' }.length
                val numbered = markdownNumberedPattern.find(line)
                val task = markdownTaskPattern.find(line)
                val bullet = markdownBulletPattern.containsMatchIn(line)
                val isListItem = numbered != null || task != null || bullet
                var sameLevelSibling = false
                if (isListItem) {
                    while (listStack.isNotEmpty() && listStack.last().indent > sourceIndent) listStack.removeAt(listStack.lastIndex)
                    val top = listStack.lastOrNull()
                    if (top != null && top.indent == sourceIndent) {
                        sameLevelSibling = true
                        if (numbered != null) top.counter++
                    } else {
                        listStack += MdListLevel(sourceIndent, if (numbered != null) 1 else 0)
                    }
                } else if (line.isNotBlank()) {
                    while (listStack.isNotEmpty() && listStack.last().indent >= sourceIndent) listStack.removeAt(listStack.lastIndex)
                }
                val continuation = !isListItem && listStack.isNotEmpty() && sourceIndent > listStack.last().indent
                val visualDepth = when {
                    isListItem -> listStack.size - 1
                    continuation -> listStack.size
                    else -> 0
                }
                val renderedLine = if (continuation) line.drop(minOf(sourceIndent, listStack.last().indent + 4)) else line
                // 每级缩进 20dp = 标记列 14dp + 标记与正文间距 6dp：续行/代码块用 (depth+1)*20，
                // 恰与列表项正文起点对齐；标记在列内左对齐——序号字母列笔直（右对齐会让字母随
                // 字形宽度左右浮动），与正文间距 = 20dp − 字形宽，随标记类型略有差异。
                // 嵌套层级画缩进参考线：落在该级标记列左侧 4dp（级数×20−4dp，避开左对齐字形，
                // 也避开上一级最宽标记），向下延伸 7dp 桥接块间距实现跨行连续；段落打断处自然断开。
                // 顶层列表项保持分组呼吸感；二级同层条目稍松，三级同层条目
                // 使用基础块距，使子列表内部的节奏与父项到首个子项一致。
                val guideLevels = when {
                    isListItem -> visualDepth
                    continuation -> visualDepth - 1
                    else -> 0
                }
                val listTopSpacing = when {
                    !isListItem -> 0.dp
                    visualDepth == 0 -> 8.dp
                    !sameLevelSibling -> 0.dp
                    lines.getOrNull(i - 1)?.isBlank() == true -> 0.dp
                    visualDepth == 1 -> 4.dp
                    else -> 0.dp
                }
                Box(
                    Modifier.fillMaxWidth()
                        .drawBehind {
                            for (level in 1..guideLevels) {
                                val x = (level * 20 - 4).dp.toPx()
                                drawLine(
                                    color = guideColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height + 7.dp.toPx()),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                        }
                        .padding(top = listTopSpacing, bottom = if (isListItem && visualDepth == 0) 2.dp else 0.dp),
                ) {
                Box(Modifier.fillMaxWidth().padding(start = (visualDepth * 20).dp)) {
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
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        val codeLine = lines[i]
                        buf.add(codeLine.drop(minOf(sourceIndent, codeLine.takeWhile { it == ' ' }.length)))
                        i++
                    }
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
                                            fontSize = mdSp(13),
                                            lineHeight = mdLh(20),
                                            softWrap = false,
                                            color = codeTextColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                renderedLine.startsWith("### ") -> MdInlineText(
                    renderedLine.removePrefix("### "), i, answerDirty,
                    modifier = Modifier.padding(top = 7.dp),
                    style = ui.typography.itemTitle.copy(fontSize = mdSp(16), lineHeight = mdLh(24)), color = Theme.MdH3,
                )
                renderedLine.startsWith("## ") -> MdInlineText(
                    renderedLine.removePrefix("## "), i, answerDirty,
                    modifier = Modifier.padding(top = 10.dp),
                    style = ui.typography.sectionTitle.copy(fontSize = mdSp(19), lineHeight = mdLh(28)), color = Theme.MdH2,
                )
                renderedLine.startsWith("# ") -> MdInlineText(
                    renderedLine.removePrefix("# "), i, answerDirty,
                    modifier = Modifier.padding(top = 12.dp),
                    style = ui.typography.pageTitle.copy(fontSize = mdSp(25), lineHeight = mdLh(34)), color = Theme.MdH1,
                )
                renderedLine.startsWith("> ") -> Row(
                    Modifier.fillMaxWidth()
                        .background(Theme.MdQuote.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Box(Modifier.width(2.dp).height(24.dp).background(Theme.MdQuote.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
                    MdInlineText(
                        renderedLine.removePrefix("> "), i, answerDirty,
                        modifier = Modifier.padding(start = 12.dp).fillMaxWidth(),
                        style = TextStyle(fontSize = mdSp(15), lineHeight = mdLh(25)),
                        color = Theme.MdQuote,
                    )
                }
                renderedLine.trim() == "---" -> VDivider()
                task != null -> {
                    val checked = task.groupValues[1].equals("x", ignoreCase = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top) {
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
                            task.groupValues[2], i, answerDirty,
                            modifier = Modifier.padding(start = 3.dp).fillMaxWidth(),
                            style = bodyStyle.copy(color = if (checked) Theme.Muted else contentColor),
                        )
                    }
                }
                bullet -> Row(Modifier.fillMaxWidth()) {
                    // 标记按层级递减明度；菱形与字母/罗马数字共用子级色阶。
                    Text(
                        if (visualDepth > 0) "◆" else "•",
                        modifier = Modifier.widthIn(min = 14.dp).alignByBaseline(),
                        color = markerColor(visualDepth),
                        fontSize = if (visualDepth > 0) 10.sp else 15.sp,
                    )
                    MdInlineText(
                        renderedLine.trimStart().removePrefix("- ").removePrefix("* "), i, answerDirty,
                        modifier = Modifier.padding(start = 6.dp).alignByBaseline(),
                        style = bodyStyle,
                        color = contentColor,
                    )
                }
                numbered != null -> {
                    // 标记随层级区分：一级沿用源文件编号，二级 a. b. c.，三级及更深 i. ii. iii.；无序侧一级 •、更深一律 ◆
                    val counter = listStack.lastOrNull()?.counter ?: 1
                    val marker = when {
                        visualDepth <= 0 -> "${numbered.groupValues[1]}."
                        visualDepth == 1 -> mdOrderedLetterMarker(counter)
                        else -> mdOrderedRomanMarker(counter)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            marker,
                            // 固定标记列宽；widthIn(min) 会被 iii./iv. 的自然宽度撑开，
                            // 导致同一子列表的正文起点逐项右移。超出列宽的字形可占用后方 6dp 空档。
                            Modifier.width(14.dp).alignByBaseline(),
                            // 一级与二级编号均可扫读；三级罗马数字略轻，避免深层内容喧宾夺主。
                            color = markerColor(visualDepth),
                            fontWeight = if (visualDepth < 2) FontWeight.SemiBold else FontWeight.Medium,
                            style = bodyStyle,
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                        )
                        MdInlineText(
                            numbered.groupValues[2], i, answerDirty,
                            modifier = Modifier.padding(start = 6.dp).fillMaxWidth().alignByBaseline(),
                            style = bodyStyle,
                            color = contentColor,
                        )
                    }
                }
                renderedLine.trimStart().startsWith("flowchart") || renderedLine.trimStart().startsWith("graph ") -> {
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
                renderedLine.isBlank() -> Spacer(Modifier.height(3.dp))
                else -> MdInlineText(
                    renderedLine, i, answerDirty,
                    style = bodyStyle,
                )
            }
                }
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
            itemsIndexed(blocks, key = { index, block -> "$index:${block.text.hashCode()}" }) { _, block ->
                Box(Modifier.fillMaxWidth()) {
                    ReaderMarkdownText(
                        block.text,
                        Modifier.widthIn(max = ui.readingMaxWidth).fillMaxWidth().align(Alignment.CenterStart),
                        followingLine = block.followingLine,
                    )
                }
            }
        }
    }
}

/** 保持围栏代码、表格和无围栏 Mermaid 完整；普通行单独成为可虚拟化的块。 */
private fun markdownBlocks(md: String): List<MdBlock> {
    val lines = md.lines()
    val blocks = ArrayList<MdBlock>(lines.size)
    var index = 0
    while (index < lines.size) {
        val start = index
        val table = parseMarkdownTable(lines, index)
        when {
            markdownNumberedPattern.containsMatchIn(lines[index]) ||
                markdownTaskPattern.containsMatchIn(lines[index]) ||
                markdownBulletPattern.containsMatchIn(lines[index]) -> {
                val listIndent = lines[index].takeWhile { it == ' ' }.length
                index++
                while (index < lines.size) {
                    val next = lines[index]
                    if (next.isBlank()) { index++; continue }
                    val nextIndent = next.takeWhile { it == ' ' }.length
                    if (nextIndent <= listIndent) break
                    if (next.trimStart().startsWith("```")) {
                        index++
                        while (index < lines.size && !lines[index].trimStart().startsWith("```")) index++
                        if (index < lines.size) index++
                    } else {
                        index++
                    }
                }
            }
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
        blocks += MdBlock(lines.subList(start, index).joinToString("\n"), start, lines.getOrNull(index))
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
