package io.github.yuubiz.hirari.tools.fuzz

import java.io.File
import java.util.concurrent.TimeUnit

/** What the fuzz and monkey drivers need from a device. */
interface Adb {
    /** Runs `adb shell` with [command] and returns its output. [timeoutSeconds] bounds the wait. */
    fun shell(command: String, timeoutSeconds: Long = 600): String

    fun push(local: File, remote: String)

    /** Copies [remote] to [local]. Returns false when the device has no such file. */
    fun pull(remote: String, local: File): Boolean

    fun install(apk: File)

    /** The text of the logcat [buffers], as they stand. */
    fun logcat(vararg buffers: String): String

    fun clearLogcat()
}

/** [Adb] over the `adb` program of the Android SDK, aimed at [serial] when it is given. */
class ProcessAdb(private val adb: String, private val serial: String?) : Adb {
    private fun run(timeoutSeconds: Long, vararg args: String): Result {
        val command = listOf(adb) + (if (serial != null) listOf("-s", serial) else emptyList()) + args
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["MSYS_NO_PATHCONV"] = "1"
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { output.appendLine(it) } }
        reader.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2000)
            return Result(-1, "$output\n[timed out after $timeoutSeconds s]")
        }
        reader.join()
        return Result(process.exitValue(), output.toString())
    }

    private class Result(val exit: Int, val text: String)

    override fun shell(command: String, timeoutSeconds: Long): String = run(timeoutSeconds, "shell", command).text

    override fun push(local: File, remote: String) {
        val result = run(300, "push", local.path, remote)
        check(result.exit == 0) { "adb push ${local.name} failed: ${result.text}" }
    }

    override fun pull(remote: String, local: File): Boolean {
        local.absoluteFile.parentFile.mkdirs()
        return run(300, "pull", remote, local.path).exit == 0
    }

    override fun install(apk: File) {
        val result = run(300, "install", "-r", "-t", apk.path)
        check(result.exit == 0 && "Success" in result.text) { "adb install ${apk.name} failed: ${result.text}" }
    }

    override fun logcat(vararg buffers: String): String =
        run(120, *(listOf("logcat", "-d", "-v", "threadtime") + buffers.flatMap { listOf("-b", it) }).toTypedArray()).text

    override fun clearLogcat() {
        run(60, "logcat", "-b", "all", "-c")
    }

    companion object {
        /** `adb` under `ANDROID_HOME` or `ANDROID_SDK_ROOT`, else the one on the path. */
        fun locate(serial: String?): ProcessAdb {
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            val names = listOf("adb.exe", "adb")
            val found = sdk?.let { root -> names.map { File(root, "platform-tools/$it") }.firstOrNull(File::isFile) }
            return ProcessAdb(found?.path ?: "adb", serial)
        }
    }
}
