package atlas.core

import java.io.File

/** Markdown image references are relative to the document that contains them. */
object MarkdownImages {
    private val imageLink = Regex("(!\\[[^]]*])\\(([^)]+)\\)")

    /** Rewrite relative image targets when moving a Markdown block to another document. */
    fun rebaseRelativeLinks(markdown: String, sourceDocument: File, targetDocument: File): String =
        imageLink.replace(markdown) { match ->
            val target = match.groupValues[2].trim()
            if (target.isBlank() || target.startsWith("<") || target.startsWith("#") ||
                target.startsWith("http://", true) || target.startsWith("https://", true) ||
                target.startsWith("data:", true) || target.startsWith("/") ||
                Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(target)
            ) {
                match.value
            } else {
                val path = target.substringBefore('#').substringBefore('?')
                val suffix = target.removePrefix(path)
                val decoded = decodePath(path)
                val sourceParent = sourceDocument.absoluteFile.parentFile ?: sourceDocument.absoluteFile
                val targetParent = targetDocument.absoluteFile.parentFile ?: targetDocument.absoluteFile
                val resolved = File(sourceParent, decoded).canonicalFile
                val rebased = targetParent.canonicalFile.toPath()
                    .relativize(resolved.toPath()).toString().replace(File.separatorChar, '/')
                "${match.groupValues[1]}(${encodePath(rebased)}$suffix)"
            }
        }

    private fun decodePath(path: String): String = path.split('/').joinToString(File.separator) {
        java.net.URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8)
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
    }
}
