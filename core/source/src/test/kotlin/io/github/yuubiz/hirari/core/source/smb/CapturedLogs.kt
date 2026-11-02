package io.github.yuubiz.hirari.core.source.smb

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everything smbj logs during the unit tests, at every level. The app ships no SLF4J provider, so there smbj logs
 * nothing; the tests install this one through META-INF/services to see what it would say.
 */
object CapturedLogs {
    val lines = ConcurrentLinkedQueue<String>()
}

class CapturingProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val factory = ILoggerFactory { name -> loggers.getOrPut(name) { CapturingLogger(name) } }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory

    override fun getMarkerFactory(): IMarkerFactory = markers

    override fun getMDCAdapter(): MDCAdapter = mdc

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() {}
}

private class CapturingLogger(name: String) : AbstractLogger() {
    init {
        this.name = name
    }

    override fun isTraceEnabled() = true

    override fun isTraceEnabled(marker: Marker?) = true

    override fun isDebugEnabled() = true

    override fun isDebugEnabled(marker: Marker?) = true

    override fun isInfoEnabled() = true

    override fun isInfoEnabled(marker: Marker?) = true

    override fun isWarnEnabled() = true

    override fun isWarnEnabled(marker: Marker?) = true

    override fun isErrorEnabled() = true

    override fun isErrorEnabled(marker: Marker?) = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, pattern: String?, arguments: Array<out Any?>?, throwable: Throwable?) {
        val message = MessageFormatter.basicArrayFormat(pattern, arguments)
        CapturedLogs.lines += "$level $name $message${throwable?.let { "\n" + it.stackTraceToString() }.orEmpty()}"
    }
}
