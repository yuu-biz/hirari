package io.github.yuubiz.hirari.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The page panel's number field (docs/spec.md section 4.8, ページバー). What is typed stays as typed until it is
 * confirmed, and only then is it read as a page.
 */
internal class PageNumberField(head: Int) {
    var text: String by mutableStateOf("${head + 1}")
        private set

    fun edit(typed: String) {
        text = typed
    }

    /** Shows [head] in place of what was typed. */
    fun show(head: Int) {
        text = "${head + 1}"
    }

    /** The page the text means, or `null` to stay put. Either way the field shows the page the reader is on again. */
    fun confirm(pageCount: Int, head: Int): Int? = PagePanel.typed(text, pageCount).also { show(head) }
}
