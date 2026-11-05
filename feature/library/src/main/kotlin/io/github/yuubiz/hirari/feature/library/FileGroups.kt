package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.NaturalOrder
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Cells after filtering and sorting, including rows that never request a cover. */
internal sealed interface ListCell {
    val key: String

    data object Parent : ListCell {
        override val key = PARENT_KEY
    }

    data class Header(val name: String, val count: Int, override val key: String) : ListCell

    data class Entry(val row: FileRow) : ListCell {
        override val key get() = row.path
    }
}

internal const val PARENT_KEY = ".."

/** Upper then lower case folds ß and final sigma too. */
internal fun normalizedName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

/** Each root is the smallest normalized name in its component, independent of list order. */
private class CreatorGroups {
    private val parents = mutableMapOf<String, String>()

    fun root(name: String): String {
        parents.putIfAbsent(name, name)
        var root = name
        while (parents.getValue(root) != root) root = parents.getValue(root)
        var current = name
        while (current != root) {
            val next = parents.getValue(current)
            parents[current] = root
            current = next
        }
        return root
    }

    fun join(names: List<String>) {
        val first = names.firstOrNull() ?: return
        for (name in names.drop(1)) {
            val a = root(first)
            val b = root(name)
            parents[maxOf(a, b)] = minOf(a, b)
        }
    }
}

private data class CreatorRow(val row: FileRow, val parsed: LibraryName) {
    val names = parsed.names.map(::normalizedName).filter { it.isNotEmpty() }.distinct()
}
/** Count exact full spellings; linked map order breaks ties by the first appearance in the sorted list. */
private fun groupSpelling(items: List<CreatorRow>): String? {
    val both = items.filter { it.parsed.circle != null && it.parsed.author != null }
    return both.ifEmpty { items }.mapNotNull { it.parsed.creator }.groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
}

/** Navigation metadata, separate from a storage path. */
data class PseudoFolder(val key: String, val name: String, val kind: LibraryGrouping, val merge: Boolean, val filter: String,
    val parent: PseudoFolder? = null, val memberKeys: List<String> = emptyList())

/** Keep the reconstructed snapshot in the screen's ViewModel, never in saved navigation arguments. */
internal fun rememberPseudoFolder(rows: List<FileRow>, folder: PseudoFolder?): PseudoFolder? {
    if (folder == null || folder.memberKeys.isNotEmpty()) return folder
    val parent = rememberPseudoFolder(rows, folder.parent)
    val members = if (parent == null) rows else {
        val paths = parent.memberKeys.toHashSet()
        rows.filter { it.path in paths }
    }
    return folder.copy(parent = parent, memberKeys = originalGroupMembers(members, folder))
}

/** Resolve each level against today's settings; a vanished group returns its nearest surviving parent. */
internal fun resolvePseudoFolder(rows: List<FileRow>, folder: PseudoFolder?, look: ListLook): Pair<PseudoFolder?, List<FileRow>> {
    if (folder == null) return null to rows
    val chain = generateSequence(folder) { it.parent }.toList().asReversed()
    var parent: PseudoFolder? = null
    var members = rows
    for ((depth, group) in chain.withIndex()) {
        val kind = when (depth) {
            0 -> look.grouping
            1 -> look.effectiveSubgrouping
            else -> LibraryGrouping.None
        }
        if (kind == LibraryGrouping.None || kind != group.kind) break
        val groups = fileGroups(members.filtered(group.filter), kind, look.mergeCircleAuthors).filter { it.rows.size >= 2 }
        val remembered = group.memberKeys.ifEmpty { originalGroupMembers(members, group) }.toHashSet()
        val match = groups.map { candidate -> candidate to candidate.rows.count { it.path in remembered } }
            .maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
        if (match == null) break
        parent = group.copy(key = scopedGroupKey(parent, match.key), name = match.name,
            merge = look.mergeCircleAuthors, parent = parent, memberKeys = match.rows.map { it.path })
        members = match.rows
    }
    return parent to members
}

/** Routes carry only the grouping conditions; reconstruct membership from the physical folder listing. */
private fun originalGroupMembers(rows: List<FileRow>, group: PseudoFolder): List<String> {
    val prefix = group.parent?.let { "/group-child:${it.key.length}:${it.key}" }.orEmpty()
    val key = group.key.removePrefix(prefix)
    val groups = fileGroups(rows.filtered(group.filter), group.kind, group.merge)
    val match = groups.firstOrNull { it.key == key }
        // A newly linked creator can change the component's smallest name (and hence its key).
        ?: if (group.kind == LibraryGrouping.Author && group.merge) {
            val creator = key.removePrefix("/group:Author:")
            groups.firstOrNull { candidate -> candidate.rows.any { row ->
                parseLibraryName(row.item.groupBase()).names.any { normalizedName(it) == creator }
            } }
        } else null
    return match?.rows?.map { it.path }.orEmpty()
}

private fun scopedGroupKey(parent: PseudoFolder?, key: String): String =
    if (parent == null) key else "/group-child:${parent.key.length}:${parent.key}$key"

internal fun ListLook.groupingInside(parent: PseudoFolder?): LibraryGrouping = when {
    parent == null -> grouping
    parent.parent == null -> effectiveSubgrouping
    else -> LibraryGrouping.None
}

internal data class FileGroup(val key: String, val name: String, val rows: List<FileRow>)

