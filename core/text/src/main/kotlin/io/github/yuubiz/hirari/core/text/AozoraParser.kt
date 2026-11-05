package io.github.yuubiz.hirari.core.text

/** The styles a notation applies to a stretch of text, by the name 青空文庫 notation gives them. */
private sealed interface Style {
    data class Emphasis(val mark: EmphasisMark, val side: MarkSide) : Style
    data class Line(val style: LineStyle, val side: MarkSide) : Style
    data object Bold : Style
    data object TateChuYoko : Style
    data class Heading(val level: HeadingLevel) : Style
}

private val emphasisNames = mapOf(
    "傍点" to EmphasisMark.Sesame,
    "白ゴマ傍点" to EmphasisMark.WhiteSesame,
    "丸傍点" to EmphasisMark.Circle,
    "白丸傍点" to EmphasisMark.WhiteCircle,
    "黒三角傍点" to EmphasisMark.Triangle,
    "白三角傍点" to EmphasisMark.WhiteTriangle,
    "二重丸傍点" to EmphasisMark.Bullseye,
    "蛇の目傍点" to EmphasisMark.Fisheye,
)

private val lineNames = mapOf(
    "傍線" to LineStyle.Solid,
    "二重傍線" to LineStyle.Double,
    "鎖線" to LineStyle.Chain,
    "破線" to LineStyle.Dashed,
    "波線" to LineStyle.Wavy,
)

private val headingNames = mapOf("大見出し" to HeadingLevel.Large, "中見出し" to HeadingLevel.Medium, "小見出し" to HeadingLevel.Small)

/** A style word such as `白丸傍点` or `中見出し`, with `左に` already split off into [side]. */
private fun styleOf(word: String, side: MarkSide): Style? {
    emphasisNames[word]?.let { return Style.Emphasis(it, side) }
    lineNames[word]?.let { return Style.Line(it, side) }
    if (side == MarkSide.Left) return null
    return when (word) {
        "太字" -> Style.Bold
        "縦中横" -> Style.TateChuYoko
        else -> headingNames[word.removePrefix("同行").removePrefix("窓")]?.let { Style.Heading(it) }
    }
}

/** The meaning of one `［＃…］` note. */
private sealed interface Command {
    data object PageBreak : Command
    data object CenterPage : Command
    data class Indent(val n: Int) : Command
    data class IndentBlock(val first: Int, val wrap: Int) : Command
    data object IndentBlockEnd : Command
    data class Bottom(val raise: Int) : Command
    data class BottomBlock(val raise: Int) : Command
    data object BottomBlockEnd : Command
    data class Reference(val target: String, val style: Style) : Command
    data class RangeStart(val style: Style) : Command
    data class RangeEnd(val style: Style) : Command
    data class Picture(val path: String) : Command
    data object Ignored : Command
}

private const val NUMBER = """([0-9]+|[一二三四五六七八九十]+)"""
private val indent = Regex("""(?:天から)?${NUMBER}字下げ""")
private val indentBlock = Regex("""ここから(?:天から)?${NUMBER}字下げ(?:、折り返して${NUMBER}字下げ)?""")
private val indentBlockFlush = Regex("""ここから改行天付き、折り返して${NUMBER}字下げ""")
private val raise = Regex("""地から${NUMBER}字上げ""")
private val raiseBlock = Regex("""ここから地から${NUMBER}字上げ""")
private val reference = Regex("""「(.+)」(は|に|の左に)(.+)""")
private val picture = Regex("""（([^（）]+)）入る$""")

private val kanjiNumber = Regex("""([一二三四五六七八九])?十([一二三四五六七八九])?|[一二三四五六七八九]""")

/**
 * The value of a notation number: digits, saturating at [Int.MAX_VALUE] since every use clamps it to the page, or a
 * kanji number up to 99. Null for a kanji run that is no such number, such as `一二`.
 */
