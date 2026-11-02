package io.github.yuubiz.hirari.core.text

/**
 * The table of contents of docs/spec.md section 4.9: the heading notations, or for a book without them, the lines
 * that look like headings. Guessed headings are [HeadingLevel.Medium].
 */
fun tableOfContents(document: TextDocument): List<Heading> {
    if (document.headings.isNotEmpty()) return document.headings
    val lines = document.blocks.filterIsInstance<Paragraph>()
    return lines.indices.drop(1).filter { i ->
        val text = lines[i].text
        val previous = lines[i - 1].text
        val beforePrevious = lines.getOrNull(i - 2)?.text.orEmpty()
        text.isNotBlank() && text.length < 40 && previous.isEmpty() && !beforePrevious.endsWith('。') && !beforePrevious.endsWith('）')
    }.map { Heading(HeadingLevel.Medium, lines[it].text.trim(), lines[it].start) }
}

/**
 * Where a 挿絵 lives: [picture] taken relative to the directory of [textPath], with `.` and `..` resolved. Both use
 * `/`. Null when the path climbs above the root of the location or archive.
 */
fun resolvePicturePath(textPath: String, picture: String): String? {
    val parts = ArrayList(textPath.split('/').dropLast(1).filter { it.isNotEmpty() })
    for (part in picture.replace('\\', '/').split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}
