package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.data.BookmarkRepository
import io.github.yuubiz.hirari.core.data.BookmarkScope
import io.github.yuubiz.hirari.core.data.ProgressRepository
import io.github.yuubiz.hirari.core.data.defaultBookmarkLabel
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.text.FixedGlyphMetrics
import io.github.yuubiz.hirari.core.text.GlyphMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

class FakeProgress : ProgressRepository {
    val stored = MutableStateFlow<Map<BookId, BookProgress>>(emptyMap())
    val writes = ArrayList<BookProgress>()

    override suspend fun get(book: BookId): BookProgress? = stored.value[book]

    override fun observe(book: BookId): Flow<BookProgress?> = stored.map { it[book] }

    override suspend fun save(progress: Collection<BookProgress>) {
        writes += progress
        stored.value = stored.value + progress.associateBy { it.book }
    }

    override suspend fun mark(book: BookId, state: ReadState, at: Instant) = Unit
}

class FakeBookmarks : BookmarkRepository {
    val marks = MutableStateFlow<List<Bookmark>>(emptyList())
    private var next = 1L

    override fun observe(sort: BookmarkSort): Flow<List<BookmarkGroup>> = emptyFlow()

    override fun inBook(book: BookId): Flow<List<Bookmark>> = marks.map { all -> all.filter { it.book == book } }

    override suspend fun add(book: BookId, position: ReadingPosition, label: String?, createdAt: Instant): Bookmark =
        Bookmark(next++, book, position, label ?: defaultBookmarkLabel(position), createdAt).also { marks.value += it }

    override suspend fun rename(id: Long, label: String) = Unit

    override suspend fun delete(ids: Collection<Long>) {
        marks.value = marks.value.filter { it.id !in ids }
    }

    override suspend fun delete(scope: BookmarkScope) = Unit
}

class FakeHistory : TextHistory {
    val opened = ArrayList<Pair<BookId, Instant>>()
    var stops = 0

    override fun opened(id: BookId, book: Book?, openedAt: Instant, limit: Int) {
        opened += id to openedAt
    }

    override fun stop() {
        stops++
    }
}

/** Every typeface measures with the fixed metrics; [gone] names imported fonts that are not there. */
class FakeFonts(private val gone: Set<String> = emptySet()) : TextFonts {
    override suspend fun available(typeface: TextTypeface): TextTypeface =
        if (typeface is TextTypeface.Imported && typeface.fileName in gone) TextTypeface.Mincho else typeface

    override suspend fun metrics(typeface: TextTypeface): GlyphMetrics = FixedGlyphMetrics
}

/** A text of [chapters] chapters with headings, each with one paragraph long enough to span several pages. */
fun sampleText(chapters: Int = 4, sentencesPerChapter: Int = 60): String = buildString {
    append("猫の本\n\n")
    for (chapter in 1..chapters) {
        append("第${chapter}章［＃「第${chapter}章」は大見出し］\n")
        append("　")
        repeat(sentencesPerChapter) { append("吾輩《わがはい》は猫である。名前はまだ${chapter}章の$it 番目にない。") }
        append('\n')
    }
}