private fun number(s: String): Int? {
    if (s[0] in '0'..'9') return s.toIntOrNull() ?: Int.MAX_VALUE
    val match = kanjiNumber.matchEntire(s) ?: return null
    val digits = "一二三四五六七八九"
    if ('十' !in s) return digits.indexOf(s) + 1
    val tens = match.groupValues[1].takeIf(String::isNotEmpty)?.let { digits.indexOf(it) + 1 } ?: 1
    val ones = match.groupValues[2].takeIf(String::isNotEmpty)?.let { digits.indexOf(it) + 1 } ?: 0
    return tens * 10 + ones
}

private fun parseCommand(rawNote: String): Command {
    val note = rawNote
    val counts = normalizeDigits(rawNote)
    when (note) {
        "改ページ", "改丁", "改見開き" -> return Command.PageBreak
        "ページの左右中央" -> return Command.CenterPage
        "ここで字下げ終わり" -> return Command.IndentBlockEnd
        "地付き" -> return Command.Bottom(0)
        "ここから地付き" -> return Command.BottomBlock(0)
        "ここで地付き終わり", "ここで字上げ終わり" -> return Command.BottomBlockEnd
    }
    indent.matchEntire(counts)?.let { return number(it.groupValues[1])?.let(Command::Indent) ?: Command.Ignored }
    indentBlock.matchEntire(counts)?.let {
        val first = number(it.groupValues[1])
        val wrap = if (it.groupValues[2].isEmpty()) first else number(it.groupValues[2])
        return if (first == null || wrap == null) Command.Ignored else Command.IndentBlock(first, wrap)
    }
    indentBlockFlush.matchEntire(counts)?.let {
        return number(it.groupValues[1])?.let { wrap -> Command.IndentBlock(0, wrap) } ?: Command.Ignored
    }
    raise.matchEntire(counts)?.let { return number(it.groupValues[1])?.let(Command::Bottom) ?: Command.Ignored }
    raiseBlock.matchEntire(counts)?.let { return number(it.groupValues[1])?.let(Command::BottomBlock) ?: Command.Ignored }
    reference.matchEntire(rawNote)?.let { match ->
        val (target, particle, word) = match.destructured
        val style = styleOf(word, if (particle == "の左に") MarkSide.Left else MarkSide.Right)
        return if (style == null) Command.Ignored else Command.Reference(target, style)
    }
    picture.find(rawNote)?.let { return Command.Picture(it.groupValues[1].substringBefore('、')) }
    val body = note.removePrefix("ここから").removePrefix("ここで")
    val isEnd = body.endsWith("終わり")
    val word = body.removeSuffix("終わり")
    val side = if (word.startsWith("左に")) MarkSide.Left else MarkSide.Right
    val style = styleOf(word.removePrefix("左に"), side) ?: return Command.Ignored
    return if (isEnd) Command.RangeEnd(style) else Command.RangeStart(style)
}

/** The kinds of character whose runs form a ruby base when no `｜` marks its start. */
private enum class BaseKind { Kanji, Hiragana, Katakana, FullwidthLatin, Latin }

private fun baseKind(c: Char): BaseKind? = when {
    c in "々〆〇ヵヶ〻" -> BaseKind.Kanji
    Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> BaseKind.Kanji
    Character.isSurrogate(c) -> BaseKind.Kanji
    c in 'ぁ'..'ゟ' -> BaseKind.Hiragana
    c in 'ァ'..'ヿ' -> BaseKind.Katakana
    c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ' || c in '０'..'９' -> BaseKind.FullwidthLatin
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> BaseKind.Latin
    else -> null
}

private class OpenRange(val style: Style, var start: Int)

private class LineBuilder(val start: Int) {
    val text = StringBuilder()
    private var offsets = IntArray(16)
    val spans = ArrayList<Span>()
    var indent: Int? = null
    var bottom: BottomAlign? = null
    var hasNote = false

