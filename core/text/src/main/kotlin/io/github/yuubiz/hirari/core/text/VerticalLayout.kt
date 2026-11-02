package io.github.yuubiz.hirari.core.text

/** 行頭禁則: characters that must not start a line. 〵 is the lower half of a くの字点. */
private const val NO_LINE_START =
    "、。，．,.)）]］｝}〕〉》」』】〙〗〟’”｠»ヽヾゝゞ々〻ーァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖ" +
        "ㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ‐゠–〜～？！?!‼⁇⁈⁉・：；:;〵"

/** 行末禁則: characters that must not end a line. 〳 and 〴 are the upper halves of a くの字点. */
private const val NO_LINE_END = "(（[［｛{〔〈《「『【〘〖〝‘“｟«〳〴"

private val UPRIGHT_PAIRS = setOf("!!", "!?", "?!", "??")

private const val EPSILON = 0.01f

private fun isLatin(c: Char): Boolean = c in '!'..'~' || c == ' '

private fun isJoiner(cp: Int): Boolean =
    cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

/**
 * One unbreakable piece of a column: a character, a 縦中横 group, a sideways word, a rule, or an image. [from] and
 * [to] are display indices; [start] and [end] are source offsets.
 */
private class Atom(
    val content: GlyphContent,
    val from: Int,
    val to: Int,
    val start: Int,
    val end: Int,
    val size: Float,
    val bold: Boolean,
    val extent: Float,
    val gap: Float,
    val first: Char,
    val last: Char,
) {
    val pitch: Float get() = extent + gap
}

/**
 * What the line breaker places whole: an atom, or a ruby group of atoms. [lead] and [tail] are how far the ruby
 * reaches beyond the base over a neighbor; they cost space only at the start and end of a line, where the neighbor
 * is not there to lend it. [spread] is the space added among the base characters when the ruby still does not fit.
 */
private class Piece(
    val atoms: List<Atom>,
    val ruby: String? = null,
    val rubyLength: Float = 0f,
    val spread: Float = 0f,
    val lead: Float = 0f,
    val tail: Float = 0f,
) {
    val start: Int get() = atoms.first().start
    val end: Int get() = atoms.last().end
    val extent: Float = atoms.sumOf { it.pitch.toDouble() }.toFloat() - atoms.last().gap + spread
    val gap: Float get() = atoms.last().gap
    val pitch: Float get() = extent + gap
    val noStart: Boolean get() = atoms.first().first in NO_LINE_START
    val noEnd: Boolean get() = atoms.last().last in NO_LINE_END
}

/** Per display unit of a paragraph, what its spans make of it. */
private class ParagraphStyles(paragraph: Paragraph) {
    private val n = paragraph.text.length
    val scale = FloatArray(n) { if (paragraph.isTitle) HEADING_SCALE else 1f }
    val bold = BooleanArray(n)
    val groupEnd = IntArray(n) { -1 }
    val image = arrayOfNulls<String>(n)
    val rubyAt = arrayOfNulls<Span.Ruby>(n)
    val inRuby = BooleanArray(n)
    val rightMarks = BooleanArray(n)

    /** Whether a run of characters must end before this index. */
    val boundary = BooleanArray(n + 1)

    init {
        for (span in paragraph.spans) {
            boundary[span.start] = true
            boundary[span.end] = true
            when (span) {
                is Span.HeadingText -> for (i in span.start until span.end) {
                    scale[i] = HEADING_SCALE
                    bold[i] = true
                }
                is Span.Bold -> for (i in span.start until span.end) bold[i] = true
                is Span.TateChuYoko -> groupEnd[span.start] = span.end
                is Span.InlineImage -> image[span.start] = span.path
                is Span.Ruby -> {
                    rubyAt[span.start] = span
                    for (i in span.start until span.end) inRuby[i] = true
                }
                is Span.Emphasis -> if (span.side == MarkSide.Right) for (i in span.start until span.end) rightMarks[i] = true
                is Span.SideLine -> if (span.side == MarkSide.Right) for (i in span.start until span.end) rightMarks[i] = true
            }
        }
    }

