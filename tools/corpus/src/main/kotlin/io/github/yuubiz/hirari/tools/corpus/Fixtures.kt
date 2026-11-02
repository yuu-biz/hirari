package io.github.yuubiz.hirari.tools.corpus

/**
 * The PPMd cases. No library the generator may use writes PPMd, so these two archives were made once with 7-Zip
 * 26.02 (x64) on Windows from the files of [ppmdPages], and are kept in `src/main/resources/fixtures`:
 *
 * - `ppmd.7z`: `7z a -t7z -m0=PPMd ppmd.7z 001.jpg 002.jpg`. One solid folder, PPMd var.H of order 6 with 512 KiB
 *   of model memory. SHA-256 b4577b0a5651438893bcd29c774f0c35546d1391673d16438f1f5c71a43a2c2c.
 * - `ppmd.zip`: `7z a -tzip -mm=PPMd ppmd.zip 001.jpg 002.jpg`. Method 98, PPMd var.I revision 1. SHA-256
 *   629e310f6ca4255c9da1ee6e1aa5d210b1481e9314c3253b0052dbcd47f4a8c3.
 */
internal fun ppmdCases(): Map<String, CorpusCase> {
    val expected = Expected.Opens(ppmdPages.map { (name, bytes) -> ExpectedEntry(name, ExpectedRead.Data(bytes)) })
    return mapOf(
        "7z/ppmd.7z" to CorpusCase(fixture("ppmd.7z"), expected),
        "zip/ppmd.zip" to CorpusCase(fixture("ppmd.zip"), expected),
    )
}

/** The files in the PPMd fixtures: lines of ASCII text, the kind of data PPMd is made for. */
internal val ppmdPages: List<Pair<String, ByteArray>> = listOf("001.jpg" to ppmdText(1, 400), "002.jpg" to ppmdText(2, 300))

private fun ppmdText(fixture: Int, lines: Int): ByteArray =
    (1..lines).joinToString("") { "Hirari PPMd fixture $fixture line $it\n" }.toByteArray(Charsets.US_ASCII)

internal fun fixture(name: String): ByteArray =
    CorpusCase::class.java.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }
