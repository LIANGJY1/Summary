package atlas.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 主题生效后的 Markdown 语义色，全部非空。 */
data class MdSpec(
    val heading: Color,
    val bold: Color,
    val link: Color,
    val quote: Color,
    val inlineCode: Color,
    val inlineCodeBg: Color?,
)

/**
 * 一套主题的完整语义色。业务色与 [AtlasPalette] 一一对应，另加表面层——本应用近 50 处颜色取自
 * Material3 的 `colorScheme` 而不是 [AtlasPalette]，只换强调色会让所有底色不变，所以表面层必须进主题。
 */
data class ThemeSpec(
    val accent: Color,
    val okGreen: Color,
    val warnOrange: Color,
    val badRed: Color,
    val muted: Color,
    val codeBg: Color,
    val inlineCodeFg: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val outlineVariant: Color,
    // 以下六项是本主题自带的 Markdown 语义色；null = 由基色推导（见 [md]）。
    // 放在末尾且可空，内置 11 套主题的 12 参数构造不必改，自定义主题则能存自己的值。
    val mdHeading: Color? = null,
    val mdBold: Color? = null,
    val mdLink: Color? = null,
    val mdQuote: Color? = null,
    val mdInlineCode: Color? = null,
    val mdInlineCodeBg: Color? = null,
) {
    /** 生效后的 Markdown 语义色：未显式指定的项回落到本主题基色。 */
    /**
     * 持久化用的 markdown 六项。存**生效值**而非 null 占位：主题是整体，
     * 存推导结果后即使日后推导规则变化，已存主题的观感也不会漂移。
     */
    fun hexListOfMd(): List<String> {
        val m = md()
        return listOf(
            hexOf(m.heading), hexOf(m.bold), hexOf(m.link),
            hexOf(m.quote), hexOf(m.inlineCode), m.inlineCodeBg?.let(::hexOf) ?: "",
        )
    }

    fun md(): MdSpec = MdSpec(
        heading = mdHeading ?: accent,
        bold = mdBold ?: accent,
        link = mdLink ?: accent,
        quote = mdQuote ?: muted,
        inlineCode = mdInlineCode ?: inlineCodeFg,
        inlineCodeBg = mdInlineCodeBg,
    )

    /** 主题派生的表面色：卡片底比页面底略凸（深色下更亮、浅色下更亮），悬浮与输入框再抬一层。 */
    fun raise(c: Color, toWhite: Boolean, t: Float): Color =
        lerp(c, if (toWhite) WHITE else BLACK, t)

    fun elevatedSurface(dark: Boolean): Color = raise(surface, !dark, 0.04f)

    fun inputBg(dark: Boolean): Color = raise(surfaceVariant, !dark, 0.30f)

    fun toMaterialScheme(dark: Boolean): ColorScheme =
        (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            onSecondary = Color.White,
            error = badRed,
            onError = Color.White,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = muted,
            outline = muted,
            outlineVariant = outlineVariant,
        )

    /**
     * 派生浅色版：把背景/表面压到接近白，正文压到接近黑，强调色保持不变并略微提亮。
     * 逐通道线性插值到固定端点，保证「浅色版一定够浅」而不是靠手调。
     */
    fun lighten(): ThemeSpec = map(
        background = towardWhite(background, LIGHT_BG),
        surface = towardWhite(surface, 0.94f),
        onSurface = towardBlack(onSurface, LIGHT_FG),
        muted = towardBlack(muted, 0.34f),
        outlineVariant = towardBlack(outlineVariant, 0.14f),
        codeBg = towardWhite(codeBg, 0.95f),
        surfaceVariant = towardWhite(surfaceVariant, 0.88f),
    ).readable()

    /** 派生深色版：与 [lighten] 对称。 */
    fun darken(): ThemeSpec = map(
        background = towardBlack(background, DARK_BG),
        surface = towardBlack(surface, DARK_SURFACE),
        onSurface = towardWhite(onSurface, DARK_FG),
        muted = towardWhite(muted, 0.42f),
        outlineVariant = towardWhite(outlineVariant, 0.20f),
        codeBg = towardBlack(codeBg, 0.20f),
        surfaceVariant = towardBlack(surfaceVariant, 0.19f),
    ).readable()

    private fun map(
        @Suppress("LongParameterList")
        accent: Color = this.accent, okGreen: Color = this.okGreen,
        warnOrange: Color = this.warnOrange, badRed: Color = this.badRed,
        muted: Color = this.muted, codeBg: Color = this.codeBg,
        inlineCodeFg: Color = this.inlineCodeFg, background: Color = this.background,
        surface: Color = this.surface, surfaceVariant: Color = this.surfaceVariant,
        onSurface: Color = this.onSurface, outlineVariant: Color = this.outlineVariant,
    ) = ThemeSpec(accent, okGreen, warnOrange, badRed, muted, codeBg, inlineCodeFg,
        background, surface, surfaceVariant, onSurface, outlineVariant)

    fun hexList(): List<String> = listOf(
        hexOf(accent), hexOf(okGreen), hexOf(warnOrange), hexOf(badRed), hexOf(muted),
        hexOf(codeBg), hexOf(inlineCodeFg), hexOf(background), hexOf(surface),
        hexOf(surfaceVariant), hexOf(onSurface), hexOf(outlineVariant),
    ) + hexListOfMd()

    companion object {
        const val BASE = 12
        const val SIZE = 18

        /**
         * 接受 18 项（12 基色 + 6 markdown，空串表示该项未指定、走推导）或旧版 12 项。
         * 任一非空色值非法即整体返回 null：主题是一个整体，半套配色比回退旧主题更糟。
         */
        fun fromHexList(hexes: List<String>): ThemeSpec? {
            if (hexes.size != SIZE && hexes.size != BASE) return null
            val padded = hexes + List(SIZE - hexes.size) { "" }
            val c = padded.map { if (it.isBlank()) null else parseHexColor(it) ?: return null }
            // 前 12 项基色必填：留空会让整套主题失去底色/正文色，比拒绝更糟。
            // 这里必须逐个判空返回 null，不能用 !! —— 空串会绕过 parseHexColor 变成 null，
            // 强解包会在用户清空输入框时抛 NPE 到 AWT 事件线程（表现为"Unknown error"弹窗）。
            val base = c.take(BASE).map { it ?: return null }
            return ThemeSpec(
                base[0], base[1], base[2], base[3],
                base[4], base[5], base[6], base[7],
                base[8], base[9], base[10], base[11],
                mdHeading = c[12], mdBold = c[13], mdLink = c[14],
                mdQuote = c[15], mdInlineCode = c[16], mdInlineCodeBg = c[17],
            )
        }

        /** 字段顺序即 [hexList] 的顺序，调整会让已存的自定义主题错位。 */
        val LABELS = listOf(
            "强调色", "成功绿", "警告橙", "错误红", "次要文字", "代码块底",
            "行内代码", "页面底色", "卡片底色", "控件底色", "正文色", "分隔线",
            "标题", "加粗", "链接", "引用", "行内代码字", "代码底色",
        )
    }
}

