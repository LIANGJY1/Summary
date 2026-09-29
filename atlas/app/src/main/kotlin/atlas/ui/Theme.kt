package atlas.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 一种明暗模式下完整、非空的 Markdown 语义色。 */
data class MdSpec(
    val h1: Color,
    val h2: Color,
    val h3: Color,
    val bold: Color,
    val link: Color,
    val quote: Color,
    val inlineCode: Color,
    val inlineCodeBg: Color,
)

/** 一种明暗模式下的完整语义色；运行期不再从另一种模式机械派生。 */
data class ThemeSpec(
    val accent: Color,
    val okGreen: Color,
    val warnOrange: Color,
    val badRed: Color,
    val muted: Color,
    val codeBg: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val outline: Color,
    val outlineVariant: Color,
    val mdH1: Color,
    val mdH2: Color,
    val mdH3: Color,
    val mdBold: Color,
    val mdLink: Color,
    val mdQuote: Color,
    val mdInlineCode: Color,
    val mdInlineCodeBg: Color,
) {
    fun md() = MdSpec(mdH1, mdH2, mdH3, mdBold, mdLink, mdQuote, mdInlineCode, mdInlineCodeBg)

    fun elevatedSurface(@Suppress("UNUSED_PARAMETER") dark: Boolean): Color =
        lerp(surface, surfaceVariant, 0.68f)

    fun inputBg(@Suppress("UNUSED_PARAMETER") dark: Boolean): Color = lerp(background, surface, 0.72f)

    /** Derived interaction surfaces keep the persisted palette small while making state styling consistent. */
    fun panelSurface(): Color = lerp(background, surface, 0.65f)

    fun hoverSurface(): Color = lerp(surfaceVariant, accent, 0.045f)

    fun selectedSurface(): Color = lerp(surfaceVariant, accent, 0.12f)

    fun pressedSurface(): Color = lerp(surfaceVariant, accent, 0.18f)

    fun codeBlockSurface(): Color = lerp(codeBg, surfaceVariant, 0.18f)

    /** Accent is reserved for actions and focus; status colors stay quiet and local to their semantic role. */
    fun infoColor(): Color = mdH2

    fun borderStrong(): Color = lerp(outline, mdH2, 0.26f)

    fun toMaterialScheme(dark: Boolean): ColorScheme =
        (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = accent,
            onPrimary = contentOn(accent),
            primaryContainer = selectedSurface(),
            onPrimaryContainer = onSurface,
            secondary = mdLink,
            onSecondary = contentOn(mdLink),
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = onSurface,
            tertiary = mdQuote,
            onTertiary = contentOn(mdQuote),
            tertiaryContainer = surfaceVariant,
            onTertiaryContainer = onSurface,
            error = badRed,
            onError = contentOn(badRed),
            errorContainer = lerp(surfaceVariant, badRed, 0.12f),
            onErrorContainer = badRed,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = muted,
            surfaceTint = Color.Transparent,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            inversePrimary = accent,
            outline = outline,
            outlineVariant = outlineVariant,
        )

    fun hexList(): List<String> = listOf(
        accent, okGreen, warnOrange, badRed, muted, codeBg,
        background, surface, surfaceVariant, onSurface, outline, outlineVariant,
        mdH1, mdH2, mdH3, mdBold, mdLink, mdQuote, mdInlineCode, mdInlineCodeBg,
    ).map(::hexOf)

    companion object {
        const val BASE = 12
        const val SIZE = 20

        fun fromHexList(hexes: List<String>): ThemeSpec? {
            if (hexes.size != SIZE) return null
            val c = hexes.map { parseHexColor(it) ?: return null }
            return ThemeSpec(
                accent = c[0], okGreen = c[1], warnOrange = c[2], badRed = c[3],
                muted = c[4], codeBg = c[5], background = c[6], surface = c[7],
                surfaceVariant = c[8], onSurface = c[9], outline = c[10], outlineVariant = c[11],
                mdH1 = c[12], mdH2 = c[13], mdH3 = c[14], mdBold = c[15],
                mdLink = c[16], mdQuote = c[17], mdInlineCode = c[18], mdInlineCodeBg = c[19],
            )
        }

        val LABELS = listOf(
            "主强调钢蓝", "成功绿", "警告黄", "错误红", "次要文字", "代码块底",
            "页面底色", "内容面", "悬浮与输入面", "正文色", "交互轮廓", "装饰分隔",
            "一级标题", "二级标题", "三级标题", "加粗", "链接", "引用", "行内代码字", "行内代码底",
        )
    }
}

