package io.github.yuubiz.hirari.feature.library

/** Parts of a name without its extension; [creator] keeps the spelling used by the grid. */
internal data class LibraryName(
    val event: String?,
    val creator: String?,
    val circle: String? = null,
    val author: String? = null,
    val creatorRange: IntRange? = null,
    val title: String = "",
    val original: String? = null,
) {
    val names: List<String> get() = if (circle != null && author != null) listOf(circle, author) else listOfNotNull(creator)
}

/** Keep the existing first complete bracket pair rule, including malformed prefixes. */
private fun firstBrackets(name: String): IntRange? {
    for ((open, char) in name.withIndex()) {
        val closing = when (char) {
            '[' -> ']'
            '［' -> '］'
            else -> continue
        }
        val close = name.indexOf(closing, open + 1)
        if (close >= 0) return open..close
    }
    return null
}

/** The balanced outer parentheses, or null when the parentheses cannot be read. No recursion for long names. */
private fun parentheses(text: String, firstOnly: Boolean = false): List<IntRange>? {
    val stack = ArrayList<Char>()
    val pairs = ArrayList<IntRange>()
    var start = 0
    for ((index, char) in text.withIndex()) {
        when (char) {
            '(', '（' -> {
                if (stack.isEmpty()) start = index
                stack += char
            }
            ')', '）' -> {
                val expected = if (char == ')') '(' else '（'
                if (stack.lastOrNull() != expected) return null
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) {
                    pairs += start..index
                    if (firstOnly) return pairs
                }
            }
        }
    }
    return pairs.takeIf { stack.isEmpty() }
}

/** Naming rules of docs/spec.md 4.3. Tags and parentheses in the title never supply circle or author names. */
private fun parseCreatorName(name: String): LibraryName {
    val range = firstBrackets(name)
    val prefix = name.substring(0, range?.first ?: name.length).trim()
    val event = parentheses(prefix, firstOnly = true)?.firstOrNull()?.takeIf { it.first == 0 }
        ?.let { prefix.substring(it.first + 1, it.last).trim() }
    val creator = range?.let { name.substring(it.first + 1, it.last).trim() }
        ?: return LibraryName(event, null)
    val last = parentheses(creator)?.lastOrNull()
    if (last != null) {
        val circle = creator.substring(0, last.first).trim()
        val author = creator.substring(last.first + 1, last.last).trim()
        if (circle.isNotEmpty() && author.isNotEmpty()) return LibraryName(event, creator, circle, author, range)
    }
    return LibraryName(event, creator, creatorRange = range)
}

private const val NUMERALS = "0-9０-９〇零一二三四五六七八九十百千万億兆壱弐参拾廿卅卌"
private val numberOnly = Regex("[$NUMERALS]+")
private val volumeSuffix = Regex(
    "(?:第\\s*[$NUMERALS]+\\s*[巻話]|[$NUMERALS]+\\s*巻|前編|後編|[上中下]|(?:vol\\.?|v)\\s*[$NUMERALS]+|#\\s*[$NUMERALS]+|\\([$NUMERALS]+\\)|[$NUMERALS]+)\\s*$",
    RegexOption.IGNORE_CASE,
)

/** Creator extraction remains compatible; title metadata only uses the prefix and trailing parts. */
internal fun parseLibraryName(name: String): LibraryName {
    val parsed = parseCreatorName(name)
    var title = name.trim()
    if (parsed.event != null) {
        val event = parentheses(title, firstOnly = true)?.firstOrNull()
        if (event?.first == 0) title = title.substring(event.last + 1).trim()
    }
    if (title.startsWith('[') || title.startsWith('［')) {
        firstBrackets(title)?.takeIf { it.first == 0 }?.let { title = title.substring(it.last + 1).trim() }
    }
    // Multiple trailing tags are common; do not mistake them for creator metadata.
    while (title.endsWith(']') || title.endsWith('］')) {
        val open = if (title.last() == ']') '[' else '［'
        val at = title.lastIndexOf(open)
        if (at < 0) break
        title = title.substring(0, at).trimEnd()
    }
    var original: String? = null
    parentheses(title)?.lastOrNull()?.takeIf { it.first > 0 && it.last == title.lastIndex }?.let { part ->
        val inside = title.substring(part.first + 1, part.last).trim()
        if (!numberOnly.matches(inside)) {
            original = inside.takeIf { it.isNotEmpty() }
            title = title.substring(0, part.first).trimEnd()
        }
    }
    return parsed.copy(title = title, original = original)
}

/** Match normalized suffixes but retain the first book's original spelling for the folder name. */
internal fun seriesTitle(title: String): String {
    val normalized = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC)
    val match = volumeSuffix.find(normalized) ?: return title.trim()
    val boundary = (0..title.length).lastOrNull {
        java.text.Normalizer.normalize(title.substring(0, it), java.text.Normalizer.Form.NFKC).length <= match.range.first
    } ?: 0
    return title.substring(0, boundary).trim().ifEmpty { title.trim() }
}
