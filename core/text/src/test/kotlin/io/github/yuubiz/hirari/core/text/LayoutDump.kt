package io.github.yuubiz.hirari.core.text

private fun num(f: Float): String {
    val rounded = Math.round(f * 100) / 100.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}

private fun glyphToken(glyph: Glyph, fontSize: Float): String {
    val flags = ArrayList<String>()
    val text = when (val content = glyph.content) {
        is GlyphContent.Chars -> {
            when (content.orientation) {
                Orientation.Upright -> Unit
                Orientation.Sideways -> flags += "side ${num(glyph.advance)}"
                Orientation.TateChuYoko -> flags += "tcy"
            }
            content.text
        }
        is GlyphContent.Dash -> "dash${content.ems}"
        is GlyphContent.Leader -> "leader${content.ems}"
        is GlyphContent.Image -> "img:${content.path}"
    }
    if (glyph.bold) flags += "bold"
    if (glyph.size != fontSize) flags += "x${num(glyph.size / fontSize)}"
    return text + "@" + num(glyph.y) + if (flags.isEmpty()) "" else flags.joinToString(",", "{", "}")
}

/** A text snapshot of [pages] for golden comparison: one block per page, one line per column. */
fun dumpPages(pages: List<TextPage>, fontSize: Float): String = buildString {
    for ((index, page) in pages.withIndex()) {
        append("== page ").append(index + 1).append(" [").append(page.start).append(", ").append(page.end).append(")")
        when (val content = page.content) {
            is PageContent.Picture -> append(" picture ").append(content.path).append('\n')
            is PageContent.Lines -> {
                append('\n')
                for (line in content.lines) {
                    append("line x=").append(num(line.x)).append(" w=").append(num(line.bodyWidth))
                    if (line.rightBand > 0) append(" right=").append(num(line.rightBand))
                    if (line.leftBand > 0) append(" left=").append(num(line.leftBand))
                    append(" [").append(line.start).append(", ").append(line.end).append(")\n")
                    if (line.glyphs.isNotEmpty()) append("  ").append(line.glyphs.joinToString(" ") { glyphToken(it, fontSize) }).append('\n')
                    for (ruby in line.rubies) {
                        append("  ruby ").append(ruby.text).append(" @").append(num(ruby.y)).append(" len=").append(num(ruby.length)).append('\n')
                    }
                    for (marks in line.emphasis) {
                        append("  marks ").append(marks.mark).append(' ').append(marks.side).append(" @")
                            .append(marks.centers.joinToString(" ") { num(it) }).append('\n')
                    }
                    for (sideLine in line.sideLines) {
                        append("  sideline ").append(sideLine.style).append(' ').append(sideLine.side).append(" @")
                            .append(num(sideLine.y0)).append("..").append(num(sideLine.y1)).append('\n')
                    }
                }
            }
        }
    }
}
