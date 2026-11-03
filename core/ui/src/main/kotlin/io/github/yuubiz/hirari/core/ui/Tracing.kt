package io.github.yuubiz.hirari.core.ui

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An async trace section for the measurements of docs/spec.md section 5.2. Async, because the work it covers
 * moves between threads and between suspensions, which a synchronous section cannot follow. The platform has
 * async sections from API 29, so on API 28 this records nothing.
 */
class TraceSection private constructor(private val name: String, private val cookie: Int) {
    private val open = AtomicBoolean(cookie != 0)

    /** Ends the section once; a second call does nothing. */
    fun end() {
        if (open.compareAndSet(true, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection(name, cookie)
    }

    companion object {
        private val cookies = AtomicInteger()

        fun begin(name: String): TraceSection {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return TraceSection(name, 0)
            val cookie = cookies.incrementAndGet()
            Trace.beginAsyncSection(name, cookie)
            return TraceSection(name, cookie)
        }
    }
}

/**
 * The section from a tap on a book in a list to the first page drawn (P4). The list starts it and the reader
 * ends it, and the two share no other state, so it lives here. A reader that opens without a tap in a list
 * starts it itself; a start while one is open drops the open one.
 */
object FirstPageTrace {
    const val NAME = "hirari.firstPage"
    private var section: TraceSection? = null

    @Synchronized
    fun begin() {
        section?.end()
        section = TraceSection.begin(NAME)
    }

    @Synchronized
    fun beginIfIdle() {
        if (section == null) begin()
    }

    @Synchronized
    fun end() {
        section?.end()
        section = null
    }
}
