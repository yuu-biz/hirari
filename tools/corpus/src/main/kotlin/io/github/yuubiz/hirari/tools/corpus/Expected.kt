package io.github.yuubiz.hirari.tools.corpus

/** What the archive layer must report for a corpus file. */
sealed interface Expected {
    data class OpenFails(val reason: Failure) : Expected

    /** Opening lists [entries] in archive order. [indexError] is set when listing stops early. */
    data class Opens(val entries: List<ExpectedEntry>, val indexError: Failure? = null) : Expected
}

enum class Failure { Corrupt, Truncated }

/** [name] is the name after decoding by the rules of docs/spec.md section 3.4. */
class ExpectedEntry(val name: String, val read: ExpectedRead)

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
