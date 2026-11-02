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
private class ParagraphStyles(val paragraph: Paragraph) {
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
 * The spans of a paragraph that touch a stretch of it, for stretches that move forward. Each span is looked at when the
 * stretch reaches it and when it leaves, so laying out every line costs the spans once and not once per line.
 */
private class SpanSweep(private val spans: List<Span>) {
    private val byStart = spans.indices.sortedBy { spans[it].start }
    private var next = 0
    private val active = ArrayList<Int>()

    /** The spans over display indices `from until to`, in their order in the paragraph. */
    fun overlapping(from: Int, to: Int): List<Span> {
        while (next < byStart.size && spans[byStart[next]].start < to) active += byStart[next++]
        active.removeAll { spans[it].end <= from }
        active.sort()
        return active.map(spans::get)
    }
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

    /**
     * The index of the first block of the source line that holds [offset]. A re-layout for a new size starts there, so
     * the notes that split a line into several blocks stay with their line.
     */
    fun blockIndexAt(offset: Int): Int {
        val blocks = document.blocks
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].start <= offset) lo = mid else hi = mid - 1
        }
        val firsts = document.lineStartBlocks
        var low = 0
        var high = firsts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (firsts[mid] <= lo) low = mid else high = mid - 1
        }
        return if (firsts.isEmpty()) 0 else firsts[low]
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

    /**
     * The columns of [paragraph], with [TextLine.x] still unset. They come one at a time, so the first page of a very
     * long line costs the page and not the line.
     */
    private fun layoutParagraph(paragraph: Paragraph): Sequence<TextLine> {
        if (paragraph.text.isEmpty()) {
            return sequenceOf(TextLine(0f, fontSize, 0f, 0f, emptyList(), emptyList(), emptyList(), emptyList(), paragraph.start, paragraph.end))
        }
        return sequence {
            val styles = ParagraphStyles(paragraph)
            val units = UnitCursor(styles)
            val sweep = SpanSweep(paragraph.spans)
            val bottom = paragraph.bottom
            val bottomAt = params.height - (bottom?.raise ?: 0) * fontSize
            val head = breakLines(units, { bottom != null && it.atoms.first().from >= bottom.from }) {
                if (it == 0) paragraph.indent else paragraph.wrapIndent
            }
            var lastHead: BrokenLine? = null
            for (line in head) {
                lastHead?.let { yield(placeLine(paragraph, sweep, it.pieces, it.indent * fontSize)) }
                lastHead = line
            }
            val tail = breakLines(units, { false }) { 0 }.iterator()
            val firstTail = if (tail.hasNext()) tail.next() else null
            val onlyTail = firstTail?.takeIf { !tail.hasNext() }
            if (lastHead != null && onlyTail != null &&
                lineLength(lastHead.pieces) + lastHead.indent * fontSize + fontSize + lineLength(onlyTail.pieces) <= bottomAt + EPSILON
            ) {
                yield(placeLine(paragraph, sweep, lastHead.pieces + onlyTail.pieces, lastHead.indent * fontSize, lastHead.pieces.size, bottomAt))
                return@sequence
            }
            lastHead?.let { yield(placeLine(paragraph, sweep, it.pieces, it.indent * fontSize)) }
            if (firstTail != null) {
                var held: BrokenLine = firstTail
                for (line in tail) {
                    yield(placeLine(paragraph, sweep, held.pieces, 0f))
                    held = line
                }
                yield(placeLine(paragraph, sweep, held.pieces, 0f, 0, bottomAt))
            }
        }.rebased(paragraph.start, paragraph.end)
    }

    /** [start] and [end] stand for the first and last column, which also hold the notes around the line. */
    private fun Sequence<TextLine>.rebased(start: Int, end: Int): Sequence<TextLine> = sequence {
        var held: TextLine? = null
        for (line in this@rebased) {
            held?.let { yield(it) }
            held = if (held == null) line.copy(start = start) else line
        }
        held?.let { yield(it.copy(end = end)) }
    }

    /** The length of [pieces] when they make a line by themselves. */
    private fun lineLength(pieces: List<Piece>): Float {
        var length = pieces.first().lead
        for (piece in pieces) length += piece.pitch
        return length - pieces.last().gap + pieces.last().tail
    }

    private class BrokenLine(val pieces: List<Piece>, val indent: Int)

    /**
     * Greedy line breaking with push-out kinsoku, one line at a time, over the pieces of [units] that [isTail] does not
     * reject. [indentOf] gives a line's indent in ems from its number.
     */
    private fun breakLines(units: UnitCursor, isTail: (Piece) -> Boolean, indentOf: (Int) -> Int): Sequence<BrokenLine> = sequence {
        val maxIndent = maxOf(0, (params.height / fontSize).toInt() - 2)
        fun pull(): Piece? {
            val next = units.peek() ?: return null
            return if (isTail(next)) null else units.take()
        }
        var buffer = ArrayList<Piece>()
        var number = 0
        while (true) {
            if (buffer.isEmpty()) buffer.add(pull() ?: break)
            val indent = indentOf(number).coerceIn(0, maxIndent)
            val limit = params.height - indent * fontSize
            var j = 1
            var length = buffer[0].lead + buffer[0].extent
            var overflow = false
            while (true) {
                if (j == buffer.size) buffer.add(pull() ?: break)
                val next = length + buffer[j - 1].gap + buffer[j].extent
                if (next + buffer[j].tail > limit + EPSILON) {
                    overflow = true
                    break
                }
                length = next
                j++
            }
            if (overflow) {
                var b = j
                while (b > 1 && (buffer[b].noStart || buffer[b - 1].noEnd)) b--
                if (!buffer[b].noStart && !buffer[b - 1].noEnd) j = b
            }
            yield(BrokenLine(ArrayList(buffer.subList(0, j)), indent))
            buffer = ArrayList(buffer.subList(j, buffer.size))
            number++
        }
    }

    private fun placeLine(
        paragraph: Paragraph,
        sweep: SpanSweep,
        pieces: List<Piece>,
        offset: Float,
        bottomFrom: Int = -1,
        bottomAt: Float = 0f,
    ): TextLine {
        val glyphs = ArrayList<Glyph>()
        val atoms = ArrayList<Atom>()
        val rubies = ArrayList<RubyRun>()
        var y = offset + pieces.first().lead
        for ((i, unit) in pieces.withIndex()) {
            if (i == bottomFrom) y = bottomAt - lineLength(pieces.subList(i, pieces.size)) + unit.lead
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
        for (span in sweep.overlapping(atoms.first().from, atoms.last().from + 1)) {
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
            pieces.first().start, pieces.last().end,
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

    /**
     * The pieces of a paragraph, made as they are asked for. A ruby group needs the atom before it and the atom after
     * it, which is all the lookahead there is.
     */
    private inner class UnitCursor(private val styles: ParagraphStyles) {
        private val atoms = AtomCursor(styles)
        private val queue = ArrayDeque<Piece>()
        private var previous: Atom? = null

        fun peek(): Piece? {
            while (queue.isEmpty()) if (!fill()) return null
            return queue.first()
        }

        fun take(): Piece? = peek()?.also { queue.removeFirst() }

        private fun fill(): Boolean {
            val first = atoms.take() ?: return false
            val ruby = styles.rubyAt[first.from]
            if (ruby == null) {
                queue.addLast(Piece(listOf(first)))
                previous = first
                return true
            }
            val group = arrayListOf(first)
            while (true) {
                val next = atoms.peek() ?: break
                if (next.from >= ruby.end) break
                group += next
                atoms.take()
            }
            val leadFree = previous?.let(styles::isFree) == true
            val tailFree = atoms.peek()?.let(styles::isFree) == true
            rubyPieces(group, ruby.text, leadFree, tailFree).forEach(queue::addLast)
            previous = group.last()
            return true
        }
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

    /**
     * The atoms of a paragraph, made as they are asked for. One step makes one atom, or the few atoms of a character
     * with its joiners; a Latin run is walked a word at a time and a word too long for a column a character at a time, so
     * a long run costs only as far as the lines reach.
     */
    private inner class AtomCursor(private val styles: ParagraphStyles) {
        private val text = styles.paragraph.text
        private val queue = ArrayDeque<Atom>()
        private var i = 0

        /** Whether the sideways run being walked goes on at [i]. A run's treatment was chosen at its start. */
        private var inRun = false

        /** The characters before this index are atoms of a word that is too long for a column, made one at a time. */
        private var splitEnd = 0

        fun peek(): Atom? {
            while (queue.isEmpty() && i < text.length) step()
            return queue.firstOrNull()
        }

        fun take(): Atom? = peek()?.also { queue.removeFirst() }

        private fun add(content: GlyphContent, from: Int, to: Int, extent: Float) {
            val size = styles.scale[from] * fontSize
            queue.addLast(
                Atom(
                    content, from, to, styles.paragraph.sourceStart(from), styles.paragraph.sourceStart(to), size, styles.bold[from], extent,
                    params.letterGap * size, text[from], text[to - 1],
                ),
            )
        }

        private fun step() {
            if (i < splitEnd) {
                val c = text[i].toString()
                add(GlyphContent.Chars(c, Orientation.Sideways), i, i + 1, metrics.width(c, styles.scale[i] * fontSize, styles.bold[i]))
                i++
                return
            }
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
                isLatin(c) -> latinStep(size)
                else -> {
                    var end = i + Character.charCount(text.codePointAt(i))
                    while (end < text.length && isJoiner(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
                    add(GlyphContent.Chars(text.substring(i, end), Orientation.Upright), i, end, size)
                    i = end
                }
            }
        }

        /** Whether a Latin run reaches index [j]. A run is Latin characters that no span edge cuts between. */
        private fun runReaches(j: Int): Boolean = j < text.length && isLatin(text[j]) && !styles.boundary[j]

        /** One atom of the Latin run that holds [i]. */
        private fun latinStep(size: Float) {
            when (params.latinMode) {
                LatinMode.Upright -> {
                    val pair = if (runReaches(i + 1)) text.substring(i, i + 2) else ""
                    val runStart = i == 0 || !isLatin(text[i - 1]) || styles.boundary[i]
                    val twoDigits = pair.length == 2 && pair.all(Char::isDigit) &&
                        (runStart || !text[i - 1].isDigit()) && (!runReaches(i + 2) || !text[i + 2].isDigit())
                    if (twoDigits || pair in UPRIGHT_PAIRS) {
                        add(GlyphContent.Chars(pair, Orientation.TateChuYoko), i, i + 2, size)
                        i += 2
                    } else {
                        add(GlyphContent.Chars(text[i].toString(), Orientation.Upright), i, i + 1, size)
                        i++
                    }
                }
                LatinMode.ShortTateChuYoko, LatinMode.Sideways -> {
                    if (params.latinMode == LatinMode.ShortTateChuYoko && !inRun) {
                        var end = i + 1
                        while (end < i + 3 && runReaches(end)) end++
                        if (end - i <= 2 && !runReaches(end) && (i until end).none { text[it] == ' ' }) {
                            add(GlyphContent.Chars(text.substring(i, end), Orientation.TateChuYoko), i, end, size)
                            i = end
                            return
                        }
                    }
                    word(size)
                }
            }
        }

        /** A word with the spaces after it, so a line may break between words. */
        private fun word(size: Float) {
            var end = i
            while (end < text.length && (end == i || runReaches(end)) && text[end] != ' ') end++
            while (end < text.length && (end == i || runReaches(end)) && text[end] == ' ') end++
            val width = metrics.width(text.substring(i, end), size, styles.bold[i])
            inRun = runReaches(end)
            if (width <= params.height || end - i == 1) {
                add(GlyphContent.Chars(text.substring(i, end), Orientation.Sideways), i, end, width)
                i = end
            } else {
                splitEnd = end
            }
        }
    }
}