/**
 * 用户自建主题：名字 + 一套 [ThemeSpec]。
 *
 * 持久化格式 `名称|h1|h2|…|h12`，多条主题之间用 `;` 分隔。字段分隔符必须用 `|` 而不是 `,`——
 * 12 个 hex 本身含逗号，与 `settings.properties` 里列表字段惯用的逗号分隔会撞车，一条主题会被拆成碎片。
 */
data class CustomTheme(val name: String, val spec: ThemeSpec) {

    fun encode(): String =
        (listOf(name.replace('|', ' ').replace(';', ' ').trim()) + spec.hexList()).joinToString("|")

    companion object {
        /**
         * 记录格式是 `名称|h1|…|h18`——名字在**最前**，不是最后。
         * 按名字定位记录一律走这里，勿就地 split。
         */
        fun nameOf(raw: String): String = raw.substringBefore('|').trim()

        fun decode(raw: String): CustomTheme? {
            val parts = raw.split("|")
            if (parts.size != ThemeSpec.SIZE + 1) return null
            val name = parts[0].trim()
            if (name.isEmpty()) return null
            val spec = ThemeSpec.fromHexList(parts.drop(1)) ?: return null
            return CustomTheme(name, spec)
        }
    }
}

/** 内置主题注册表，每套给明暗两版。 */
/**
 * 内置主题注册表。色值不是手挑的，而是从各主题的官方源抓取解析而来：
 * Catppuccin（`catppuccin/palette`）、Nord（`arcticicestudio/nord`）、Ayu（`ayu-theme/ayu-colors`）、
 * Gruvbox（`morhetz/gruvbox`）、One Dark（`atom/one-dark-syntax`）。
 *
 * 每套只有一份配色，深浅色由 [ThemeSpec.darken] / [ThemeSpec.lighten] 派生——
 * 手工维护两套 inevitably 会漂移。
 */
