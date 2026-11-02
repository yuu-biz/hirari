package io.github.yuubiz.hirari.tools.corpus

import java.nio.charset.Charset

/**
 * The RAR, 7z and tar cases of docs/spec.md section 6.2. RAR is written with the store method only, so the
 * compressed RAR cases, solid ones included, are not generated (section 6.2 lists them).
 */
internal fun archiveCases(): Map<String, CorpusCase> = rarCases() + sevenZipCases() + tarCases()

private val CP932: Charset = Charset.forName("windows-31j")

private fun data(name: String, bytes: ByteArray) = ExpectedEntry(name, ExpectedRead.Data(bytes))

private fun directory(name: String) = ExpectedEntry(name, ExpectedRead.Directory)

private fun encrypted(name: String) = ExpectedEntry(name, ExpectedRead.Unsupported("encrypted"))

private val pages = listOf("001.jpg" to letters(3000, 1), "002.jpg" to letters(20000, 2), "003.jpg" to letters(5000, 3))

private fun rarCases(): Map<String, CorpusCase> {
    val japanese = "第1話/表紙.jpg"
    val files = pages.map { (name, bytes) -> RarEntry(name.toByteArray(), bytes) }
    val plainExpected = pages.map { (name, bytes) -> data(name, bytes) }
    val withEncrypted = listOf(files[0], RarEntry("002.jpg".toByteArray(), letters(3000, 9), encrypted = true), files[2])
    val withEncryptedExpected = listOf(plainExpected[0], encrypted("002.jpg"), plainExpected[2])
    // RAR 4 separates folders with `\`, and the trail byte of 表 is the same byte.
    val rar4Names = listOf(
        RarEntry("第1話".toByteArray(CP932), ByteArray(0), isDirectory = true),
        RarEntry(japanese.replace('/', '\\').toByteArray(CP932), pages[0].second),
    )
    val rar5Names = listOf(RarEntry("第1話".toByteArray(), ByteArray(0), isDirectory = true), RarEntry(japanese.toByteArray(), pages[0].second))
    val namesExpected = listOf(directory("第1話"), data(japanese, pages[0].second))
    return mapOf(
        "rar4/stored.rar" to CorpusCase(writeRar4(files), Expected.Opens(plainExpected)),
        // RAR 4 keeps names without the Unicode flag in the ANSI code page, CP932 on Japanese Windows.
        "rar4/names-cp932.rar" to CorpusCase(writeRar4(rar4Names), Expected.Opens(namesExpected)),
        // libarchive stops at the first file header with the solid flag, which every file after the first has.
        "rar4/solid.rar" to CorpusCase(writeRar4(files, solid = true), Expected.Opens(plainExpected.take(1), Failure.Corrupt)),
        "rar4/encrypted.rar" to CorpusCase(writeRar4(withEncrypted), Expected.Opens(withEncryptedExpected)),
        "rar4/encrypted-headers.rar" to CorpusCase(writeRar4(files, encryptedHeaders = true), Expected.OpenFails(Failure.Encrypted)),
        "rar5/stored.rar" to CorpusCase(writeRar5(files), Expected.Opens(plainExpected)),
        "rar5/names.rar" to CorpusCase(writeRar5(rar5Names), Expected.Opens(namesExpected)),
        "rar5/solid.rar" to CorpusCase(writeRar5(files, solid = true), Expected.Opens(plainExpected)),
        "rar5/encrypted.rar" to CorpusCase(writeRar5(withEncrypted), Expected.Opens(withEncryptedExpected)),
        "rar5/encrypted-headers.rar" to CorpusCase(writeRar5(files, encryptedHeaders = true), Expected.OpenFails(Failure.Encrypted)),
    )
}

private fun sevenZipCases(): Map<String, CorpusCase> {
    val files = pages.map { (name, bytes) -> SevenZipFile(name, bytes) } + SevenZipFile("第1話/表紙.jpg", letters(4000, 4))
    val expected = files.map { data(it.name, it.data) }
    fun one(method: SevenZipMethod, file: SevenZipFile) = SevenZipFolder(method, listOf(file))
    val methods = listOf(SevenZipMethod.Lzma, SevenZipMethod.Lzma2, SevenZipMethod.Deflate, SevenZipMethod.BZip2, SevenZipMethod.Lzma2X86)
        .mapIndexed { i, method -> one(method, SevenZipFile("%03d.jpg".format(i + 1), letters(10000, 10 + i))) }
    val unreadable = listOf(
        one(SevenZipMethod.Copy, SevenZipFile("001.jpg", letters(3000, 1))),
        one(SevenZipMethod.Aes, SevenZipFile("002.jpg", letters(3000, 2))),
        one(SevenZipMethod.Copy, SevenZipFile("003.jpg", letters(3000, 3))),
    )
    return mapOf(
        "7z/copy.7z" to CorpusCase(write7z(files.map { one(SevenZipMethod.Copy, it) }), Expected.Opens(expected)),
        "7z/copy-solid.7z" to CorpusCase(write7z(listOf(SevenZipFolder(SevenZipMethod.Copy, files))), Expected.Opens(expected)),
        "7z/lzma2-solid.7z" to CorpusCase(write7z(listOf(SevenZipFolder(SevenZipMethod.Lzma2, files))), Expected.Opens(expected)),
        "7z/methods.7z" to CorpusCase(write7z(methods), Expected.Opens(methods.flatMap { it.files }.map { data(it.name, it.data) })),
        "7z/unreadable.7z" to CorpusCase(
            write7z(unreadable),
            Expected.Opens(
                listOf(
                    data("001.jpg", unreadable[0].files[0].data),
                    encrypted("002.jpg"),
                    data("003.jpg", unreadable[2].files[0].data),
                ),
            ),
        ),
        "7z/encrypted-header.7z" to CorpusCase(write7z(emptyList(), encryptedHeader = true), Expected.OpenFails(Failure.Encrypted)),
    )
}

private fun tarCases(): Map<String, CorpusCase> {
    val entries = listOf(TarEntry("ch1/".toByteArray(), ByteArray(0), isDirectory = true)) +
        pages.map { (name, bytes) -> TarEntry("ch1/$name".toByteArray(), bytes) } +
        TarEntry("ch1/表紙.jpg".toByteArray(), letters(4000, 4))
    val tar = writeTar(entries)
    val expected = Expected.Opens(
        entries.map { if (it.isDirectory) directory(String(it.name)) else data(String(it.name), it.data) },
    )
    return mapOf("tar/plain.tar" to CorpusCase(tar, expected)) +
        TarFilter.entries.associate { "tar/compressed.${it.extension}" to CorpusCase(compressTar(tar, it), expected) }
}
