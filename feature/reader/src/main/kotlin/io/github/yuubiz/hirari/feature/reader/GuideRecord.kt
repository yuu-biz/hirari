package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.LaunchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Remembers on [scope] that the operation guide was shown to the end (docs/spec.md section 4.8, 操作ガイド). A write
 * that fails is dropped: the guide shows again at the next reading, and the reading goes on.
 */
internal fun CoroutineScope.rememberGuideSeen(launchRecord: LaunchRepository): Job = launch {
    try {
        launchRecord.markReaderGuideSeen()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