object AtlasThemes {

    data class Entry(val name: String, val spec: ThemeSpec)

    /** 抓取自权威源的原始配色。 */
    private val RAW: List<Entry> = listOf(
    Entry(
        "Catppuccin Latte",
        ThemeSpec(
            hex(0xFF1E66F5),
            hex(0xFF40A02B),
            hex(0xFFFE640B),
            hex(0xFFD20F39),
            hex(0xFF6C6F85),
            hex(0xFFE6E9EF),
            hex(0xFF8839EF),
            hex(0xFFEFF1F5),
            hex(0xFFE6E9EF),
            hex(0xFFDCE0E8),
            hex(0xFF4C4F69),
            hex(0xFFACB0BE),
        ),
    ),
    Entry(
        "Catppuccin Frappe",
        ThemeSpec(
            hex(0xFF8CAAEE),
            hex(0xFFA6D189),
            hex(0xFFEF9F76),
            hex(0xFFE78284),
            hex(0xFFA5ADCE),
            hex(0xFF292C3C),
            hex(0xFFCA9EE6),
            hex(0xFF303446),
            hex(0xFF292C3C),
            hex(0xFF232634),
            hex(0xFFC6D0F5),
            hex(0xFF626880),
        ),
    ),
    Entry(
        "Catppuccin Macchiato",
        ThemeSpec(
            hex(0xFF8AADF4),
            hex(0xFFA6DA95),
            hex(0xFFF5A97F),
            hex(0xFFED8796),
            hex(0xFFA5ADCB),
            hex(0xFF1E2030),
            hex(0xFFC6A0F6),
            hex(0xFF24273A),
            hex(0xFF1E2030),
            hex(0xFF181926),
            hex(0xFFCAD3F5),
            hex(0xFF5B6078),
        ),
    ),
    Entry(
        "Catppuccin Mocha",
        ThemeSpec(
            hex(0xFF89B4FA),
            hex(0xFFA6E3A1),
            hex(0xFFFAB387),
            hex(0xFFF38BA8),
            hex(0xFFA6ADC8),
            hex(0xFF181825),
            hex(0xFFCBA6F7),
            hex(0xFF1E1E2E),
            hex(0xFF181825),
            hex(0xFF11111B),
            hex(0xFFCDD6F4),
            hex(0xFF585B70),
        ),
    ),
    Entry(
        "Nord",
        ThemeSpec(
            hex(0xFF88C0D0),
            hex(0xFFA3BE8C),
            hex(0xFFEBCB8B),
            hex(0xFFBF616A),
            hex(0xFF4C566A),
            hex(0xFF3B4252),
            hex(0xFF81A1C1),
            hex(0xFF2E3440),
            hex(0xFF3B4252),
            hex(0xFF434C5E),
            hex(0xFFD8DEE9),
            hex(0xFF434C5E),
        ),
    ),
    Entry(
        "Nord Light",
        ThemeSpec(
            hex(0xFF5E81AC),
            hex(0xFFA3BE8C),
            hex(0xFFEBCB8B),
            hex(0xFFBF616A),
            hex(0xFF5E81AC),
            hex(0xFFF0F4F8),
            hex(0xFF81A1C1),
            hex(0xFFFFFFFF),
            hex(0xFFF0F4F8),
            hex(0xFFE5E9F0),
            hex(0xFF2E3440),
            hex(0xFFD8DEE9),
        ),
    ),
    Entry(
        "Ayu Light",
        ThemeSpec(
            hex(0xFF22A4E6),
            hex(0xFF86B300),
            hex(0xFFFA8532),
            hex(0xFFF07171),
            hex(0xFF8C9196),
            hex(0xFFF3F4F6),
            hex(0xFF4CBF99),
            hex(0xFFF8F9FB),
            hex(0xFFF3F4F6),
            hex(0xFFEAECEF),
            hex(0xFF828E9F),
            hex(0xFFEAECEF),
        ),
    ),
    Entry(
        "Ayu Dark",
        ThemeSpec(
            hex(0xFF59C2FF),
            hex(0xFFAAD94C),
            hex(0xFFFF8F40),
            hex(0xFFF07178),
            hex(0xFF6C7380),
            hex(0xFF0B0E14),
            hex(0xFF95E6CB),
            hex(0xFF0F1419),
            hex(0xFF0B0E14),
            hex(0xFF131720),
            hex(0xFF5A6378),
            hex(0xFF131720),
        ),
    ),
    Entry(
        "Ayu Mirage",
        ThemeSpec(
            hex(0xFF73D0FF),
            hex(0xFFD5FF80),
            hex(0xFFFFA659),
            hex(0xFFF28779),
            hex(0xFF6C7380),
            hex(0xFF242C38),
            hex(0xFF95E6CB),
            hex(0xFF1F2430),
            hex(0xFF242C38),
            hex(0xFF2D3641),
            hex(0xFF707A8C),
            hex(0xFF2D3641),
        ),
    ),
    Entry(
        "One Dark",
        ThemeSpec(
            hex(0xFF528BFF),
            hex(0xFF98C379),
            hex(0xFFD19A66),
            hex(0xFFE06C75),
            hex(0xFF828997),
            hex(0xFF2F333D),
            hex(0xFF56B6C2),
            hex(0xFF282C34),
            hex(0xFF2F333D),
            hex(0xFF373D48),
            hex(0xFFABB2BF),
            hex(0xFF5C6370),
        ),
    ),
    Entry(
        "Gruvbox Dark",
        ThemeSpec(
            hex(0xFFFABD2F),
            hex(0xFFB8BB26),
            hex(0xFFFE8019),
            hex(0xFFFB4934),
            hex(0xFF928374),
            hex(0xFF32302F),
            hex(0xFFD3869B),
            hex(0xFF282828),
            hex(0xFF32302F),
            hex(0xFF3C3836),
            hex(0xFFFBF1C7),
            hex(0xFF504945),
        ),
    ),
    )

