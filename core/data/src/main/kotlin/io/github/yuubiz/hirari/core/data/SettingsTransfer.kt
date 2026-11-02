package io.github.yuubiz.hirari.core.data

import androidx.room3.withWriteTransaction
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** The version of the settings file. A newer file still imports; keys this version does not know are ignored. */
internal const val SETTINGS_FILE_FORMAT = 1

/** An SMB server as the settings file holds it: never the user name, the domain or the password. */
@Serializable
internal data class ServerJson(
    val displayName: String = "",
    val host: String,
    val port: Int = 445,
    val share: String,
    val folder: String = "",
)

@Serializable
internal sealed interface PositionJson {
    @Serializable
    @SerialName("image")
    data class Image(val viewStart: Int, val pageCount: Int) : PositionJson

    @Serializable
    @SerialName("text")
    data class Text(val charOffset: Long, val totalChars: Long) : PositionJson
}

@Serializable
internal data class ProgressJson(val book: String, val state: ReadState, val position: PositionJson? = null, val updatedAt: Long)

@Serializable
internal data class BookmarkJson(val book: String, val position: PositionJson, val label: String, val createdAt: Long)

@Serializable
internal data class HistoryJson(val book: String, val openedAt: Long)

/** What a settings file would change, for the confirmation of docs/spec.md section 4.11. */
data class ImportChanges(
    val settings: Int,
    val bookOverrides: Int,
    val servers: Int,
    val progress: Int,
    val bookmarks: Int,
    val history: Int,
) {
    val total: Int get() = settings + bookOverrides + servers + progress + bookmarks + history
}

/** A settings file read and checked entry by entry, ready to apply. */
class ImportPlan internal constructor(
    internal val settings: GlobalSettings,
    internal val bookOverrides: Map<BookId, BookOverrides>,
    internal val servers: List<SmbServer>,
    internal val progress: List<BookProgressRow>,
    internal val bookmarks: List<BookmarkRow>,
    internal val history: List<HistoryRow>,
)

sealed interface ImportPreparation {
    /** Not JSON, not an object, or without the format version. */
    data object NotASettingsFile : ImportPreparation

    data class Ready(val plan: ImportPlan, val changes: ImportChanges) : ImportPreparation
}

