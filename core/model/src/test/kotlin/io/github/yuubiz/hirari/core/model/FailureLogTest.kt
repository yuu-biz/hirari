package io.github.yuubiz.hirari.core.model

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailureLogTest {
    private val error = IOException("private-book.cbz", IllegalStateException("private-server/private-path"))

    private fun capture(debuggable: Boolean): LogRecord {
        var record: LogRecord? = null
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(value: LogRecord) { record = value }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        logger.logFailure("Cannot read", error, debuggable)
        return checkNotNull(record)
    }

    @Test
    fun `release logs only fixed text and the exception class`() {
        val record = capture(debuggable = false)
        assertEquals("Cannot read: java.io.IOException", record.message)
        assertNull(record.thrown)
        assertNull(record.parameters)
        val output = SimpleFormatter().format(record)
        assertFalse(output.contains("private-"))
        assertFalse(output.contains("IllegalStateException"))
    }

    @Test
    fun `debug keeps the full throwable and cause`() {
        val record = capture(debuggable = true)
        assertEquals("Cannot read", record.message)
        assertSame(error, record.thrown)
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        assertTrue(SimpleFormatter().format(record).contains(trace))
    }
}