    fun isFree(atom: Atom): Boolean = (atom.from until atom.to).none { inRuby[it] || rightMarks[it] }
}

/**
 * Vertical typesetting of docs/spec.md section 4.9. Pages come out lazily, so the caller decides how much to lay out
 * at a time and on which thread.
 */
class VerticalLayout(
    private val document: TextDocument,
    private val params: LayoutParams,
    private val metrics: GlyphMetrics,
) {
    private val fontSize = params.fontSize

    /** The index of the block whose source range holds [offset]. A re-layout for a new size starts there. */
    fun blockIndexAt(offset: Int): Int {
        val blocks = document.blocks
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    /**
     * The pages of blocks `from until to`. The first page starts at block [from], or at 0 when [from] is 0, and the
     * last ends where block [to] starts, or at the end of the source. An empty range makes one empty page.
     */
    fun pages(from: Int = 0, to: Int = document.blocks.size): Sequence<TextPage> = sequence {
        val blocks = document.blocks
        require(from in 0..to && to <= blocks.size) { "blocks $from until $to of ${blocks.size}" }
        val rangeStart = when {
            from == 0 -> 0
            from >= blocks.size -> document.sourceLength
            else -> blocks[from].start
        }
        val rangeEnd = if (to >= blocks.size) document.sourceLength else blocks[to].start
        var pageStart = rangeStart
        var lines = ArrayList<TextLine>()
        var centered = false
        var yielded = false
        // A finished page waits here until the next page's first item tells where it ends.
        var pending: PageContent? = null

        fun finish(): PageContent? {
            if (lines.isEmpty()) return null
            val content = PageContent.Lines(if (centered) centerLines(lines) else lines)
            lines = ArrayList()
            centered = false
            return content
        }

        suspend fun SequenceScope<TextPage>.begin(itemStart: Int) {
            val content = pending ?: return
            val end = maxOf(itemStart, pageStart)
            yield(TextPage(content, pageStart, end))
            yielded = true
            pageStart = end
            pending = null
        }

        for (index in from until minOf(to, blocks.size)) {
            when (val block = blocks[index]) {
                is PageBreak -> finish()?.let { pending = it }
                is CenterPage -> centered = true
                is Figure -> {
                    finish()?.let { pending = it }
                    begin(block.start)
                    pending = PageContent.Picture(block.path)
                }
                is Paragraph -> for (line in layoutParagraph(block)) {
                    val prev = lines.lastOrNull()
                    var right = params.width - line.rightBand
                    if (prev != null) {
                        val gap = maxOf(params.lineGap * fontSize, prev.leftBand + line.rightBand)
                        right = prev.x - prev.bodyWidth / 2 - gap
                        if (right - line.bodyWidth - line.leftBand < -EPSILON) {
                            pending = finish()
                            right = params.width - line.rightBand
                        }
                    }
                    begin(line.start)
                    lines += line.copy(x = right - line.bodyWidth / 2)
                }
            }
        }
        finish()?.let { pending = it }
        if (pending == null && !yielded) pending = PageContent.Lines(emptyList())
        pending?.let { yield(TextPage(it, pageStart, maxOf(rangeEnd, pageStart))) }
    }

    private fun centerLines(lines: List<TextLine>): List<TextLine> {
        val last = lines.last()
        val left = last.x - last.bodyWidth / 2 - last.leftBand
        return lines.map { it.copy(x = it.x - left / 2) }
    }

    /** The columns of [paragraph], with [TextLine.x] still unset. */
    private fun layoutParagraph(paragraph: Paragraph): List<TextLine> {
        if (paragraph.text.isEmpty()) {
            return listOf(TextLine(0f, fontSize, 0f, 0f, emptyList(), emptyList(), emptyList(), emptyList(), paragraph.start, paragraph.end))
        }
        val styles = ParagraphStyles(paragraph)
        val units = units(paragraph, styles)
        val bottom = paragraph.bottom
        val splitAt = if (bottom == null) units.size else units.indexOfFirst { it.atoms.first().from >= bottom.from }.let { if (it < 0) units.size else it }
        val head = breakLines(units, 0, splitAt) { if (it == 0) paragraph.indent else paragraph.wrapIndent }
        val bottomAt = params.height - (bottom?.raise ?: 0) * fontSize
        val tail = if (splitAt < units.size) breakLines(units, splitAt, units.size) { 0 } else emptyList()
        val lines = ArrayList<TextLine>()
        head.forEach { (range, indent) -> lines += placeLine(paragraph, styles, units, range, indent * fontSize) }
        val lastHead = head.lastOrNull()
        val onlyTail = tail.singleOrNull()?.first
        if (lastHead != null && onlyTail != null &&
            lineLength(units, lastHead.first) + lastHead.second * fontSize + fontSize + lineLength(units, onlyTail) <= bottomAt + EPSILON
        ) {
            lines[lines.lastIndex] = placeLine(
                paragraph, styles, units, lastHead.first.first..onlyTail.last, lastHead.second * fontSize, onlyTail.first, bottomAt,
            )
        } else {
            tail.forEach { (range, _) -> lines += placeLine(paragraph, styles, units, range, 0f, range.first, bottomAt) }
        }
        lines[0] = lines[0].copy(start = paragraph.start)
        lines[lines.lastIndex] = lines.last().copy(end = paragraph.end)
        return lines
    }

    /** The length of units [range] when they make a line by themselves. */
    private fun lineLength(units: List<Piece>, range: IntRange): Float {
        var length = units[range.first].lead
        for (i in range) length += units[i].pitch
        return length - units[range.last].gap + units[range.last].tail
    }

    /** Greedy line breaking with push-out kinsoku. Each line's unit range and indent in ems. */
    private fun breakLines(units: List<Piece>, from: Int, to: Int, indentOf: (Int) -> Int): List<Pair<IntRange, Int>> {
        val result = ArrayList<Pair<IntRange, Int>>()
        val maxIndent = maxOf(0, (params.height / fontSize).toInt() - 2)
        var i = from
        while (i < to) {
            val indent = indentOf(result.size).coerceIn(0, maxIndent)
            val limit = params.height - indent * fontSize
            var j = i + 1
            var length = units[i].lead + units[i].extent
            while (j < to) {
                val next = length + units[j - 1].gap + units[j].extent
                if (next + units[j].tail > limit + EPSILON) break
                length = next
                j++
            }
            if (j < to) {
                var b = j
                while (b > i + 1 && (units[b].noStart || units[b - 1].noEnd)) b--
                if (!units[b].noStart && !units[b - 1].noEnd) j = b
            }
            result += (i until j) to indent
            i = j
        }
        return result
    }

    private fun placeLine(
        paragraph: Paragraph,
        styles: ParagraphStyles,
        units: List<Piece>,
        range: IntRange,
        offset: Float,
        bottomFrom: Int = -1,
        bottomAt: Float = 0f,
    ): TextLine {
        val glyphs = ArrayList<Glyph>()
        val atoms = ArrayList<Atom>()
        val rubies = ArrayList<RubyRun>()
        var y = offset + units[range.first].lead
        for (i in range) {
            if (i == bottomFrom) y = bottomAt - lineLength(units, i..range.last) + units[i].lead
            val unit = units[i]
            val share = unit.spread / unit.atoms.size
            var atomY = y + share / 2
            for (atom in unit.atoms) {
                glyphs += Glyph(atom.content, atomY, atom.extent, atom.size, atom.bold, atom.start, atom.end)
                atoms += atom
                atomY += atom.pitch + share
            }
            unit.ruby?.let {
                val reach = unit.lead + unit.extent + unit.tail
                rubies += RubyRun(it, y - unit.lead + (reach - unit.rubyLength) / 2, unit.rubyLength, params.rubySize)
            }
            y += unit.pitch
        }
        val emphasis = ArrayList<EmphasisRun>()
        val sideLines = ArrayList<SideLineRun>()
        for (span in paragraph.spans) {
            val covered = atoms.indices.filter { atoms[it].from >= span.start && atoms[it].from < span.end }
            if (covered.isEmpty()) continue
            when (span) {
                is Span.Emphasis -> emphasis += EmphasisRun(
                    span.mark, span.side, covered.flatMap { markCenters(glyphs[it]) }, params.rubySize,
                )
                is Span.SideLine -> sideLines += SideLineRun(
                    span.style, span.side, glyphs[covered.first()].y, glyphs[covered.last()].let { it.y + it.advance },
                )
                else -> Unit
            }
        }
        val marks = emphasis.map { it.side } + sideLines.map { it.side }
        val rightBand = if (rubies.isNotEmpty() || MarkSide.Right in marks) params.rubySize else 0f
        val leftBand = if (MarkSide.Left in marks) params.rubySize else 0f
        return TextLine(
            0f, glyphs.maxOf { it.size }, rightBand, leftBand, glyphs, rubies, emphasis, sideLines,
            units[range.first].start, units[range.last].end,
        )
    }

    private fun markCenters(glyph: Glyph): List<Float> {
        val ems = when (val content = glyph.content) {
            is GlyphContent.Dash -> content.ems
            is GlyphContent.Leader -> content.ems
            else -> 1
        }
        return List(ems) { glyph.y + glyph.advance * (it + 0.5f) / ems }
    }

    private fun units(paragraph: Paragraph, styles: ParagraphStyles): List<Piece> {
        val atoms = atoms(paragraph, styles)
        val units = ArrayList<Piece>(atoms.size)
        var a = 0
        while (a < atoms.size) {
            val ruby = styles.rubyAt[atoms[a].from]
            if (ruby == null) {
                units += Piece(listOf(atoms[a]))
                a++
                continue
            }
            var b = a
            while (b < atoms.size && atoms[b].from < ruby.end) b++
            val leadFree = a > 0 && styles.isFree(atoms[a - 1])
            val tailFree = b < atoms.size && styles.isFree(atoms[b])
            units += rubyPieces(atoms.subList(a, b).toList(), ruby.text, leadFree, tailFree)
            a = b
        }
        return units
    }

    /**
     * One ruby group, or several when it is longer than a column: then the base splits into nearly equal runs and the
     * ruby splits in proportion, since a group that cannot fit a line cannot stay whole.
     */
    private fun rubyPieces(atoms: List<Atom>, ruby: String, leadFree: Boolean, tailFree: Boolean): List<Piece> {
        val whole = rubyPiece(atoms, ruby, leadFree, tailFree)
        val length = whole.lead + whole.extent + whole.tail
        if (length <= params.height + EPSILON || atoms.size == 1) return listOf(whole)
        val count = minOf(atoms.size, kotlin.math.ceil(length / params.height).toInt())
        val rubyPoints = ruby.codePoints().toArray()
        fun share(size: Int, k: Int): Int = (size.toLong() * k / count).toInt()
        return (0 until count).map { k ->
            val text = String(rubyPoints, share(rubyPoints.size, k), share(rubyPoints.size, k + 1) - share(rubyPoints.size, k))
            rubyPiece(atoms.subList(share(atoms.size, k), share(atoms.size, k + 1)), text, leadFree && k == 0, tailFree && k == count - 1)
        }
    }

    private fun rubyPiece(atoms: List<Atom>, ruby: String, leadFree: Boolean, tailFree: Boolean): Piece {
        val base = atoms.sumOf { it.pitch.toDouble() }.toFloat() - atoms.last().gap
        val length = ruby.codePointCount(0, ruby.length) * params.rubySize
        val excess = (length - base).coerceAtLeast(0f)
        val reach = RUBY_OVERHANG_EMS * fontSize
        val lead = if (leadFree) minOf(excess / 2, reach) else 0f
        val tail = if (tailFree) minOf(excess / 2, reach) else 0f
        return Piece(atoms, ruby, length, excess - lead - tail, lead, tail)
    }

    private fun atoms(paragraph: Paragraph, styles: ParagraphStyles): List<Atom> {
        val text = paragraph.text
        val result = ArrayList<Atom>(text.length)
        val add = { content: GlyphContent, from: Int, to: Int, extent: Float ->
            val size = styles.scale[from] * fontSize
            result += Atom(
                content, from, to, paragraph.sourceStart(from), paragraph.sourceStart(to), size, styles.bold[from], extent,
                params.letterGap * size, text[from], text[to - 1],
            )
        }
        var i = 0
        while (i < text.length) {
            val size = styles.scale[i] * fontSize
            val groupEnd = styles.groupEnd[i]
            val image = styles.image[i]
            val c = text[i]
            val pairs = i + 1 < text.length && text[i + 1] == c && !styles.boundary[i + 1]
            when {
                groupEnd > i -> {
                    add(GlyphContent.Chars(text.substring(i, groupEnd), Orientation.TateChuYoko), i, groupEnd, size)
                    i = groupEnd
                }
                image != null -> {
                    add(GlyphContent.Image(image), i, i + 1, size)
                    i++
                }
                (c == '―' || c == '—') && pairs -> {
                    add(GlyphContent.Dash(2), i, i + 2, 2 * size)
                    i += 2
                }
                c == '…' && pairs -> {
                    add(GlyphContent.Leader(2), i, i + 2, 2 * size)
                    i += 2
                }
                isLatin(c) -> {
                    var end = i + 1
                    while (end < text.length && isLatin(text[end]) && !styles.boundary[end]) end++
                    latinRun(text, i, end, size, styles.bold[i], add)
                    i = end
                }
                else -> {
                    var end = i + Character.charCount(text.codePointAt(i))
                    while (end < text.length && isJoiner(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
                    add(GlyphContent.Chars(text.substring(i, end), Orientation.Upright), i, end, size)
                    i = end
                }
            }
        }
        return result
    }

    private fun latinRun(text: String, from: Int, to: Int, size: Float, bold: Boolean, add: (GlyphContent, Int, Int, Float) -> Unit) {
        val sideways = { s: Int, e: Int ->
            val word = text.substring(s, e)
            val width = metrics.width(word, size, bold)
            if (width <= params.height || e - s == 1) {
                add(GlyphContent.Chars(word, Orientation.Sideways), s, e, width)
            } else {
                for (i in s until e) {
                    val c = text[i].toString()
                    add(GlyphContent.Chars(c, Orientation.Sideways), i, i + 1, metrics.width(c, size, bold))
                }
            }
        }
        when (params.latinMode) {
            LatinMode.ShortTateChuYoko ->
                if (to - from <= 2 && (from until to).none { text[it] == ' ' }) {
                    add(GlyphContent.Chars(text.substring(from, to), Orientation.TateChuYoko), from, to, size)
                } else {
                    words(text, from, to, sideways)
                }
            LatinMode.Sideways -> words(text, from, to, sideways)
            LatinMode.Upright -> {
                var i = from
                while (i < to) {
                    val pair = if (i + 1 < to) text.substring(i, i + 2) else ""
                    val twoDigits = pair.length == 2 && pair.all(Char::isDigit) &&
                        (i == from || !text[i - 1].isDigit()) && (i + 2 == to || !text[i + 2].isDigit())
                    if (twoDigits || pair in UPRIGHT_PAIRS) {
                        add(GlyphContent.Chars(pair, Orientation.TateChuYoko), i, i + 2, size)
                        i += 2
                    } else {
                        add(GlyphContent.Chars(text[i].toString(), Orientation.Upright), i, i + 1, size)
                        i++
                    }
                }
            }
        }
    }

    /** Splits a sideways run into words that keep their trailing spaces, so a line may break between words. */
    private fun words(text: String, from: Int, to: Int, emit: (Int, Int) -> Unit) {
        var s = from
        while (s < to) {
            var e = s
            while (e < to && text[e] != ' ') e++
            while (e < to && text[e] == ' ') e++
            emit(s, e)
            s = e
        }
    }
}
