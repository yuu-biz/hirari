package io.github.yuubiz.hirari.core.model

import java.util.logging.Level
import java.util.logging.Logger

/** [message] must be fixed text, without book or server data (docs/spec.md section 6.6). */
fun Logger.logFailure(message: String, error: Throwable, debuggable: Boolean) {
    if (debuggable) {
        log(Level.WARNING, message, error)
    } else {
        log(Level.WARNING, "$message: ${error.javaClass.name}")
    }
}