/** 一套主题同时拥有独立调校的浅色与深色色板。 */
data class ThemeDefinition(
    val name: String,
    val light: ThemeSpec,
    val dark: ThemeSpec,
) {
    fun spec(dark: Boolean): ThemeSpec = if (dark) this.dark else light
}

/** 用户主题的 V2 格式：`v2|名称|l1|…|l20|d1|…|d20`。 */
data class CustomTheme(
    val name: String,
    val light: ThemeSpec,
    val dark: ThemeSpec,
) {
    fun spec(dark: Boolean): ThemeSpec = if (dark) this.dark else light

    fun encode(): String =
        (listOf(VERSION, safeName(name)) + light.hexList() + dark.hexList()).joinToString("|")

    companion object {
        private const val VERSION = "v2"
        private const val V2_PARTS = 2 + ThemeSpec.SIZE * 2

        fun nameOf(raw: String): String {
            val parts = raw.split('|', limit = 3)
            return if (parts.firstOrNull() == VERSION) parts.getOrNull(1).orEmpty().trim()
            else parts.firstOrNull().orEmpty().trim()
        }

        /** V2 正常读取；旧 12/18 色记录只在此迁移一次。 */
        fun decode(raw: String): CustomTheme? {
            val parts = raw.split('|')
            return if (parts.firstOrNull() == VERSION) {
                if (parts.size != V2_PARTS) return null
                val name = parts[1].trim().takeIf { it.isNotEmpty() } ?: return null
                val light = ThemeSpec.fromHexList(parts.subList(2, 2 + ThemeSpec.SIZE)) ?: return null
                val dark = ThemeSpec.fromHexList(parts.subList(2 + ThemeSpec.SIZE, V2_PARTS)) ?: return null
                CustomTheme(name, light, dark)
            } else {
                decodeLegacy(parts)
            }
        }

        private fun decodeLegacy(parts: List<String>): CustomTheme? {
            if (parts.size != 13 && parts.size != 19) return null
            val name = parts[0].trim().takeIf { it.isNotEmpty() } ?: return null
            val legacy = legacySpec(parts.drop(1)) ?: return null
            return CustomTheme(name, legacy.legacyLight(), legacy.legacyDark())
        }

        private fun safeName(value: String): String =
            value.replace('|', ' ').replace(';', ' ').trim()
    }
}

/** 内置主题色板：每套主题的浅深模式均为独立人工校准值。 */
object AtlasThemes {
    const val NAME = "Atlas"
    const val BLACK_NAME = "黑曜"

    val ATLAS = ThemeDefinition(
        name = NAME,
        dark = ThemeSpec(
            accent = color("#9EBCE8"), okGreen = color("#9BC9AE"),
            warnOrange = color("#D4B486"), badRed = color("#DA9B9E"),
            muted = color("#A3ADB9"), codeBg = color("#1B2026"),
            background = color("#1E2126"), surface = color("#252A31"),
            surfaceVariant = color("#2E353E"), onSurface = color("#DDE2E8"),
            outline = color("#6B788A"), outlineVariant = color("#373F49"),
            mdH1 = color("#E9EDF2"), mdH2 = color("#B7C8E0"),
            mdH3 = color("#DDE2E8"), mdBold = color("#F4F6F8"),
            mdLink = color("#A9C4E8"), mdQuote = color("#AEB9C7"),
            mdInlineCode = color("#B7C1CE"), mdInlineCodeBg = color("#2D343C"),
        ),
        light = ThemeSpec(
            accent = color("#365F91"), okGreen = color("#2F7453"),
            warnOrange = color("#875A28"), badRed = color("#A4474F"),
            muted = color("#586270"), codeBg = color("#F2F3F1"),
            background = color("#F2F3F1"), surface = color("#FCFCFB"),
            surfaceVariant = color("#E7E9E7"), onSurface = color("#252A31"),
            outline = color("#6E7A88"), outlineVariant = color("#D5D9D8"),
            mdH1 = color("#20272F"), mdH2 = color("#3E5B7F"),
            mdH3 = color("#30373E"), mdBold = color("#171C22"),
            mdLink = color("#245A88"), mdQuote = color("#505C68"),
            mdInlineCode = color("#46525E"), mdInlineCodeBg = color("#E8ECEF"),
        ),
    )

