package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.PageView
import kotlin.math.roundToInt

/** A sheet over the reader (docs/spec.md section 4.8). At most one is up at a time. */
sealed interface ReaderSheet {
    /** ページパネル. [openedOn] is the head page when it opened, where 取り消す goes back to. */
    data class Pages(val openedOn: Int) : ReaderSheet

    /** メニュー. */
    data object Menu : ReaderSheet

    /** 画像補正: 明るさ, シャープ, グレースケール, and 白黒反転 of this book. */
    data object Adjust : ReaderSheet

    /** 本ごとの設定, opened on [opened], where 元に戻す goes back to. */
    data class BookSettings(val opened: ReaderConfig) : ReaderSheet

    /** ページを共有 on a pair: which of its two pages. */
    data class SharePick(val view: PageView.Two) : ReaderSheet

    /** 表紙にする, or with [range] 範囲を選んで表紙にする, on a pair: which of its two pages. */
    data class CoverPick(val view: PageView.Two, val range: Boolean) : ReaderSheet

    /** しおりを追加 at the view starting with [page], with a name the user may change. */
    data class AddBookmark(val page: Int) : ReaderSheet

    /** しおり一覧 of this book. */
    data object Bookmarks : ReaderSheet

    /**
     * The neighboring book toward [edge]: the sheet of 本の端での動作, or with [fromMenu] the choices of 前の本 and
     * 次の本 in the menu.
     */
    data class BookEnd(val edge: BookEdge, val fromMenu: Boolean = false) : ReaderSheet

    /**
     * Whether the reader takes [code] while this sheet is up. Back closes it and Menu opens or closes the menu.
     * Every other key goes to the sheet's controls, and on the page panel Del goes to the page number field.
     */
    fun takes(code: Int): Boolean = when (this) {
        is Pages, Adjust, is SharePick, is CoverPick, is BookEnd, is AddBookmark, Bookmarks, is BookSettings -> code == KeyCodes.BACK || code == KeyCodes.MENU
        Menu -> code == KeyCodes.BACK || code == KeyCodes.MENU || code == KeyCodes.DEL
    }
}

/** The page number of [view] as the reader shows it: `12 / 200`, or `12-13 / 200` for a pair. */
fun pageLabel(view: PageView, pageCount: Int): String {
    val pages = view.pages.sorted().map { it + 1 }
    val shown = if (pages.size == 1) "${pages[0]}" else "${pages.first()}-${pages.last()}"
    return "$shown / $pageCount"
}

/** The page panel's controls (docs/spec.md section 4.8, ページバー). Pages are counted from 0 here. */
object PagePanel {
    /** The interval of the live preview while the slider is dragged. */
    const val PREVIEW_MS = 100L

    /** The page a typed number means, from 1. A number past either end goes to that end; other text means none. */
    fun typed(text: String, pageCount: Int): Int? = text.trim().toLongOrNull()?.let { (it - 1).coerceIn(0L, pageCount - 1L).toInt() }

    fun atSlider(value: Float, pageCount: Int): Int = value.roundToInt().coerceIn(0, pageCount - 1)
}

/** The menu's width: 95% of the screen's short side, up to 400 dp (docs/spec.md section 4.8, メニュー). */
fun menuWidthDp(screenWidthDp: Float, screenHeightDp: Float): Float = minOf(minOf(screenWidthDp, screenHeightDp) * 0.95f, 400f)

enum class MenuGroup { Book, Move, Reading, App }

/** The menu's items in their order. */
enum class MenuItem(val group: MenuGroup) {
    AddBookmark(MenuGroup.Book),
    Bookmarks(MenuGroup.Book),
    BookSettings(MenuGroup.Book),
    SetCover(MenuGroup.Book),
    SetCoverFromRange(MenuGroup.Book),
    SharePage(MenuGroup.Book),
    RestoreAndClose(MenuGroup.Book),
    FirstPage(MenuGroup.Move),
    LastPage(MenuGroup.Move),
    ShiftBack(MenuGroup.Move),
    ShiftForward(MenuGroup.Move),
    PreviousBook(MenuGroup.Move),
    NextBook(MenuGroup.Move),
    AutoPlay(MenuGroup.Reading),
    Breath(MenuGroup.Reading),
    Orientation(MenuGroup.Reading),
    MirrorControls(MenuGroup.Reading),
    Guide(MenuGroup.Reading),
    Settings(MenuGroup.App),
    EditCommandBar(MenuGroup.App),
    About(MenuGroup.App),
}
