package io.github.yuubiz.hirari.benchmark

import androidx.test.uiautomator.By
import java.util.regex.Pattern

/** The pages of the view on screen, from 1, out of [total]. */
internal data class ViewSpan(val first: Int, val last: Int, val total: Int) {
    val atStart: Boolean get() = first == 1
    val atEnd: Boolean get() = last == total
}

/**
 * Reads the view on screen from what the reader tells a screen reader of it (docs/spec.md section 6.7): the
 * strings `a11y_pages_of` for a pair and `a11y_page_of` for one page, whose numbers are in the order of [ViewSpan].
 */
internal class ViewSpanReader(private val driver: Driver) {
    private val formats = listOf("a11y_pages_of", "a11y_page_of").map { driver.appString(it) }
    private val patterns = formats.map { format ->
        Pattern.compile(format.split(Regex("%\\d[$]d")).joinToString("""(\d+)""") { Pattern.quote(it) })
    }
    private val selector = By.pkg(PACKAGE).desc(Pattern.compile(patterns.joinToString("|") { "(?:${it.pattern()})" }))

    fun read(): ViewSpan {
        val description = driver.device.findObject(selector)?.contentDescription ?: driver.fail("no view of the reader is on screen")
        for (pattern in patterns) {
            val match = pattern.matcher(description)
            if (!match.matches()) continue
            val numbers = (1..match.groupCount()).map { match.group(it).toInt() }
            return if (numbers.size == 3) ViewSpan(numbers[0], numbers[1], numbers[2]) else ViewSpan(numbers[0], numbers[0], numbers[1])
        }
        driver.fail("the reader's view reads '$description'")
    }
}