/** The input is filtered and sorted. Real folders never join a component, even as a creator link. */
internal fun fileGroups(rows: List<FileRow>, kind: LibraryGrouping, merge: Boolean): List<FileGroup> {
    if (kind == LibraryGrouping.None) return emptyList()
    val creators = rows.filter { it.item !is FileItem.Folder }.map { CreatorRow(it, parseLibraryName(it.item.groupBase())) }
    val unions = CreatorGroups()
    if (kind == LibraryGrouping.Author && merge) creators.forEach { unions.join(it.names) }
    fun spelling(it: CreatorRow): String? = when (kind) {
        LibraryGrouping.None -> null
        LibraryGrouping.Author -> it.parsed.creator
        LibraryGrouping.Series -> seriesTitle(it.parsed.title)
        LibraryGrouping.Original -> it.parsed.original
        LibraryGrouping.Event -> it.parsed.event
    }?.takeIf { name -> name.isNotBlank() }
    return creators.groupBy {
        val name = if (kind == LibraryGrouping.Author && merge) it.names.firstOrNull()?.let(unions::root)
            else spelling(it)?.let(::normalizedName)
        name?.let { "/group:${kind.name}:$it" }
    }.mapNotNull { (key, items) ->
        if (key == null) null else FileGroup(key,
            (if (kind == LibraryGrouping.Author && merge) groupSpelling(items) else spelling(items.first())).orEmpty(),
            items.map { it.row })
    }
}

private fun FileItem.groupBase(): String = if (this is FileItem.Folder) entry.name else
    entry.name.substringBeforeLast('.', entry.name).trim()

/** Replace only multi-item groups. A synthetic folder reuses its first member's exact CoverSpec. */
internal fun pseudoFolders(rows: List<FileRow>, kind: LibraryGrouping, merge: Boolean, filter: String,
    sort: LibrarySort, foldersFirst: Boolean, parent: PseudoFolder? = null): List<FileRow> {
    if (kind == LibraryGrouping.None || parent?.parent != null) return rows
    val groups = fileGroups(rows, kind, merge).filter { it.rows.size >= 2 }
    val groupedPaths = groups.flatMap { it.rows }.mapTo(hashSetOf()) { it.path }
    val result = rows.filterNot { it.path in groupedPaths } + groups.map { group ->
        val key = scopedGroupKey(parent, group.key)
        FileRow(FileItem.Folder(FolderEntry(group.name, null, true, null, null)), key, normalizedName(group.name),
            cover = group.rows.first().cover, gridName = GridName(group.name, "${group.rows.size} 件"),
            sizeText = "${group.rows.size} 件", pseudo = PseudoFolder(key, group.name, kind, merge, filter, parent, group.rows.map { it.path }))
    }
    val byName = compareBy<FileRow, String>(NaturalOrder) { it.item.entry.name }
    val byFiles = when (sort) {
        LibrarySort.Name -> byName
        LibrarySort.Newest -> compareBy<FileRow, Instant?>(nullsLast(reverseOrder())) { it.item.entry.modified }.then(byName)
        LibrarySort.Oldest -> compareBy<FileRow, Instant?>(nullsLast(naturalOrder())) { it.item.entry.modified }.then(byName)
    }
    // With mixed date ordering, fill folder slots in name order and preserve the sorted file slots.
    val mixed = result.sortedWith(if (foldersFirst) compareBy<FileRow> { it.item !is FileItem.Folder }.then(byFiles) else byFiles)
    val folders = mixed.filter { it.item is FileItem.Folder }.sortedWith(byName).iterator()
    return mixed.map { if (it.item is FileItem.Folder) folders.next() else it }
}

private enum class Section(val title: String) {
    Folders("フォルダ"), Unread("未読"), Reading("読みかけ"), Read("読了"),
    Today("今日"), Yesterday("昨日"), Week("今週"), Month("今月"), Year("今年"), Older("それ以前"), Unknown("日時不明"),
}

/** Headers are independent of grouping and use stable non-path keys for saved scroll positions. */
internal fun fileCells(rows: List<FileRow>, section: LibrarySection, parent: Boolean,
    folded: Set<String> = emptySet(), now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<ListCell> = buildList {
    if (parent) add(ListCell.Parent)
    if (section == LibrarySection.None) {
        rows.mapTo(this) { ListCell.Entry(it) }
        return@buildList
    }
    val today = now.atZone(zone).toLocalDate()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val groups = rows.groupBy { row ->
        if (row.item is FileItem.Folder) Section.Folders
        else if (section == LibrarySection.ReadState) when (row.mark) {
            ReadMark.Read -> Section.Read
            is ReadMark.Reading -> Section.Reading
            else -> Section.Unread
        } else {
            val date = row.item.entry.modified?.atZone(zone)?.toLocalDate()
            when {
                date == null -> Section.Unknown
                date >= today -> Section.Today
                date == today.minusDays(1) -> Section.Yesterday
                date >= monday -> Section.Week
                date >= today.withDayOfMonth(1) -> Section.Month
                date >= today.withDayOfYear(1) -> Section.Year
                else -> Section.Older
            }
        }
    }
    for ((kind, items) in groups.toSortedMap()) {
        val key = "/section:${section.name}:${kind.name}"
        add(ListCell.Header(kind.title, items.size, key))
        if (key !in folded) items.mapTo(this) { ListCell.Entry(it) }
    }
}
