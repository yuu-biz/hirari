package io.github.yuubiz.hirari.tools.corpus

import io.github.yuubiz.hirari.core.model.NaturalOrder

/** What the archive layer must report for a corpus file. */
sealed interface Expected {
    data class OpenFails(val reason: Failure) : Expected

    /** Opening lists [entries] in archive order. [indexError] is set when listing stops early. */
    data class Opens(val entries: List<ExpectedEntry>, val indexError: Failure? = null) : Expected
}

enum class Failure { Corrupt, Truncated }

/**
 * [name] is the name after decoding by the rules of docs/spec.md section 3.4. [page] is what the book opener
 * returns for the entry, or null when the entry is not a page.
 */
class ExpectedEntry(val name: String, val read: ExpectedRead, val page: ExpectedPage? = derivedPage(name, read))

/** What the book opener must return for a page (docs/spec.md section 2.4). */
sealed interface ExpectedPage {
    /** [width] and [height] are the size of the original image. */
    data class Ready(val width: Int, val height: Int) : ExpectedPage

    data class Fails(val reason: Failure) : ExpectedPage

    data object NotAnImage : ExpectedPage

    data object Encrypted : ExpectedPage

    data class Compression(val method: String) : ExpectedPage

    data class TooLarge(val width: Int, val height: Int, val bytes: Long) : ExpectedPage

    data class ImageFeature(val detail: String) : ExpectedPage
}

private val IMAGE_NAME = Regex(""".*\.(jpg|png)""")

/**
 * The page an entry named like an image becomes when its expectation is not given. The archive cases fill
 * such entries with letters, which are not an image.
 */
private fun derivedPage(name: String, read: ExpectedRead): ExpectedPage? {
    if (!IMAGE_NAME.matches(name)) return null
    return when (read) {
        is ExpectedRead.Data -> ExpectedPage.NotAnImage
        ExpectedRead.Directory -> null
        is ExpectedRead.Fails -> ExpectedPage.Fails(read.reason)
        is ExpectedRead.Unsupported -> when {
            read.kind == "encrypted" -> ExpectedPage.Encrypted
            read.kind.startsWith("compression:") -> ExpectedPage.Compression(read.kind.removePrefix("compression:"))
            else -> error("give the page of a ${read.kind} entry")
        }
    }
}

sealed interface ExpectedRead {
    class Data(val bytes: ByteArray) : ExpectedRead

    data object Directory : ExpectedRead

    data class Fails(val reason: Failure) : ExpectedRead

    /** [kind] is `encrypted`, `too-large`, or `compression:` and libarchive's name of the method. */
    data class Unsupported(val kind: String) : ExpectedRead
}

/**
 * The expectations as tab-separated lines, for the device tests to read without this module. An `archive` line
 * gives the path and either `opens` with the index error or `-`, or `fails` with the reason. The `entry` lines
 * that follow give the name and either `data` with the size and CRC-32, `directory`, `fails` with the reason, or
 * `unsupported` with the kind. Reasons are lowercase.
 */
fun manifest(cases: Map<String, CorpusCase>): String = buildString {
    for ((path, case) in cases) {
        when (val expected = case.expected) {
            is Expected.OpenFails -> line("archive", path, "fails", expected.reason.token)
            is Expected.Opens -> {
                line("archive", path, "opens", expected.indexError?.token ?: "-")
                for (entry in expected.entries) {
                    when (val read = entry.read) {
                        is ExpectedRead.Data -> line("entry", entry.name, "data", read.bytes.size.toString(), "%08x".format(crc32(read.bytes)))
                        ExpectedRead.Directory -> line("entry", entry.name, "directory")
                        is ExpectedRead.Fails -> line("entry", entry.name, "fails", read.reason.token)
                        is ExpectedRead.Unsupported -> line("entry", entry.name, "unsupported", read.kind)
                    }
                }
            }
        }
    }
}

private val Failure.token: String get() = name.lowercase()

private fun StringBuilder.line(vararg fields: String) {
    fields.forEach { require('\t' !in it && '\n' !in it) }
    append(fields.joinToString("\t")).append('\n')
}

/**
 * The page expectations as tab-separated lines. A `book` line gives the path and either `opens` with the index
 * error or `-`, or `fails` with the reason. The `page` lines that follow are in natural order and give the name
 * and either `ready` with the width and height, `fails` with the reason, or `unsupported` with the kind and its
 * fields: `not-an-image`, `encrypted`, `compression` and the method, `too-large` and the width, height and bytes,
 * or `image-feature` and the detail.
 */
fun pagesManifest(cases: Map<String, CorpusCase>): String = buildString {
    for ((path, case) in cases) {
        when (val expected = case.expected) {
            is Expected.OpenFails -> line("book", path, "fails", expected.reason.token)
            is Expected.Opens -> {
                line("book", path, "opens", expected.indexError?.token ?: "-")
                val pages = expected.entries.filter { it.page != null }.sortedWith(compareBy(NaturalOrder) { it.name })
                for (entry in pages) line("page", entry.name, *entry.page!!.fields())
            }
        }
    }
}

private fun ExpectedPage.fields(): Array<String> = when (this) {
    is ExpectedPage.Ready -> arrayOf("ready", width.toString(), height.toString())
    is ExpectedPage.Fails -> arrayOf("fails", reason.token)
    ExpectedPage.NotAnImage -> arrayOf("unsupported", "not-an-image")
    ExpectedPage.Encrypted -> arrayOf("unsupported", "encrypted")
    is ExpectedPage.Compression -> arrayOf("unsupported", "compression", method)
    is ExpectedPage.TooLarge -> arrayOf("unsupported", "too-large", width.toString(), height.toString(), bytes.toString())
    is ExpectedPage.ImageFeature -> arrayOf("unsupported", "image-feature", detail)
}