    /** 中性炭黑灰阶画布（R=G=B，无色相偏移）与柔和文字层级；低彩蓝灰只用于链接及少量焦点提示。 */
    val BLACK = ThemeDefinition(
        name = BLACK_NAME,
        dark = ThemeSpec(
            accent = color("#AEB3BA"), okGreen = color("#84A78D"),
            warnOrange = color("#B49A73"), badRed = color("#BC8588"),
            muted = color("#919191"), codeBg = color("#131313"),
            background = color("#151515"), surface = color("#1B1B1B"),
            surfaceVariant = color("#222222"), onSurface = color("#D2D2D2"),
            outline = color("#686868"), outlineVariant = color("#2A2A2A"),
            mdH1 = color("#DBDBDB"), mdH2 = color("#C1C1C1"),
            mdH3 = color("#D0D0D0"), mdBold = color("#E0E0E0"),
            mdLink = color("#9BB1CE"), mdQuote = color("#A6A6A6"),
            mdInlineCode = color("#B2B2B2"), mdInlineCodeBg = color("#262626"),
        ),
        light = ThemeSpec(
            accent = color("#4B5058"), okGreen = color("#2F7453"),
            warnOrange = color("#875A28"), badRed = color("#A4474F"),
            muted = color("#62666D"), codeBg = color("#F0F0EE"),
            background = color("#F4F4F2"), surface = color("#FFFFFF"),
            surfaceVariant = color("#E9E9E7"), onSurface = color("#25272B"),
            outline = color("#70757E"), outlineVariant = color("#D8D9D7"),
            mdH1 = color("#202226"), mdH2 = color("#4A5058"),
            mdH3 = color("#292C30"), mdBold = color("#101214"),
            mdLink = color("#3E5B7A"), mdQuote = color("#565B62"),
            mdInlineCode = color("#484D54"), mdInlineCodeBg = color("#EBECEA"),
        ),
    )

    val ALL: List<ThemeDefinition> = listOf(ATLAS, BLACK)
    val DEFAULT: ThemeDefinition = ATLAS

    fun byName(name: String): ThemeDefinition = ALL.firstOrNull { it.name == name } ?: DEFAULT
    fun specOf(name: String, dark: Boolean): ThemeSpec = byName(name).spec(dark)
}

/** 将旧单模式记录映射到新版字段；仅供 [CustomTheme.decode] 的迁移分支使用。 */
private fun legacySpec(hexes: List<String>): ThemeSpec? {
    if (hexes.size != 12 && hexes.size != 18) return null
    val padded = hexes + List(18 - hexes.size) { "" }
    val c = padded.map { if (it.isBlank()) null else parseHexColor(it) ?: return null }
    val base = c.take(12).map { it ?: return null }
    return ThemeSpec(
        accent = base[0], okGreen = base[1], warnOrange = base[2], badRed = base[3],
        muted = base[4], codeBg = base[5], background = base[7], surface = base[8],
        surfaceVariant = base[9], onSurface = base[10], outline = base[4], outlineVariant = base[11],
        mdH1 = c[12] ?: base[0], mdH2 = c[12] ?: base[0], mdH3 = base[10],
        mdBold = c[13] ?: base[0], mdLink = c[14] ?: base[0], mdQuote = c[15] ?: base[4],
        mdInlineCode = c[16] ?: base[6], mdInlineCodeBg = c[17] ?: base[9],
    )
}

private fun ThemeSpec.legacyLight(): ThemeSpec = copy(
    background = towardWhite(background, 0.97f),
    surface = towardWhite(surface, 0.94f),
    surfaceVariant = towardWhite(surfaceVariant, 0.88f),
    onSurface = towardBlack(onSurface, 0.16f),
    muted = towardBlack(muted, 0.34f),
    codeBg = towardWhite(codeBg, 0.95f),
    outline = towardBlack(outline, 0.30f),
    outlineVariant = towardBlack(outlineVariant, 0.14f),
    mdInlineCodeBg = towardWhite(mdInlineCodeBg, 0.90f),
).readable()

