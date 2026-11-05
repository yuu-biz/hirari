package io.github.yuubiz.hirari.feature.library

/** Parts of a name without its extension; [creator] keeps the spelling used by the grid. */
internal data class LibraryName(
    val event: String?,
    val creator: String?,
    val circle: String? = null,
    val author: String? = null,
    val creatorRange: IntRange? = null,
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
internal fun parseLibraryName(name: String): LibraryName {
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
