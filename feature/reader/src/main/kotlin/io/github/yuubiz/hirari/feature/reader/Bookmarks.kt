package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.defaultBookmarkLabel
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.ReadingPosition

/** The position a bookmark at the view starting with [page] keeps, as the saved position names it (docs/spec.md section 2.7). */
fun bookmarkAt(page: Int, pageCount: Int): ReadingPosition.Image = ReadingPosition.Image(page, pageCount)

/** The name typed when adding a bookmark, or `null` for the default name of docs/spec.md section 2.8 when it is blank. */
fun bookmarkLabel(typed: String): String? = typed.trim().ifEmpty { null }

/**
 * The page of a book of [pageCount] pages that [mark] goes to, or `null` for a bookmark of a text position. A page
 * past the end, as after the pages were ordered another way, goes to the last page.
 */
fun Bookmark.page(pageCount: Int): Int? = (position as? ReadingPosition.Image)?.viewStart?.coerceIn(0, pageCount - 1)

/** Whether the list shows the page beside the name: a name left at its default already is the page. */
val Bookmark.showsPage: Boolean get() = label != defaultBookmarkLabel(position)
