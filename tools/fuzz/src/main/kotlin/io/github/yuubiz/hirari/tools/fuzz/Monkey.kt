package io.github.yuubiz.hirari.tools.fuzz

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Usage: ./gradlew :tools:fuzz:monkey --args="[options]"
  --events N       monkey events over all books (default 10000), shared evenly between the books
  --seed N         monkey seed of the first book; the next book adds one (default 1)
  --throttle MS    pause between events (default 30)
  --books a,b      books, relative to --corpus (default one standard book and the error books, see DEFAULT_BOOKS)
  --corpus DIR     the output of :tools:corpus:generateCorpus (default tools/corpus/build/corpus)
  --standard FILE  the standard corpus, tools/corpus/build/standard-corpus/standard.zip, to read as well
  --apk FILE       the debug APK (default the one :app:assembleDebug builds)
  --out DIR        logs (default tools/fuzz/build/monkey)
  --serial S       the device, when more than one is attached
"""

private val KNOWN = setOf("events", "seed", "throttle", "books", "corpus", "standard", "apk", "out", "serial")

/** `zip/images.zip` stands for the standard corpus, and the rest are error books of every container. */
private const val DEFAULT_BOOKS =
    "zip/images.zip,zip/truncated-second-data.zip,zip/corrupt.zip,zip/encrypted.zip,rar5/stored.rar,7z/methods.7z,tar/compressed.txz,pdf/mixed-sizes.pdf,pdf/broken.pdf"

internal const val APP = "io.github.yuubiz.hirari"

/**
 * The monkey test of docs/spec.md section 6.2: for each book, opens it in the reader of the debug build through the
 * debug route (`--es book`), then lets `monkey` act on the app with a fixed seed, and counts crashes and ANRs from its
 * output and the logcat.
 */
fun main(args: Array<String>) {
    val arguments = Arguments(args)
    if (arguments.flag("help") || arguments.unknown(KNOWN + "help").isNotEmpty()) {
        println(USAGE)
        exitProcess(if (arguments.flag("help")) 0 else 2)
    }
    val adb = ProcessAdb.locate(arguments.string("serial"))
    val apk = File(arguments.string("apk", "app/build/intermediates/apk/debug/app-debug.apk"))
    check(apk.isFile) { "no debug APK at ${apk.path}; run ./gradlew :app:assembleDebug" }
    val corpus = File(arguments.string("corpus", "tools/corpus/build/corpus"))
    val books = arguments.string("books", DEFAULT_BOOKS).split(',').map { corpus.resolve(it) }.toMutableList()
    arguments.string("standard")?.let { books += File(it) }
    books.firstOrNull { !it.isFile }?.let { error("no book at ${it.path}") }
    val out = File(arguments.string("out", "tools/fuzz/build/monkey")).absoluteFile.apply { mkdirs() }
    val totalEvents = arguments.int("events", 10_000)
    val seed = arguments.long("seed", 1)
    val throttle = arguments.int("throttle", 30)

    adb.install(apk)
    // A file that the shell wrote into the app's external directory is not readable by the app, so the books go into
    // the app's own cache through run-as, which the debug build allows.
    val dir = "/data/data/$APP/cache/monkey"
    adb.shell("run-as $APP sh -c 'rm -rf $dir; mkdir -p $dir'")
    var total = MonkeyCounts.None
    val summary = StringBuilder()
    for ((i, book) in books.withIndex()) {
        val staged = "/data/local/tmp/hirari-monkey-${book.name}"
        adb.push(book, staged)
        adb.shell("cat $staged | run-as $APP sh -c 'cat > $dir/${book.name}'; rm $staged")
        val copied = adb.shell("run-as $APP stat -c %s $dir/${book.name}").trim()
        check(copied == book.length().toString()) { "the copy of ${book.name} in the app has $copied bytes, not ${book.length()}" }
        val events = totalEvents / books.size + if (i < totalEvents % books.size) 1 else 0
        val counts = session(adb, book, "$dir/${book.name}", events, seed + i, throttle, out)
        total += counts
        val line = "${book.name}: seed ${seed + i}, ${counts.injected} of $events events, ${counts.crashes} crashes, ${counts.nativeCrashes} native crashes, ${counts.anrs} ANRs" +
            (if (counts.aborted) ", monkey aborted" else "") + (if (counts.abnormal > 0) ", ${counts.abnormal} sessions did not end normally" else "")
        println(line)
        summary.appendLine(line)
    }
    val line = "total: ${total.injected} events, ${total.crashes} crashes, ${total.nativeCrashes} native crashes, ${total.anrs} ANRs" +
        (if (total.abnormal > 0) ", ${total.abnormal} sessions did not end normally" else "")
    println(line)
    summary.appendLine(line)
    File(out, "summary.txt").writeText(summary.toString())
    exitProcess(if (total.failed) 1 else 0)
}

/**
 * Monkey's taps and swipes leave the reader sooner or later (a tap on the close control, for one), after which the
 * rest of its events would land on the home screen. So a book gets [CHUNK] events at a time, and the reader is
 * opened on it again before each chunk.
 */
private fun session(adb: Adb, book: File, remote: String, events: Int, seed: Long, throttle: Int, out: File): MonkeyCounts {
    var total = MonkeyCounts.None
    var round = 0
    while (total.injected < events && total.abnormal == 0) {
        total += chunk(adb, book, remote, minOf(CHUNK, events - total.injected), seed * 1000 + round, throttle, out, round)
        round++
    }
    return total
}

private const val CHUNK = 250

private fun chunk(adb: Adb, book: File, remote: String, events: Int, seed: Long, throttle: Int, out: File, index: Int): MonkeyCounts {
    adb.shell("am force-stop $APP")
    adb.clearLogcat()
    adb.shell("am start -n $APP/.MainActivity --es book file://$remote")
    Thread.sleep(5000)
    val percentages = "--pct-touch 45 --pct-motion 25 --pct-pinchzoom 10 --pct-nav 5 --pct-majornav 0 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --pct-trackball 0"
    val end = adb.shellResult(
        "monkey -p $APP -s $seed --throttle $throttle --ignore-crashes --ignore-timeouts --ignore-security-exceptions --ignore-native-crashes --monitor-native-crashes " +
            "$percentages -v $events",
        timeoutSeconds = events.toLong() * (throttle + 400) / 1000 + 300,
    )
    val logcat = adb.logcat("crash", "main", "system")
    val observed = MonkeyCounts.of(APP, end.output, logcat)
    val problem = observed.abnormality(events, end)
    val counts = if (problem == null) observed else observed.copy(abnormal = 1)
    if (counts.failed) {
        File(out, "${book.name}.$index.monkey.txt").writeText((problem?.let { "$it\n" } ?: "") + end.output)
        File(out, "${book.name}.$index.logcat.txt").writeText(logcat)
    }
    if (problem != null) println("${book.name}, round $index: $problem")
    return counts
}
