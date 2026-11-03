package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

private const val TURN_LABEL = "turn"

/**
 * P1 to P3 of docs/spec.md section 5.2, read from the Perfetto trace, for the Q1 prototypes and for the reader.
 * [section] names the async section to read (`hirari.turn` for a view that was prepared, `hirari.jump` for one
 * that was not) and [label] prefixes the measurements, which are listed here with the label `turn`. The frame
 * counts and jank shares and `turnsSkipped` come only with the label `turn`, so two of these can share a test.
 *
 * - `turnDrawMs`: from the tap's event time to the main thread recording the first frame that draws the
 *   new view. The app's section covers handling to recording, and its name carries the wait from the event time
 *   to the handling.
 * - `turnShownMs`: from the same event time to the end of that frame's FrameTimeline actual slice, which
 *   is when the frame reached the display. Needs API 31 or later.
 * - `turnRenderThreadMs`: the RenderThread's `DrawFrames` slice of that frame. A software bitmap drawn for
 *   the first time is uploaded to the GPU inside it.
 * - `timelineJankPercent`: frames whose FrameTimeline jank type is not `None`, and
 *   `timelineAppJankPercent`: frames the app made late (`App Deadline Missed`), both as a share of all the
 *   app's frames in the measured block.
 * - `turnsSkipped`: taps that found the next view not prepared. They are not turns and are not measured.
 */
@OptIn(ExperimentalMetricApi::class)
class TurnMetric(private val section: String = "hirari.turn", private val label: String = TURN_LABEL) : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val app = "'${captureInfo.targetPackageName}'"
        val turns = traceSession.query(
            """
            WITH turn AS (
              SELECT s.ts, s.dur, s.name, s.ts + s.dur AS end_ts, p.upid
              FROM slice s JOIN process_track pt ON s.track_id = pt.id JOIN process p ON pt.upid = p.upid
              WHERE p.name = $app AND s.name LIKE '$section lagUs=%' AND s.dur > 0
            ),
            frame AS (
              SELECT d.ts, d.dur, CAST(SUBSTR(d.name, 23) AS INT) AS vsync, t.upid
              FROM slice d JOIN thread_track tt ON d.track_id = tt.id JOIN thread t ON tt.utid = t.utid
                JOIN process p ON t.upid = p.upid
              WHERE p.name = $app AND t.tid = p.pid AND d.name GLOB 'Choreographer#doFrame [0-9]*'
            ),
            turn_frame AS (
              SELECT turn.ts, turn.dur, turn.name, turn.upid, MIN(frame.vsync) AS vsync
              FROM turn LEFT JOIN frame
                ON frame.upid = turn.upid AND frame.ts <= turn.end_ts AND frame.ts + frame.dur >= turn.end_ts
              GROUP BY turn.ts, turn.dur, turn.name, turn.upid
            )
            SELECT tf.ts AS ts, tf.dur AS dur, tf.name AS name,
              (SELECT MAX(a.ts + a.dur) FROM actual_frame_timeline_slice a
                WHERE a.upid = tf.upid AND CAST(a.name AS INT) = tf.vsync) AS shown,
              (SELECT MAX(r.dur) FROM slice r JOIN thread_track rtt ON r.track_id = rtt.id
                JOIN thread rt ON rtt.utid = rt.utid
                WHERE rt.upid = tf.upid AND rt.name = 'RenderThread' AND r.name = 'DrawFrames ' || tf.vsync) AS render
            FROM turn_frame tf
            ORDER BY tf.ts
            """.trimIndent(),
        ).toList()
        val drawMs = mutableListOf<Double>()
        val shownMs = mutableListOf<Double>()
        val renderMs = mutableListOf<Double>()
        for (row in turns) {
            val lagNanos = row.string("name").substringAfter("lagUs=").toLong() * 1000
            val eventTs = row.long("ts") - lagNanos
            drawMs += (row.long("ts") + row.long("dur") - eventTs) / 1e6
            row.nullableLong("shown")?.let { shownMs += (it - eventTs) / 1e6 }
            row.nullableLong("render")?.let { renderMs += it / 1e6 }
        }
        val frames = traceSession.query(
            """
            SELECT COUNT(*) AS frames,
              SUM(CASE WHEN a.jank_type = 'None' THEN 0 ELSE 1 END) AS janky,
              SUM(CASE WHEN a.jank_type LIKE '%App Deadline Missed%' THEN 1 ELSE 0 END) AS app_janky
            FROM actual_frame_timeline_slice a JOIN process p ON a.upid = p.upid
            WHERE p.name = $app
            """.trimIndent(),
        ).single()
        val skipped = traceSession.query(
            """
            SELECT COUNT(*) AS n FROM slice s JOIN thread_track tt ON s.track_id = tt.id
              JOIN thread t ON tt.utid = t.utid JOIN process p ON t.upid = p.upid
            WHERE p.name = $app AND s.name = 'hirari.turnSkipped'
            """.trimIndent(),
        ).single().long("n")
        val frameCount = frames.long("frames").coerceAtLeast(1)
        return buildList {
            if (drawMs.isNotEmpty()) add(Measurement("${label}DrawMs", drawMs))
            if (shownMs.isNotEmpty()) add(Measurement("${label}ShownMs", shownMs))
            if (renderMs.isNotEmpty()) add(Measurement("${label}RenderThreadMs", renderMs))
            add(Measurement("${label}Count", drawMs.size.toDouble()))
            add(Measurement("${label}ShownCount", shownMs.size.toDouble()))
            if (label == TURN_LABEL) {
                add(Measurement("turnsSkipped", skipped.toDouble()))
                add(Measurement("timelineFrameCount", frameCount.toDouble()))
                add(Measurement("timelineJankPercent", 100.0 * (frames.nullableLong("janky") ?: 0) / frameCount))
                add(Measurement("timelineAppJankPercent", 100.0 * (frames.nullableLong("app_janky") ?: 0) / frameCount))
            }
        }
    }
}
