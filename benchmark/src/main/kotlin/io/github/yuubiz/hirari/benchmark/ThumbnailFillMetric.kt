package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

private const val VISIBLE_ITEMS = 15

/**
 * P8 of docs/spec.md section 5.2: how long the covers of the first [VISIBLE_ITEMS] rows take to fill. The app's
 * `hirari.thumbnail` async section covers one bound row, from its lookup to its cover or its failure.
 *
 * - `thumbnailFillMs`: from the start of the first section to the end of the [VISIBLE_ITEMS]th section to end.
 *   When fewer ended, to the last, and `thumbnailSections` is that smaller count.
 * - `thumbnailSections`: how many sections ended in the measured block.
 */
@OptIn(ExperimentalMetricApi::class)
class ThumbnailFillMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val sections = traceSession.query(
            "SELECT ts, ts + dur AS finish FROM slice WHERE name = 'hirari.thumbnail' AND dur > 0 ORDER BY finish",
        ).toList()
        if (sections.isEmpty()) return listOf(Measurement("thumbnailSections", 0.0))
        val filled = sections.take(VISIBLE_ITEMS)
        val first = filled.minOf { it.long("ts") }
        val last = filled.last().long("finish")
        return listOf(
            Measurement("thumbnailFillMs", (last - first) / 1e6),
            Measurement("thumbnailSections", sections.size.toDouble()),
        )
    }
}
