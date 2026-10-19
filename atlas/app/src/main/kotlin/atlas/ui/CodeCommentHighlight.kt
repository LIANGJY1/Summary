package atlas.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * 围栏代码块的注释弱化（IDE 同款观感：注释变浅、代码原色），非全量语法高亮。
 * 轻量扫描器按语言族识别注释区间：字符串感知避免 "https://…" 一类字面量被误判，
 * 块注释状态跨行延续；识别不了的语言（无标签、text 等）不着色。
 */

/** 注释变浅力度：代码文字色在此基础上再乘的透明度 */
internal const val CODE_COMMENT_DIM_ALPHA = 0.62f

/** 围栏语言标签 → 注释语法；null = 不识别 */
internal fun commentSyntaxFor(lang: String): CommentSyntax? = when (lang.trim().lowercase()) {
    "c", "c++", "cpp", "cxx", "cc", "java", "kotlin", "kt", "kts", "js", "ts", "javascript",
    "typescript", "dart", "rust", "go", "golang", "swift", "scala", "cs", "csharp",
    -> CommentSyntax(lineTokens = listOf("//"), blockStart = "/*", blockEnd = "*/")
    "sh", "bash", "shell", "zsh", "console", "py", "python", "ruby", "rb", "yaml", "yml",
    "toml", "conf", "ini", "dockerfile", "makefile", "cmake", "perl", "r",
    -> CommentSyntax(lineTokens = listOf("#"), lineTokenNeedsBoundary = true)
    "sql", "lua" -> CommentSyntax(lineTokens = listOf("--"), blockStart = "/*", blockEnd = "*/")
    else -> null
}

internal class CommentSyntax(
    val lineTokens: List<String>,
    val blockStart: String? = null,
    val blockEnd: String? = null,
    /** 行注释记号还须位于行首或空白之后：shell 语义，${var#x} 的 # 不是注释 */
    val lineTokenNeedsBoundary: Boolean = false,
)

/**
 * 逐行返回注释区间（行内 [start, endExclusive)，Pair.first/second）。
 * `#` 记号要求行首或空白后；引号内的记号不触发（反斜杠转义可跳过闭引号）；
 * [CommentSyntax.blockStart] 未在本行闭合时其余行整段记为注释。
 */
internal fun codeCommentRanges(lines: List<String>, lang: String): List<List<Pair<Int, Int>>> {
    val syntax = commentSyntaxFor(lang) ?: return List(lines.size) { emptyList() }
    var inBlock = false
    return lines.map { line ->
        val ranges = mutableListOf<Pair<Int, Int>>()
        var quote: Char? = null
        var i = 0
        if (inBlock && syntax.blockEnd != null) {
            val close = line.indexOf(syntax.blockEnd)
            if (close == -1) {
                if (line.isNotEmpty()) ranges += 0 to line.length
                return@map ranges
            }
            ranges += 0 to close + syntax.blockEnd.length
            i = close + syntax.blockEnd.length
            inBlock = false
        }
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                when {
                    c == '\\' -> i++
                    c == quote -> quote = null
                }
            } else {
                val token = syntax.lineTokens.firstOrNull {
                    line.startsWith(it, i) && (!syntax.lineTokenNeedsBoundary || i == 0 || line[i - 1].isWhitespace())
                }
                when {
                    token != null -> {
                        ranges += i to line.length
                        break
                    }
                    syntax.blockStart != null && line.startsWith(syntax.blockStart, i) -> {
                        val close = line.indexOf(syntax.blockEnd!!, i + syntax.blockStart.length)
                        if (close == -1) {
                            ranges += i to line.length
                            inBlock = true
                            break
                        }
                        ranges += i to close + syntax.blockEnd.length
                        i = close + syntax.blockEnd.length - 1 // 抵消循环尾 i++，落到 */ 之后
                    }
                    c == '"' || c == '\'' -> quote = c
                }
            }
            i++
        }
        ranges
    }
}

/** 注释区间着 [commentColor]，其余保持调用方传入的基础色 */
internal fun highlightCodeComments(
    lines: List<String>,
    lang: String,
    commentColor: Color,
): List<AnnotatedString> {
    val rangesPerLine = codeCommentRanges(lines, lang)
    return lines.mapIndexed { index, line ->
        val builder = AnnotatedString.Builder()
        builder.append(line)
        rangesPerLine[index].forEach { (start, endExclusive) ->
            if (endExclusive > start) builder.addStyle(SpanStyle(color = commentColor), start, endExclusive)
        }
        builder.toAnnotatedString()
    }
}