/** 設定の書き出しと読み込み of docs/spec.md section 4.11. Safe to call from the main thread. */
class SettingsTransfer internal constructor(
    private val settings: SettingsRepository,
    private val database: HirariDatabase,
) {
    /** [includeReading] adds positions, read states, bookmarks and history. */
    suspend fun export(includeReading: Boolean): String {
        val file = buildJsonObject {
            put(FORMAT, SETTINGS_FILE_FORMAT)
            put(SETTINGS, SettingsJson.encodeToJsonElement(GlobalSettings.serializer(), settings.settings.first()))
            put(
                BOOK_OVERRIDES,
                JsonObject(database.bookOverrides().all().associate { it.bookId to it.overrides.toBookOverrides().toJson() }),
            )
            put(SERVERS, encode(ServerJson.serializer(), database.locations().all().filter { it.kind == LocationKind.Smb }.map { it.toServerJson() }))
            if (includeReading) {
                put(PROGRESS, encode(ProgressJson.serializer(), database.bookProgress().all().map { it.toJson() }))
                put(BOOKMARKS, encode(BookmarkJson.serializer(), database.bookmarks().all().map { it.toJson() }))
                put(HISTORY, encode(HistoryJson.serializer(), database.history().all().map { HistoryJson(it.bookId, it.openedAt) }))
            }
        }
        return file.toString()
    }

    suspend fun prepare(text: String): ImportPreparation {
        val file = try {
            SettingsJson.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        if (file == null || (file[FORMAT] as? JsonPrimitive)?.intOrNull == null) {
            return ImportPreparation.NotASettingsFile
        }
        val plan = ImportPlan(
            settings = (file[SETTINGS] as? JsonObject ?: JsonObject(emptyMap())).toGlobalSettings(),
            bookOverrides = (file[BOOK_OVERRIDES] as? JsonObject).orEmpty()
                .mapNotNull { (book, value) -> (value as? JsonObject)?.let { BookId(book) to it.toBookOverrides() } }
                .toMap(),
            servers = file.entries(SERVERS, ServerJson.serializer()).mapNotNull { it.toServer() },
            progress = file.entries(PROGRESS, ProgressJson.serializer()).mapNotNull { it.toRow() }.newestPerBook(),
            bookmarks = file.entries(BOOKMARKS, BookmarkJson.serializer()).mapNotNull { it.toRow() },
            history = file.entries(HISTORY, HistoryJson.serializer()).map { HistoryRow(it.book, it.openedAt) },
        )
        return ImportPreparation.Ready(plan, changes(plan))
    }

    /**
     * Applies [plan] all or nothing. The database rows, the added servers and the settings go in one transaction
     * and the settings file is written last, so an error before it leaves everything as it was.
     */
    suspend fun apply(plan: ImportPlan) {
        database.withWriteTransaction {
            database.bookOverrides().upsert(
                plan.bookOverrides.map { (book, overrides) -> BookOverridesRow(book.value, overrides.toJson().toString()) },
            )
            database.bookProgress().upsertNewer(plan.progress)
            database.bookmarks().insert(newBookmarks(plan))
            database.history().upsert(newerHistory(plan))
            database.history().keepNewest(plan.settings.historyLimit)
            newServers(plan).forEach { database.locations().insert(it.toRow(id = 0, credential = null)) }
            settings.update { plan.settings }
        }
    }

    private suspend fun changes(plan: ImportPlan): ImportChanges {
        val current = SettingsJson.encodeToJsonElement(GlobalSettings.serializer(), settings.settings.first()).jsonObject
        val imported = SettingsJson.encodeToJsonElement(GlobalSettings.serializer(), plan.settings).jsonObject
        val storedOverrides = database.bookOverrides().all().associate { BookId(it.bookId) to it.overrides.toBookOverrides() }
        val progress = database.bookProgress()
        return ImportChanges(
            settings = current.keys.count { current[it] != imported[it] },
            bookOverrides = plan.bookOverrides.count { (book, overrides) -> (storedOverrides[book] ?: BookOverrides()) != overrides },
            servers = newServers(plan).size,
            progress = plan.progress.count { row -> progress.get(row.bookId).let { it == null || it.updatedAt < row.updatedAt } },
            bookmarks = newBookmarks(plan).size,
            history = newerHistory(plan).size,
        )
    }

    /** A server already registered with the same host, port, share and folder is not added again. */
    private suspend fun newServers(plan: ImportPlan): List<SmbServer> {
        val known = database.locations().all().filter { it.kind == LocationKind.Smb }.map { it.toServerJson().identity }.toSet()
        return plan.servers.distinctBy { it.identity }.filter { it.identity !in known }
    }

    private suspend fun newBookmarks(plan: ImportPlan): List<BookmarkRow> {
        val known = database.bookmarks().all().map { it.identity }.toSet()
        return plan.bookmarks.distinctBy { it.identity }.filter { it.identity !in known }
    }

    private suspend fun newerHistory(plan: ImportPlan): List<HistoryRow> {
        val dao = database.history()
        return plan.history.groupBy { it.bookId }.map { (_, rows) -> rows.maxBy { it.openedAt } }
            .filter { row -> dao.get(row.bookId).let { it == null || it.openedAt < row.openedAt } }
    }

    private companion object {
        const val FORMAT = "format"
        const val SETTINGS = "settings"
        const val BOOK_OVERRIDES = "bookOverrides"
        const val SERVERS = "servers"
        const val PROGRESS = "progress"
        const val BOOKMARKS = "bookmarks"
        const val HISTORY = "history"
    }
}

private fun <T> encode(serializer: KSerializer<T>, values: List<T>): JsonElement =
    SettingsJson.encodeToJsonElement(ListSerializer(serializer), values)

/** Each entry is read on its own, so one bad entry is skipped and the others still import. */
private fun <T> JsonObject.entries(key: String, serializer: KSerializer<T>): List<T> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { entry ->
        try {
            SettingsJson.decodeFromJsonElement(serializer, entry)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

private fun LocationRow.toServerJson() = ServerJson(displayName, host.orEmpty(), port, share.orEmpty(), basePath.orEmpty())

private val ServerJson.identity get() = listOf(host.lowercase(), port.toString(), share.lowercase(), normalizePath(folder))

private val SmbServer.identity get() = listOf(host.lowercase(), port.toString(), share.lowercase(), normalizePath(basePath))

private fun ServerJson.toServer(): SmbServer? {
    if (host.isBlank() || share.isBlank()) return null
    return SmbServer(
        displayName = displayName.ifBlank { host },
        host = host,
        port = port.coerceIn(1, 65535),
        share = share,
        basePath = normalizePath(folder),
    )
}

private val BookmarkRow.identity get() = listOf(bookId, positionKind.name, positionAt.toString(), positionTotal.toString(), label)

private fun ReadingPosition.toJson(): PositionJson = when (this) {
    is ReadingPosition.Image -> PositionJson.Image(viewStart, pageCount)
    is ReadingPosition.Text -> PositionJson.Text(charOffset, totalChars)
}

/** `null` for a position that cannot be in its book. */
private fun PositionJson.toModel(): ReadingPosition? = when (this) {
    is PositionJson.Image -> if (pageCount > 0 && viewStart in 0..<pageCount) ReadingPosition.Image(viewStart, pageCount) else null
    is PositionJson.Text -> if (totalChars >= 0 && charOffset in 0..totalChars) ReadingPosition.Text(charOffset, totalChars) else null
}

private fun BookProgressRow.toJson() = toModel().let { ProgressJson(bookId, it.state, it.position?.toJson(), updatedAt) }

private fun ProgressJson.toRow(): BookProgressRow? {
    val parsed = position?.let { it.toModel() ?: return null }
    return BookProgress(BookId(book), state, parsed, Instant.ofEpochMilli(updatedAt)).toRow()
}

private fun BookmarkRow.toJson() = BookmarkJson(bookId, toModel().position.toJson(), label, createdAt)

private fun BookmarkJson.toRow(): BookmarkRow? =
    position.toModel()?.let { bookmarkRow(BookId(book), it, label, Instant.ofEpochMilli(createdAt)) }
