package io.github.yuubiz.hirari.tools.fuzz

import java.io.File
import java.util.concurrent.TimeUnit

/** How an `adb` call ended: its output, its exit status, and whether it was killed (exit -1) for running past its time. */
data class AdbResult(val output: String, val exit: Int, val timedOut: Boolean)

/** What the fuzz and monkey drivers need from a device. */
interface Adb {
    /** Runs `adb shell` with [command]. [timeoutSeconds] bounds the wait. */
    fun shellResult(command: String, timeoutSeconds: Long = 600): AdbResult

    /** The output of [shellResult], for a caller that does not need to know how the command ended. */
    fun shell(command: String, timeoutSeconds: Long = 600): String = shellResult(command, timeoutSeconds).output

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
    private fun run(timeoutSeconds: Long, vararg args: String): AdbResult {
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
            return AdbResult("$output\n[timed out after $timeoutSeconds s]", -1, timedOut = true)
        }
        reader.join()
        return AdbResult(output.toString(), process.exitValue(), timedOut = false)
    }

    override fun shellResult(command: String, timeoutSeconds: Long): AdbResult = run(timeoutSeconds, "shell", command)

    override fun push(local: File, remote: String) {
        val result = run(300, "push", local.path, remote)
        check(result.exit == 0) { "adb push ${local.name} failed: ${result.output}" }
    }

    override fun pull(remote: String, local: File): Boolean {
        local.absoluteFile.parentFile.mkdirs()
        return run(300, "pull", remote, local.path).exit == 0
    }

    override fun install(apk: File) {
        val result = run(300, "install", "-r", "-t", apk.path)
        check(result.exit == 0 && "Success" in result.output) { "adb install ${apk.name} failed: ${result.output}" }
    }

    override fun logcat(vararg buffers: String): String =
        run(120, *(listOf("logcat", "-d", "-v", "threadtime") + buffers.flatMap { listOf("-b", it) }).toTypedArray()).output

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
