package io.github.yuubiz.hirari.core.model

/**
 * Filename candidates, in lowercase without a dot (docs/spec.md section 3.2).
 * Callers own listing and page-selection policies; readers still decide the format from the bytes.
 */
object FileFormats {
    val imagePages: Set<String> = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl",
    )

    val archiveBooks: Map<String, Container> = mapOf(
        "zip" to Container.Zip,
        "cbz" to Container.Zip,
        "rar" to Container.Rar,
        "cbr" to Container.Rar,
        "7z" to Container.SevenZip,
        "cb7" to Container.SevenZip,
        "tar" to Container.Tar,
        "cbt" to Container.Tar,
    )

    val pdfs: Set<String> = setOf("pdf")
    val texts: Set<String> = setOf("txt")
}
