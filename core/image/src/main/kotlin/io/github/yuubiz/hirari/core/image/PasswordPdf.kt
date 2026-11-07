package io.github.yuubiz.hirari.core.image

import android.os.storage.StorageManager
import io.github.yuubiz.hirari.core.model.BookPassword
import io.github.yuubiz.hirari.core.source.ByteSource

/** A failed renderer closes its descriptor. Keep the input available for a new password attempt. */
internal suspend fun openPasswordPdf(source: ByteSource, storage: StorageManager, password: BookPassword?): PdfDocument.Opened {
    var transferred = false
    var secret: CharArray? = null
    try {
        secret = password?.initial()
        while (true) {
            val ownsInput = java.util.concurrent.atomic.AtomicBoolean(false)
            val attempt = object : ByteSource by source {
                override fun close() { if (ownsInput.get()) source.close() }
            }
            val result = PdfDocument.open(attempt, storage, secret)
            val wrong = secret != null
            secret?.fill('\u0000')
            secret = null
            when {
                result is PdfDocument.Opened.Document -> {
                    try { password?.accepted() }
                    catch (e: Throwable) { result.document.close(); throw e }
                    ownsInput.set(true)
                    transferred = true
                    return result
                }
                result is PdfDocument.Opened.Failed && result.reason == BookFailure.PasswordRequired -> {
                    secret = password?.retry(wrong) ?: return result
                }
                else -> return result
            }
        }
    } finally {
        secret?.fill('\u0000')
        if (!transferred) { password?.discard(); source.close() }
    }
}
