package io.github.yuubiz.hirari.core.text

/**
 * The Java heap that opening a text takes at its peak (docs/spec.md section 3.11), in bytes. The figures follow what
 * [decodeText], [parseAozora] and [VerticalLayout] allocate, with every string at two bytes a char, and are estimates.
 */
object TextFootprint {
    /**
     * Decoding [bytes] bytes: the bytes, the char buffer [decodeText] sizes for one char a byte, the most any charset
     * it is given makes, and the string made from it.
     */
    fun decoding(bytes: Int): Long = bytes + 4L * bytes

    /**
     * Parsing [source] and laying it out: [source] while it is parsed, the document, the styles of its longest line,
     * which every paragraph layout running at once holds, a search's window, and the pages kept for the screen.
     */
    fun reading(source: String): Long {
        var lines = 0L
        var longest = 0
        for (line in sourceLines(source)) {
            lines++
            longest = maxOf(longest, line.last - line.first + 1)
        }
        val chars = source.length.toLong()
        return 2 * chars + DOCUMENT_BYTES_PER_CHAR * chars + DOCUMENT_BYTES_PER_LINE * lines +
            LINE_LAYOUT_BYTES_PER_CHAR * longest + SEARCH_WINDOW_BYTES + PAGE_WINDOW_BYTES
    }

    /** The drawn text of a paragraph and the source offset of each of its chars. */
    private const val DOCUMENT_BYTES_PER_CHAR = 6L

    /** A paragraph, its text and offset arrays, and its place in the block and line lists. */
    private const val DOCUMENT_BYTES_PER_LINE = 128L

    /** The per-char styles of up to three paragraph layouts, 20 bytes each. */
    private const val LINE_LAYOUT_BYTES_PER_CHAR = 60L

    /**
     * A search's folded text and the source range of each char of it, for a window of 65536 chars and a query. It does
     * not depend on the length of the line or on how far NFKC expands it.
     */
    private const val SEARCH_WINDOW_BYTES = 2L shl 20

    /** The pages laid out for the screen, which hold objects for every glyph. */
    private const val PAGE_WINDOW_BYTES = 8L shl 20
}
