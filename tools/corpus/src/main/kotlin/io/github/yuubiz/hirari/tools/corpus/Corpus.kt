package io.github.yuubiz.hirari.tools.corpus

import java.io.File

/** Every corpus file by its path under the corpus directory (docs/spec.md section 6.2). */
fun corpus(): Map<String, ByteArray> = mapOf(
    "zip/names.zip" to storedZip(
        listOf(
            StoredEntry("001.txt".toByteArray(Charsets.US_ASCII), "1".toByteArray(), utf8Flag = false),
            StoredEntry("chapter/002.txt".toByteArray(Charsets.US_ASCII), "2".toByteArray(), utf8Flag = false),
            StoredEntry("日本語.txt".toByteArray(Charsets.UTF_8), "3".toByteArray(), utf8Flag = true),
        ),
    ),
)

/** Writes the corpus into the directory given as the only argument. */
fun main(args: Array<String>) {
    val dir = File(args.single())
    corpus().forEach { (path, bytes) ->
        dir.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}
