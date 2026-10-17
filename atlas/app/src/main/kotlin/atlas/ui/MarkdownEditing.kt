package atlas.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** 编辑器快捷键 → Markdown 成对标记；Ctrl+B 加粗、Ctrl+I 斜体、Ctrl+` 行内代码 */
internal fun markdownShortcutWrap(key: Key): Pair<String, String>? = when (key) {
    Key.B -> "**" to "**"
    Key.I -> "*" to "*"
    Key.Grave -> "`" to "`"
    else -> null
}

/**
 * 包裹/取消包裹当前选区：选区首尾正好是成对标记时摘掉（toggle），否则加上；
 * 无选中时插入一对空标记并把光标放在中间。
 */
internal fun TextFieldValue.toggleMarkdownWrap(prefix: String, suffix: String = prefix): TextFieldValue {
    val start = selection.min
    val end = selection.max
    if (start == end) {
        val text = text.substring(0, start) + prefix + suffix + text.substring(end)
        return TextFieldValue(text, TextRange(start + prefix.length))
    }
    val selected = text.substring(start, end)
    if (selected.length >= prefix.length + suffix.length && selected.startsWith(prefix) && selected.endsWith(suffix)) {
        val inner = selected.substring(prefix.length, selected.length - suffix.length)
        val text = text.substring(0, start) + inner + text.substring(end)
        return TextFieldValue(text, TextRange(start, start + inner.length))
    }
    val text = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
    return TextFieldValue(text, TextRange(start + prefix.length, end + prefix.length))
}

/** Replace the current selection with a standalone Markdown image block and keep the caret after it. */
internal fun TextFieldValue.insertMarkdownImage(markdown: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val before = text.substring(0, start)
    val after = text.substring(end)
    val prefix = when {
        before.isEmpty() || before.endsWith("\n\n") -> ""
        before.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    val suffix = when {
        after.isEmpty() || after.startsWith("\n\n") -> ""
        after.startsWith('\n') -> "\n"
        else -> "\n\n"
    }
    val inserted = "$prefix$markdown$suffix"
    val updated = before + inserted + after
    val caret = before.length + inserted.length
    return TextFieldValue(updated, TextRange(caret))
}

internal fun TextFieldValue.insertInlineMarkdownImage(markdown: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val updated = text.substring(0, start) + markdown + text.substring(end)
    val caret = start + markdown.length
    return TextFieldValue(updated, TextRange(caret))
}
