package io.github.yuubiz.hirari.tools.corpus

import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * A text corpus file: its bytes, the charset the reader must pick for it, and the text it must read back, with LF
 * line ends and U+FFFD for each bad sequence (docs/spec.md sections 3.11 and 6.2).
 */
class TextCase(val bytes: ByteArray, val charset: Charset, val text: String)

const val TEXT_MANIFEST = "text.tsv"

private val CP932: Charset = Charset.forName("windows-31j")
private val EUC_JP: Charset = Charset.forName("EUC-JP")

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

/** A lead byte CP932 leaves unassigned. Put before a line feed, which cannot be a trail byte, it is one bad sequence. */
private val BAD_CP932 = byteArrayOf(0x85.toByte())

private val bookLines = listOf(
    "吾輩《わがはい》は猫である。名前はまだ無い。",
    "どこで生れたかとんと見当《けんとう》がつかぬ。",
    "［＃５字下げ］一［＃「一」は中見出し］",
    "何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。",
    "吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番｜獰悪《どうあく》な種族であったそうだ。",
    "第12話のABC――いろ／＼あって……",
    "※［＃「てへん＋劣」、U+6318、10-5］と①、ｶﾀｶﾅ、～。",
    "",
    "［＃改ページ］",
)

/** EUC-JP has no NEC row 13 and maps the wave dash differently, so its book leaves those lines out. */
private val eucLines = bookLines.filter { EUC_JP.newEncoder().canEncode(it) }

/** A book of about [chars] characters, cycling [lines] with a chapter number so the text does not repeat exactly. */
private fun book(chars: Int, lines: List<String> = bookLines): String = buildString {
    append("吾輩は猫である\n夏目漱石\n\n")
    var chapter = 1
    while (length < chars) {
        append("第").append(chapter++).append("章\n")
        for (line in lines) append(line).append('\n')
    }
}

private fun crlf(text: String) = text.replace("\n", "\r\n")

private fun invalidCp932(): TextCase {
    val text = book(3000)
    val cuts = listOf(text.indexOf('\n', text.length / 3), text.indexOf('\n', text.length * 2 / 3))
    val bytes = buildList {
        var from = 0
        for (cut in cuts) {
            add(text.substring(from, cut).toByteArray(CP932))
            add(BAD_CP932)
            from = cut
        }
        add(text.substring(from).toByteArray(CP932))
    }.reduce(ByteArray::plus)
    val expected = StringBuilder(text).apply { cuts.reversed().forEach { insert(it, '�') } }.toString()
    return TextCase(bytes, CP932, expected)
}

/** Every text corpus case by its path under the corpus directory. */
fun textCases(): Map<String, TextCase> {
    val text = book(3000)
    return mapOf(
        "text/cp932-crlf.txt" to TextCase(crlf(text).toByteArray(CP932), CP932, text),
        "text/utf8.txt" to TextCase(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8, text),
        "text/utf8-bom-crlf.txt" to TextCase(UTF8_BOM + crlf(text).toByteArray(Charsets.UTF_8), Charsets.UTF_8, text),
        "text/utf16le-bom.txt" to TextCase(UTF16LE_BOM + text.toByteArray(Charsets.UTF_16LE), Charsets.UTF_16LE, text),
        "text/utf16be-bom.txt" to TextCase(UTF16BE_BOM + text.toByteArray(Charsets.UTF_16BE), Charsets.UTF_16BE, text),
        "text/euc-jp.txt" to book(3000, eucLines).let { TextCase(it.toByteArray(EUC_JP), EUC_JP, it) },
        "text/cp932-invalid.txt" to invalidCp932(),
        "text/cp932-short.txt" to TextCase("猫".toByteArray(CP932), CP932, "猫"),
        "text/cp932-10mb.txt" to book(5_400_000).let { TextCase(crlf(it).toByteArray(CP932), CP932, it) },
    )
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** One line per case: path, charset, length of the expected text in UTF-16 units, and its SHA-256 in UTF-8. */
fun textManifest(cases: Map<String, TextCase>): String = buildString {
    for ((path, case) in cases) {
        append(path).append('\t').append(case.charset.name()).append('\t').append(case.text.length).append('\t').append(sha256(case.text)).append('\n')
    }
}

/** Writes the text corpus into the directory given as the only argument. */
object TextCorpus {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.single())
        val cases = textCases()
        for ((path, case) in cases) dir.resolve(path).apply { parentFile.mkdirs() }.writeBytes(case.bytes)
        dir.resolve(TEXT_MANIFEST).writeText(textManifest(cases))
    }
}
