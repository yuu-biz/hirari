package io.github.yuubiz.hirari.tools.corpus

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Deterministic test data only. PKWARE APPNOTE section 6.1, traditional encryption. */
internal fun zipCrypto(plain: ByteArray, password: String): ByteArray {
    var k0 = 0x12345678
    var k1 = 0x23456789
    var k2 = 0x34567890
    fun crc(state: Int, byte: Int): Int {
        var value = state xor (byte and 255)
        repeat(8) { value = (value ushr 1) xor (if (value and 1 != 0) 0xedb88320.toInt() else 0) }
        return value
    }
    fun update(byte: Int) {
        k0 = crc(k0, byte)
        k1 = (k1 + (k0 and 255)) * 134775813 + 1
        k2 = crc(k2, k1 ushr 24)
    }
    password.toByteArray(Charsets.UTF_8).forEach { update(it.toInt()) }
    return ByteArray(plain.size) { i ->
        val t = (k2 and 0xffff) or 2
        val encrypted = plain[i].toInt() xor ((t * (t xor 1)) ushr 8)
        update(plain[i].toInt())
        encrypted.toByte()
    }
}

/** WinZip AES AE-2: PBKDF2, little endian CTR, and the first ten HMAC-SHA1 bytes. JDK APIs only. */
internal fun winZipAes(plain: ByteArray, password: String): ByteArray {
    val salt = ByteArray(16) { (it + 1).toByte() }
    val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 66 * 8)
    val keys = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
    finally { spec.clearPassword() }
    val aes = Cipher.getInstance("AES/ECB/NoPadding")
    aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.copyOfRange(0, 32), "AES"))
    val ciphertext = ByteArray(plain.size)
    for (offset in plain.indices step 16) {
        val nonce = ByteArray(16)
        val block = offset / 16 + 1
        repeat(4) { nonce[it] = (block ushr (8 * it)).toByte() }
        val stream = aes.doFinal(nonce)
        repeat(minOf(16, plain.size - offset)) { ciphertext[offset + it] = (plain[offset + it].toInt() xor stream[it].toInt()).toByte() }
    }
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(keys.copyOfRange(32, 64), "HmacSHA1")) }
    return (salt + keys.copyOfRange(64, 66) + ciphertext + mac.doFinal(ciphertext).copyOf(10)).also { keys.fill(0) }
}

internal const val ZIP_PASSWORD = "hirari"

internal fun passwordZipCases(): Map<String, CorpusCase> {
    val plain = ZipEntry("001.jpg".toByteArray(), letters(64, 1))
    val locked = ZipEntry("002.jpg".toByteArray(), letters(200, 2), Method.Deflated, password = ZIP_PASSWORD)
    val aes = ZipEntry("002.jpg".toByteArray(), locked.data, Method.Deflated, password = ZIP_PASSWORD, aes = true)
    val text = ZipEntry("notes.txt".toByteArray(), "password protected text".toByteArray(), password = ZIP_PASSWORD)
    return mapOf(
        "zip/zipcrypto.zip" to CorpusCase(writeZip(listOf(plain, locked, text)).bytes,
            Expected.Opens(listOf(ExpectedEntry("001.jpg", ExpectedRead.Data(plain.data)),
                ExpectedEntry("002.jpg", ExpectedRead.Fails(Failure.PasswordRequired)),
                ExpectedEntry("notes.txt", ExpectedRead.Fails(Failure.PasswordRequired))))),
        "zip/aes.zip" to CorpusCase(writeZip(listOf(plain, aes)).bytes,
            Expected.Opens(listOf(ExpectedEntry("001.jpg", ExpectedRead.Data(plain.data)),
                ExpectedEntry("002.jpg", ExpectedRead.Unsupported("encrypted"))))),
    )
}