    fun append(s: String, sourceStart: Int) {
        for (c in s) {
            if (text.length == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
            offsets[text.length] = if (text.isEmpty()) start else sourceStart
            text.append(c)
        }
    }

    fun offset(index: Int): Int = offsets[index]

    fun offsets(): IntArray = offsets.copyOf(text.length)
}

/** docs/spec.md section 4.9: a ruled block of notation help in lines 2 to 500, rule lines included. */
private fun helpBlock(lines: List<IntRange>, source: String): IntRange? {
    fun isRule(i: Int): Boolean {
        val line = lines[i]
        return line.last - line.first + 1 >= 40 && line.all { source[it] == '-' }
    }
    val window = 1 until minOf(lines.size, 500)
    val first = window.firstOrNull(::isRule) ?: return null
    val second = (first + 1 until window.last + 1).firstOrNull(::isRule) ?: return null
    return first..second
}

/** The lines of [source] without their line breaks, as ranges of it, one at a time. */
internal fun sourceLines(source: String): Sequence<IntRange> = sequence {
    var lineStart = 0
    while (lineStart <= source.length) {
        val newline = source.indexOf('\n', lineStart)
        val end = if (newline < 0) source.length else newline
        if (newline >= 0 || end > lineStart) yield(lineStart until end)
        lineStart = end + 1
    }
}

/** Parses [source], the output of [decodeText], by the notation subset of docs/spec.md section 4.9. */
fun parseAozora(source: String, gaiji: JisX0213Table = JisX0213Table.None): TextDocument =
    AozoraParser(source, gaiji).parse()

private class AozoraParser(private val source: String, private val gaiji: JisX0213Table) {
    private val blocks = ArrayList<Block>()
    private val headings = ArrayList<Heading>()
    private val lineStartBlocks = ArrayList<Int>()
    private val open = ArrayList<OpenRange>()
    private var indentBlock: Command.IndentBlock? = null
    private var bottomBlock: Int? = null
    private var isFirstLine = true

    fun parse(): TextDocument {
        val hidden = helpBlock(sourceLines(source).take(500).toList(), source)
        for ((i, line) in sourceLines(source).withIndex()) {
            if (hidden != null && i in hidden) continue
            val first = blocks.size
            parseLine(line.first, line.last + 1)
            if (blocks.size > first) lineStartBlocks += first
            isFirstLine = false
        }
        return TextDocument(source.length, blocks, headings, lineStartBlocks.toIntArray())
    }

    private fun parseLine(start: Int, end: Int) {
        val paragraphEnd = minOf(end + 1, source.length)
        var line = LineBuilder(start)
        var rubyStart: Int? = null
        var barrier = 0
        var p = start
        while (p < end) {
            val c = source[p]
            when {
                c == '※' && source.startsWith("［＃", p + 1) -> {
                    val close = noteEnd(p + 1, end)
                    if (close < 0) {
                        line.append("※", p)
                        p++
                    } else {
                        line.append(gaijiText(source.substring(p + 3, close), gaiji) ?: "※", p)
                        p = close + 1
                    }
                }
                c == '［' && source.startsWith("［＃", p) -> {
                    val close = noteEnd(p, end)
                    if (close < 0) {
                        line.append("［", p)
                        p++
                        continue
                    }
                    line.hasNote = true
                    val command = parseCommand(source.substring(p + 2, close))
                    if (command is Command.Picture && close + 1 < end) {
                        line.spans += Span.InlineImage(line.text.length, command.path)
                        line.append("￼", p)
                    } else {
                        val next = apply(command, line, p, close + 1)
                        if (next !== line) rubyStart = null
                        line = next
                    }
                    barrier = line.text.length
                    p = close + 1
                }
                c == '｜' -> {
                    rubyStart = line.text.length
                    p++
                }
                c == '《' -> {
                    val close = source.indexOf('》', p).takeIf { it in 0 until end }
                    val baseStart = rubyStart ?: autoBaseStart(line.text, barrier)
                    if (close == null || baseStart == line.text.length) {
                        line.append("《", p)
                        p++
                    } else {
                        val ruby = stripNotes(source.substring(p + 1, close))
                        if (ruby.isNotEmpty()) line.spans += Span.Ruby(baseStart, line.text.length, ruby)
                        rubyStart = null
                        barrier = line.text.length
                        p = close + 1
                    }
                }
                c == '／' && source.startsWith("＼", p + 1) -> {
                    line.append("〳〵", p)
                    p += 2
                }
                c == '／' && p + 2 < end && source[p + 1] in "″゛" && source[p + 2] == '＼' -> {
                    line.append("〴〵", p)
                    p += 3
                }
                else -> {
                    line.append(c.toString(), p)
                    p++
                }
            }
        }
        if (line.text.isNotEmpty() || !line.hasNote) flush(line, paragraphEnd)
    }

