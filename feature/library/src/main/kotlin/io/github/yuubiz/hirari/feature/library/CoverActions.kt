package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The binding direction a book is shown with: its own, else the global one (docs/spec.md section 4.4). */
internal suspend fun LibraryServices.directionOf(id: BookId): ReadingDirection =
    coverIndex.directions().first()[id] ?: settings.settings.first().readingDirection

/**
 * 子の画像やページをフォルダの表紙にする / 選ぶのをやめる (docs/spec.md section 4.4): saves the choice for the
 * folder at [folder] of [place], then makes the thumbnail of that folder again at every size. When the folder's own
 * entry cannot be found, only the choice is saved; the folder shows it once its listing is read again. It runs on
 * [LibraryServices.appScope], so leaving the screen does not leave the choice saved but the thumbnails old, and a
 * later choice for the same folder stops this one first, so a slow earlier choice never lands over it.
 */
internal fun LibraryServices.applyFolderCover(place: LibraryLocation, folder: String, cover: FolderCover?): Job =
    folderCoverRuns.run(place.id to folder) {
        locations.setFolderCover(place.id, folder, cover)
        val direction = directionOf(locationBookId(place, folder))
        val spec = covers.folderSpec(place, folder, direction, cover?.let { covers.chosen(place, it) }) ?: return@run
        covers.regenerate(spec, covers.sizesFrom(settings.settings.first().thumbSize))
    }

/**
 * Work on [scope] of which only the latest run of a key counts: a new run cancels the one before it and starts once
 * that one has ended, so the runs of a key never overlap and what they write lands in the order they were asked.
 */
internal class LatestRuns<K : Any>(private val scope: CoroutineScope) {
    private val running = HashMap<K, Job>()

    @Synchronized
    fun run(key: K, block: suspend CoroutineScope.() -> Unit): Job {
        val before = running[key]?.also { it.cancel() }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            before?.let { withContext(NonCancellable) { it.join() } }
            ensureActive()
            block()
        }
        running[key] = job
        job.invokeOnCompletion { forget(key, job) }
        job.start()
        return job
    }

    @Synchronized
    private fun forget(key: K, job: Job) {
        if (running[key] === job) running.remove(key)
    }
}