    val ALL: List<Entry> = RAW

    val DEFAULT: Entry = RAW.first { it.name == "Catppuccin Mocha" }

    fun byName(name: String): Entry = ALL.firstOrNull { it.name == name } ?: DEFAULT

    fun specOf(name: String, dark: Boolean): ThemeSpec {
        val e = byName(name)
        return if (dark) e.spec.darken() else e.spec.lighten()
    }
}

private fun hex(v: Long): Color = Color(v.toInt())

private val WHITE = Color(0xFFFFFFFF.toInt())
private val BLACK = Color(0xFF000000.toInt())

/** 派生目标端的明度：浅色版底/面 0.97/0.94、正文 0.16；深色版底/面 0.13/0.17、正文 0.88。 */
private const val LIGHT_BG = 0.97f
private const val LIGHT_FG = 0.16f
private const val DARK_BG = 0.13f
private const val DARK_SURFACE = 0.17f
private const val DARK_FG = 0.88f

/**
 * 把 [c] 朝白或黑收敛，使结果的相对亮度**恰好**落在 [targetLuma] 附近，而不是按固定比例混色。
 * 固定比例对浅色主题失效：`#EFF1F5` 按 0.55 混黑只得到 `#4F5051`（中灰），而"深色版"应接近黑。
 * 按目标明度反解权重才对任何源色成立。
 */
/**
 * 语义色（强调/成功/警告/错误）不做亮度收敛——它们是"有彩色"，压到固定亮度会毁掉色相
 * （`#1E66F5` 收到 0.10 亮度会变成近黑的 `#0A317E`）。深色版仅在过暗时轻微提亮，保证在深底上够亮。
 */
/** goal 是**目标相对亮度**（0=黑 1=白），两种方向都用同一语义。 */
private fun towardWhite(c: Color, goal: Float): Color = converge(c, WHITE, goal)

private fun towardBlack(c: Color, goal: Float): Color = converge(c, BLACK, goal)

private fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuma().toDouble()
    val lb = b.relativeLuma().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * 换背景后彩色语义色会变得不可读：深色主题的淡强调色落到近白底上只剩约 2:1。
 * 按对比度下限把它朝背景反方向拉到刚好达标。sRGB lerp 向黑是等比缩放，色相饱和度不变。
 */
private fun ensureContrast(c: Color, bg: Color, min: Double): Color {
    if (contrastRatio(c, bg) >= min) return c
    val away = if (bg.relativeLuma() > 0.5f) Color(0xFF000000.toInt()) else Color(0xFFFFFFFF.toInt())
    var lo = 0.0
    var hi = 1.0
    repeat(20) {
        val mid = (lo + hi) / 2
        if (contrastRatio(lerp(c, away, mid.toFloat()), bg) >= min) hi = mid else lo = mid
    }
    return lerp(c, away, hi.toFloat())
}

/** 底色与卡片面亮度不同（浅色版 surface 比 background 略暗），两者都要达标。 */
private fun Color.readableOn(bg: Color, surface: Color, min: Double): Color =
    ensureContrast(ensureContrast(this, bg, min), surface, min)

private fun ThemeSpec.readable(): ThemeSpec {
    val m = md()
    return copy(
        accent = accent.readableOn(background, surface, MIN_ACCENT),
        okGreen = okGreen.readableOn(background, surface, MIN_ACCENT),
        warnOrange = warnOrange.readableOn(background, surface, MIN_ACCENT),
        badRed = badRed.readableOn(background, surface, MIN_ACCENT),
        muted = muted.readableOn(background, surface, MIN_TEXT),
        inlineCodeFg = inlineCodeFg.readableOn(background, surface, MIN_TEXT),
        mdHeading = m.heading.readableOn(background, surface, MIN_ACCENT),
        mdBold = m.bold.readableOn(background, surface, MIN_ACCENT),
        mdLink = m.link.readableOn(background, surface, MIN_ACCENT),
        mdQuote = m.quote.readableOn(background, surface, MIN_TEXT),
        mdInlineCode = m.inlineCode.readableOn(background, surface, MIN_TEXT),
    )
}

private const val MIN_ACCENT = 3.0
private const val MIN_TEXT = 4.5

private fun converge(c: Color, target: Color, goal: Float): Color {
    val cur = c.relativeLuma()
    // lerp 的 t 沿「起点→终点」单调，剩余空间即 |1 - 目标亮度|；t = 已走距离 / 剩余空间。
    // 两种方向都取正数，收敛到黑时不能写成 goal - cur（那是负数，会被夹成 0 而整段不动）。
    // 剩余空间 = 「当前色」到「目标端点」的亮度距离，目标端点是纯白(1.0)或纯黑(0.0)。
    // 不能拿 goal 当剩余空间：goal=0.13 时 (0.13-0.878)/0.13 为负，会被夹成 0 而整段不动——
    // 这正是"浅色主题派生深色版完全没变化"的成因。
    val toWhite = target.relativeLuma() > 0.5f
    fun step(from: Color): Float {
        val got = from.relativeLuma()
        val remain = if (toWhite) 1f - got else got
        if (remain < 0.004f) return 0f
        val t = if (toWhite) (goal - got) / remain else (got - goal) / remain
        return t.coerceIn(0f, 1f)
    }

    var out = lerp(c, target, step(c))
    // lerp 在 sRGB 空间插值，与线性亮度不成正比，一次未必到位；按残差再收敛。
    repeat(3) {
        if (kotlin.math.abs(goal - out.relativeLuma()) < 0.008f) return out
        out = lerp(out, target, step(out))
    }
    return out
}

/** 相对亮度（Rec.709 对 RGB 线性化）。只用于反解派生权重，无需感知均匀。 */
private fun Color.relativeLuma(): Float =
    0.2126f * linearize(red) + 0.7152f * linearize(green) + 0.0722f * linearize(blue)

private fun linearize(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

/**
 * Color → `#AARRGGBB`。走 red/green/blue 浮点分量而不是 packed `value`：该 Compose 版本把 sRGB 放在
 * packed 值的高 32 位，直接掩码低位会得到错误颜色（实测 `Color(0xFF1A56DB).value` 与分量不一致）。
 */
internal fun hexOf(c: Color): String {
    fun ch(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(ch(c.alpha), ch(c.red), ch(c.green), ch(c.blue))
}