private fun ThemeSpec.legacyDark(): ThemeSpec = copy(
    background = towardBlack(background, 0.13f),
    surface = towardBlack(surface, 0.17f),
    surfaceVariant = towardBlack(surfaceVariant, 0.19f),
    onSurface = towardWhite(onSurface, 0.88f),
    muted = towardWhite(muted, 0.42f),
    codeBg = towardBlack(codeBg, 0.20f),
    outline = towardWhite(outline, 0.32f),
    outlineVariant = towardWhite(outlineVariant, 0.20f),
    mdInlineCodeBg = towardBlack(mdInlineCodeBg, 0.22f),
).readable()

private fun ThemeSpec.readable(): ThemeSpec = copy(
    accent = accent.readableOn(background, surface, 3.0),
    okGreen = okGreen.readableOn(background, surface, 3.0),
    warnOrange = warnOrange.readableOn(background, surface, 3.0),
    badRed = badRed.readableOn(background, surface, 3.0),
    muted = muted.readableOn(background, surface, 4.5),
    onSurface = onSurface.readableOn(background, surface, 4.5),
    outline = outline.readableOn(background, surface, 3.0),
    mdH1 = mdH1.readableOn(background, surface, 3.0),
    mdH2 = mdH2.readableOn(background, surface, 3.0),
    mdH3 = mdH3.readableOn(background, surface, 4.5),
    mdBold = mdBold.readableOn(background, surface, 4.5),
    mdLink = mdLink.readableOn(background, surface, 4.5),
    mdQuote = mdQuote.readableOn(background, surface, 4.5),
    mdInlineCode = mdInlineCode.readableOn(background, surface, 4.5),
)

private fun Color.readableOn(background: Color, surface: Color, min: Double): Color =
    ensureContrast(ensureContrast(this, background, min), surface, min)

private fun ensureContrast(color: Color, background: Color, min: Double): Color {
    if (contrastRatio(color, background) >= min) return color
    val target = if (background.relativeLuma() > 0.5f) BLACK else WHITE
    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        if (contrastRatio(lerp(color, target, mid), background) >= min) high = mid else low = mid
    }
    return lerp(color, target, high)
}

private fun towardWhite(color: Color, goal: Float): Color = converge(color, WHITE, goal)
private fun towardBlack(color: Color, goal: Float): Color = converge(color, BLACK, goal)

private fun converge(color: Color, target: Color, goal: Float): Color {
    val toWhite = target.relativeLuma() > 0.5f
    fun step(from: Color): Float {
        val current = from.relativeLuma()
        val remaining = if (toWhite) 1f - current else current
        if (remaining < 0.004f) return 0f
        return (if (toWhite) (goal - current) / remaining else (current - goal) / remaining)
            .coerceIn(0f, 1f)
    }
    var result = lerp(color, target, step(color))
    repeat(3) { result = lerp(result, target, step(result)) }
    return result
}

private fun contrastRatio(a: Color, b: Color): Double {
    val light = maxOf(a.relativeLuma(), b.relativeLuma()).toDouble()
    val dark = minOf(a.relativeLuma(), b.relativeLuma()).toDouble()
    return (light + 0.05) / (dark + 0.05)
}

private fun contentOn(color: Color): Color =
    if (contrastRatio(BLACK, color) >= contrastRatio(WHITE, color)) BLACK else WHITE

private fun Color.relativeLuma(): Float =
    0.2126f * linearize(red) + 0.7152f * linearize(green) + 0.0722f * linearize(blue)

private fun linearize(value: Float): Float =
    if (value <= 0.04045f) value / 12.92f
    else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

private fun color(value: String): Color = requireNotNull(parseHexColor(value))
private val WHITE = Color(0xFFFFFFFF.toInt())
private val BLACK = Color(0xFF000000.toInt())

/** Color → `#AARRGGBB`。 */
internal fun hexOf(color: Color): String {
    fun channel(value: Float) = (value * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(
        channel(color.alpha), channel(color.red), channel(color.green), channel(color.blue),
    )
}