    /** The index of the `］` that closes the note opening at [open], or -1. Notes may nest. */
    private fun noteEnd(open: Int, end: Int): Int {
        var depth = 0
        for (i in open until end) {
            when (source[i]) {
                '［' -> depth++
                '］' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun stripNotes(s: String): String = s.replace(Regex("""［＃[^］]*］"""), "")

    private fun autoBaseStart(text: CharSequence, barrier: Int): Int {
        if (text.length <= barrier) return text.length
        val kind = baseKind(text[text.length - 1]) ?: return text.length
        var i = text.length - 1
        while (i > barrier && baseKind(text[i - 1]) == kind) i--
        return i
    }

    /**
     * Applies [command], found at source `start until end`, and returns the builder the line continues in. A
     * [Command.Picture] here ends its line, so it stands as its own block.
     */
    private fun apply(command: Command, line: LineBuilder, start: Int, end: Int): LineBuilder {
        when (command) {
            Command.PageBreak, Command.CenterPage, is Command.Picture -> {
                if (line.text.isNotEmpty()) flush(line, start)
                val rest = LineBuilder(end).also {
                    it.hasNote = true
                    if (line.text.isEmpty()) it.indent = line.indent
                }
                blocks += when (command) {
                    Command.PageBreak -> PageBreak(start, end)
                    Command.CenterPage -> CenterPage(start, end)
                    is Command.Picture -> Figure(command.path, start, end)
                    else -> error("unreachable")
                }
                return rest
            }
            is Command.Indent -> line.indent = command.n
            is Command.IndentBlock -> indentBlock = command
            Command.IndentBlockEnd -> indentBlock = null
            is Command.Bottom -> line.bottom = BottomAlign(line.text.length, command.raise)
            is Command.BottomBlock -> bottomBlock = command.raise
            Command.BottomBlockEnd -> bottomBlock = null
            is Command.Reference -> {
                val at = line.text.lastIndexOf(command.target)
                if (at >= 0) addStyle(line, command.style, at, at + command.target.length)
            }
            is Command.RangeStart -> open += OpenRange(command.style, line.text.length)
            is Command.RangeEnd -> {
                val i = open.indexOfLast { it.style == command.style }
                if (i >= 0) {
                    val range = open.removeAt(i)
                    addStyle(line, range.style, range.start, line.text.length)
                }
            }
            Command.Ignored -> Unit
        }
        return line
    }

    private fun addStyle(line: LineBuilder, style: Style, start: Int, end: Int) {
        if (start >= end) return
        line.spans += when (style) {
            is Style.Emphasis -> Span.Emphasis(start, end, style.mark, style.side)
            is Style.Line -> Span.SideLine(start, end, style.style, style.side)
            Style.Bold -> Span.Bold(start, end)
            Style.TateChuYoko -> Span.TateChuYoko(start, end)
            is Style.Heading -> {
                headings += Heading(style.level, line.text.substring(start, end), line.offset(start))
                Span.HeadingText(start, end, style.level)
            }
        }
    }

    private fun flush(line: LineBuilder, end: Int) {
        for (range in open) {
            addStyle(line, range.style, range.start, line.text.length)
            range.start = 0
        }
        val block = indentBlock
        val indent = line.indent ?: block?.first ?: 0
        val wrap = line.indent ?: block?.wrap ?: 0
        val bottom = line.bottom ?: bottomBlock?.let { BottomAlign(0, it) }
        blocks += Paragraph(
            line.text.toString(), line.offsets(), line.start, end, line.spans.toList(), indent, wrap, bottom, isFirstLine,
        )
    }
}
