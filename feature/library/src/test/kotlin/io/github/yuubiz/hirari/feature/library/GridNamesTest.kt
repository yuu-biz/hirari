package io.github.yuubiz.hirari.feature.library

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/** docs/spec.md section 4.3, グリッドの名前. */
class GridNamesTest {
    private fun item(name: String, directory: Boolean = false): FileItem =
        requireNotNull(FolderEntry(name, documentId = name, directory = directory, size = 1L, modified = Instant.EPOCH).toItem(showDotFiles = true))

    @Test
    fun `a name splits into the title and the inside of its first brackets`() {
        val table = listOf(
            "[堀越耕平] 僕のヒーローアカデミア 第03巻.zip" to GridName("僕のヒーローアカデミア 第03巻", "堀越耕平"),
            "［作者］タイトル 01.cbz" to GridName("タイトル 01", "作者"),
            "タイトル 01.cbz" to GridName("タイトル 01", ""),
            "[サークル] 題 [第2版] (C99).zip" to GridName("題 [第2版] (C99)", "サークル"),
            "題名[作者]続き.rar" to GridName("題名続き", "作者"),
            "題名 [作者] 続き.7z" to GridName("題名 続き", "作者"),
            "題名 [作者].pdf" to GridName("題名", "作者"),
            "[作者].zip" to GridName("作者", ""),
            "[] 題.zip" to GridName("題", ""),
            "[閉じない 題.zip" to GridName("[閉じない 題", ""),
            "［全角 [半角] 題］.zip" to GridName("全角 [半角] 題", ""),
            "novel.v2.txt" to GridName("novel.v2", ""),
        )
        for ((name, expected) in table) assertEquals(expected, item(name).gridName(), name)
    }

    @Test
    fun `a folder and an image keep their extensions and split their brackets`() {
        assertEquals(GridName("Vol.1", "作者"), item("[作者] Vol.1", directory = true).gridName())
        assertEquals(GridName("cover.jpg", "作者"), item("[作者] cover.jpg").gridName())
    }

    private fun fitsIn(count: Int): (String) -> Boolean = { it.codePointCount(0, it.length) <= count }

    @Test
    fun `a title that fits is left whole`() {
        assertEquals("タイトル 01", middleElided("タイトル 01", fitsIn(9)))
    }

    @Test
    fun `a long title keeps its start and its end around an ellipsis`() {
        val title = "僕のヒーローアカデミア とても長い副題がここに入ります 第03巻"
        assertEquals("僕のヒーローアカデ…ります 第03巻", middleElided(title, fitsIn(18)))
    }

    @Test
    fun `the cut drops the spaces next to the ellipsis`() {
        assertEquals("abc…xyz", middleElided("abc   middle   xyz", fitsIn(7)))
    }

    @Test
    fun `the cut never splits a character made of several code points`() {
        val family = "👨‍👩‍👧"
        assertEquals("あ$family…え", middleElided("あ${family}いうえ") { it.length <= 11 })
    }

    @Test
    fun `nothing fitting leaves the ellipsis alone`() {
        assertEquals("…", middleElided("長い名前", fitsIn(1)))
    }
}
