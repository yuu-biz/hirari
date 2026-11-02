package io.github.yuubiz.hirari.tools.corpus

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * A PDF book of the corpus. PDFs are not archives, so they stay out of [MANIFEST] and add only their `book`
 * and `page` lines to [PAGES_MANIFEST]. [open] is the tokens after the path on the `book` line, and [pages] the
 * size in points of each page, which is its shape.
 */
internal class PdfCase(val bytes: ByteArray, val open: List<String>, val pages: List<Pair<Int, Int>> = emptyList())

/** The PDF cases of docs/spec.md section 6.2: mixed page sizes, a user password, and a broken file. */
internal fun pdfCases(): Map<String, PdfCase> {
    val sizes = listOf(595 to 842, 842 to 595, 300 to 400)
    return mapOf(
        "pdf/mixed-sizes.pdf" to PdfCase(pdf(sizes), listOf("opens", "-"), sizes),
        "pdf/password.pdf" to PdfCase(pdf(listOf(595 to 842), PdfPassword(user = PDF_USER_PASSWORD, owner = "owner")), listOf("fails", "encrypted")),
        "pdf/broken.pdf" to PdfCase(ascii("%PDF-1.4\n") + letters(2000, 7), listOf("fails", "corrupt")),
    )
}

/** The user password of `pdf/password.pdf`, for the device test of docs/spec.md Q4. */
internal const val PDF_USER_PASSWORD = "hirari"

/** The `book` and `page` lines of [cases], in the format of [pagesManifest]. */
internal fun pdfPagesManifest(cases: Map<String, PdfCase>): String = buildString {
    for ((path, case) in cases) {
        append((listOf("book", path) + case.open).joinToString("\t")).append('\n')
        case.pages.forEachIndexed { i, (width, height) -> append("page\t${i + 1}\tready\t$width\t$height\n") }
    }
}

private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

internal class PdfPassword(val user: String, val owner: String)

/**
 * A PDF of one page per size in [sizes], each with a filled square so it draws something. With [password], the
 * document has the standard security handler of revision 2 (40-bit RC4) and its pages have no content, since
 * the content streams would have to be encrypted too.
 */
internal fun pdf(sizes: List<Pair<Int, Int>>, password: PdfPassword? = null): ByteArray {
    val objects = ArrayList<String>()
    val pageIds = sizes.indices.map { 3 + it * 2 }
    objects += "<< /Type /Catalog /Pages 2 0 R >>"
    objects += "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${sizes.size} >>"
    for ((width, height) in sizes) {
        val contents = if (password == null) " /Contents ${objects.size + 2} 0 R" else ""
        objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height]$contents >>"
        val stream = "0.2 0.4 0.8 rg 20 20 ${width / 2} ${height / 2} re f"
        objects += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
    }
    val id = MessageDigest.getInstance("MD5").digest(ascii("hirari corpus ${sizes.size}"))
    val encryption = password?.let { standardSecurity(it, id) }
    if (encryption != null) objects += encryption
    val out = StringBuilder("%PDF-1.4\n")
    val offsets = objects.mapIndexed { i, body ->
        val offset = out.length
        out.append("${i + 1} 0 obj\n$body\nendobj\n")
        offset
    }
    val xref = out.length
    out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
    val encrypt = if (encryption != null) " /Encrypt ${objects.size} 0 R" else ""
    out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R$encrypt /ID [<${id.hex()}> <${id.hex()}>] >>\n")
    out.append("startxref\n$xref\n%%EOF\n")
    return ascii(out.toString())
}

/** The encryption dictionary of the standard security handler, revision 2, by the algorithms of ISO 32000-1 7.6.3. */
private fun standardSecurity(password: PdfPassword, id: ByteArray): String {
    val permissions = -4
    val owner = rc4(md5(padded(password.owner)).copyOf(5), padded(password.user))
    val key = md5(padded(password.user) + owner + littleEndian(permissions) + id).copyOf(5)
    val user = rc4(key, PASSWORD_PADDING)
    return "<< /Filter /Standard /V 1 /R 2 /O <${owner.hex()}> /U <${user.hex()}> /P $permissions >>"
}

private fun padded(password: String): ByteArray = (ascii(password) + PASSWORD_PADDING).copyOf(32)

private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

private fun rc4(key: ByteArray, data: ByteArray): ByteArray =
    Cipher.getInstance("ARCFOUR").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ARCFOUR")) }.doFinal(data)

private fun littleEndian(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private val PASSWORD_PADDING = intArrayOf(
    0x28, 0xbf, 0x4e, 0x5e, 0x4e, 0x75, 0x8a, 0x41, 0x64, 0x00, 0x4e, 0x56, 0xff, 0xfa, 0x01, 0x08,
    0x2e, 0x2e, 0x00, 0xb6, 0xd0, 0x68, 0x3e, 0x80, 0x2f, 0x0c, 0xa9, 0xfe, 0x64, 0x53, 0x69, 0x7a,
).let { values -> ByteArray(values.size) { values[it].toByte() } }
