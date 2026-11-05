package io.github.yuubiz.hirari.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AozoraParserTest {
    @Test
    fun `TX05 fullwidth digits stay in standalone and inline illustration names`() {
        val figure = body("［＃挿絵（図１.png、横１００縦２００）入る］").single() as Figure
        assertEquals("図１.png", figure.path)
        val inline = paragraph("前［＃挿絵（図１.png）入る］後")
        assertEquals("図１.png", inline.spans.filterIsInstance<Span.InlineImage>().single().path)
        assertEquals(2, paragraph("［＃２字下げ］本文").indent)
    }

    /** The paragraphs of [source] after a title line, which the parser marks as the title. */
    private fun body(source: String, gaiji: JisX0213Table = JisX0213Table.None): List<Block> =
        parseAozora("題\n$source", gaiji).blocks.drop(1)

    private fun paragraph(source: String, gaiji: JisX0213Table = JisX0213Table.None): Paragraph =
        body(source, gaiji).single() as Paragraph

    @Test
    fun `ruby without a bar takes the run of the same kind before it`() {
        val p = paragraph("ひらがな漢字《かんじ》と吾輩《わがはい》")
        assertEquals("ひらがな漢字と吾輩", p.text)
        assertEquals(listOf(Span.Ruby(4, 6, "かんじ"), Span.Ruby(7, 9, "わがはい")), p.spans)
    }

    @Test
    fun `a bar marks where the base starts`() {
        assertEquals(listOf(Span.Ruby(2, 6, "かいしゃく")), paragraph("この｜ルビ付き《かいしゃく》").spans)
    }

    @Test
    fun `adjacent rubies do not swallow each other's base`() {
        assertEquals(listOf(Span.Ruby(0, 2, "かんじ"), Span.Ruby(2, 4, "かんじ")), paragraph("漢字《かんじ》漢字《かんじ》").spans)
    }

    @Test
    fun `display units keep the source range of their notations`() {
        val doc = parseAozora("吾輩《わがはい》は\n猫")
        val p = doc.blocks[0] as Paragraph
        assertEquals(listOf(0, 1, 8, 10), (0..3).map(p::sourceStart))
        assertEquals(0 until 10, p.start until p.end)
        assertEquals(1, p.displayIndexOf(5))
        assertEquals(10 until 11, (doc.blocks[1] as Paragraph).let { it.start until it.end })
        assertTrue(p.isTitle)
    }

    @Test
    fun `the three page breaks are one block each and leave no paragraph`() {
        val blocks = body("前\n［＃改ページ］\n［＃改丁］\n［＃改見開き］\n後")
        assertEquals(listOf("P", "B", "B", "B", "P"), blocks.map { if (it is PageBreak) "B" else "P" })
    }

    @Test
    fun `indents apply to a line or to a block with a wrap`() {
        val blocks = body("［＃３字下げ］一\n［＃ここから２字下げ、折り返して４字下げ］\n二\n［＃ここで字下げ終わり］\n三").map { it as Paragraph }
        assertEquals(listOf(Triple("一", 3, 3), Triple("二", 2, 4), Triple("三", 0, 0)), blocks.map { Triple(it.text, it.indent, it.wrapIndent) })
    }

    @Test
    fun `bottom alignment starts where the note stands`() {
        assertEquals(BottomAlign(0, 0), paragraph("［＃地付き］署名").bottom)
        assertEquals(BottomAlign(2, 2), paragraph("本文［＃地から２字上げ］署名").bottom)
    }

    @Test
    fun `headings become spans and table entries`() {
        val doc = parseAozora("題\n第一章［＃「第一章」は大見出し］\n［＃中見出し］序［＃中見出し終わり］")
        assertEquals(listOf(Heading(HeadingLevel.Large, "第一章", 2), Heading(HeadingLevel.Medium, "序", 19)), doc.headings)
        assertEquals(listOf(Span.HeadingText(0, 3, HeadingLevel.Large)), (doc.blocks[1] as Paragraph).spans)
    }

    @Test
    fun `emphasis marks by reference and by range, on either side`() {
        assertEquals(listOf(Span.Emphasis(0, 1, EmphasisMark.Sesame, MarkSide.Right)), paragraph("猫である［＃「猫」に傍点］").spans)
        assertEquals(listOf(Span.Emphasis(0, 1, EmphasisMark.WhiteCircle, MarkSide.Left)), paragraph("猫である［＃「猫」の左に白丸傍点］").spans)
        assertEquals(listOf(Span.Emphasis(1, 3, EmphasisMark.Fisheye, MarkSide.Right)), paragraph("我［＃蛇の目傍点］輩は［＃蛇の目傍点終わり］猫").spans)
    }

    @Test
    fun `side lines, bold, and tate-chu-yoko`() {
        assertEquals(listOf(Span.SideLine(0, 1, LineStyle.Wavy, MarkSide.Right)), paragraph("猫［＃「猫」に波線］").spans)
        assertEquals(listOf(Span.SideLine(0, 2, LineStyle.Double, MarkSide.Left)), paragraph("［＃左に二重傍線］猫だ［＃左に二重傍線終わり］").spans)
        assertEquals(listOf(Span.Bold(0, 1)), paragraph("猫［＃「猫」は太字］").spans)
        assertEquals(listOf(Span.TateChuYoko(1, 3)), paragraph("第12話［＃「12」は縦中横］").spans)
    }

    @Test
    fun `a range open at a line end continues on the next line`() {
        val blocks = body("［＃ここから太字］一\n二［＃ここで太字終わり］三").map { (it as Paragraph).spans }
        assertEquals(listOf(listOf<Span>(Span.Bold(0, 1)), listOf(Span.Bold(0, 1))), blocks)
    }

    @Test
    fun `a picture alone on its line is a block, and inside a sentence is inline`() {
        val note = "［＃挿絵（fig01.png、横321×縦123）入る］"
        assertEquals(Figure("fig01.png", 2, 2 + note.length), body(note).single())
        val p = paragraph("前［＃挿絵（a.png）入る］後")
        assertEquals("前￼後", p.text)
        assertEquals(listOf(Span.InlineImage(1, "a.png")), p.spans)
    }

    @Test
    fun `gaiji by code point, by name, and by table`() {
        assertEquals("挘", paragraph("※［＃「てへん＋劣」、U+6318、123-4］").text)
        assertEquals("①Ⅱⅲ㍻ヷ", paragraph("※［＃丸1、1-13-1］※［＃ローマ数字2、1-13-22］※［＃小文字ローマ数字３］※［＃「平成」の縦中横合字］※［＃濁点付き片仮名ワ、1-7-82］").text)
        assertEquals("※", paragraph("※［＃「木＋世」、第3水準1-85-6］").text)
        val table = JisX0213Table { plane, row, cell -> if (Triple(plane, row, cell) == Triple(1, 85, 6)) "枻" else null }
        assertEquals("枻", paragraph("※［＃「木＋世」、第3水準1-85-6］", table).text)
    }

    @Test
    fun `a gaiji number too large for an int stays as the unknown mark`() {
        val huge = "9".repeat(30)
        assertEquals("※※※", paragraph("※［＃丸$huge］※［＃ローマ数字$huge］※［＃小文字ローマ数字$huge］").text)
    }

    @Test
    fun `a notation number too large for an int saturates and a malformed kanji number is an ordinary note`() {
        assertEquals(Int.MAX_VALUE, (paragraph("［＃${"9".repeat(30)}字下げ］本") as Paragraph).indent)
        assertEquals(Int.MAX_VALUE, (paragraph("［＃地から${"９".repeat(30)}字上げ］本") as Paragraph).bottom?.raise)
        val malformed = paragraph("［＃一二字下げ］本")
        assertEquals("本", malformed.text)
        assertEquals(0, malformed.indent)
        assertEquals(12, paragraph("［＃十二字下げ］本").indent)
    }

    @Test
    fun `a ruby bar does not reach across a page break`() {
        val blocks = body("あいうえお｜漢字［＃改ページ］あ《る》")
        val after = blocks.last() as Paragraph
        assertEquals("あ", after.text)
        assertEquals(listOf(Span.Ruby(0, 1, "る")), after.spans)
    }

    @Test
    fun `kunoji marks become their vertical forms`() {
        assertEquals("いろ〳〵だん〴〵", paragraph("いろ／＼だん／″＼").text)
    }

    @Test
    fun `the notation help between two rules is hidden`() {
        val rule = "-".repeat(55)
        val doc = parseAozora("題\n著者\n$rule\n【テキスト中に現れる記号について】\n《》：ルビ\n$rule\n本文")
        assertEquals(listOf("題", "著者", "本文"), doc.blocks.map { (it as Paragraph).text })
    }

    @Test
    fun `other notes vanish and leave the text`() {
        val p = paragraph("本文［＃「本文」は底本では「本分」］続き")
        assertEquals("本文続き", p.text)
        assertTrue(p.spans.isEmpty())
    }

    @Test
    fun `unclosed notation stays as text`() {
        assertEquals("《開いたまま", paragraph("《開いたまま").text)
        assertEquals("［＃閉じない", paragraph("［＃閉じない").text)
    }

    @Test
    fun `a blank line is an empty paragraph and the first line is the title`() {
        val doc = parseAozora("題\n\n本文")
        assertEquals(listOf("", "本文"), doc.blocks.drop(1).map { (it as Paragraph).text })
        assertEquals("題", doc.title)
        assertNull(parseAozora("").title)
    }

    @Test
    fun `without heading notes the contents guess headings from blank lines`() {
        val doc = parseAozora("題\n\n一\n本文が続く。\n\n二\n本文\n\n三")
        assertEquals(listOf("一", "三"), tableOfContents(doc).map { it.text })
    }

    @Test
    fun `picture paths resolve against the text file`() {
        assertEquals("book/img/a.png", resolvePicturePath("book/text/a.txt", "../img/a.png"))
        assertEquals("a.png", resolvePicturePath("a.txt", "./a.png"))
        assertNull(resolvePicturePath("a.txt", "../a.png"))
    }
}
