package io.github.yuubiz.hirari

/** Who made a main-thread I/O call that StrictMode reported. docs/spec.md section 6.3. */
enum class IoCulprit { App, Platform }

private val aospPackages = listOf(
    "java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.", "android.", "com.android.",
    "org.apache.harmony.", "org.json.", "org.xml.", "org.w3c.", "org.xmlpull.",
)

private fun isAosp(className: String) = aospPackages.any(className::startsWith)

/**
 * Walks [frames] from the I/O call outward. The app is to blame when its own code is reached through AOSP frames
 * only; a device maker's framework frame on the way (`miui.`, `com.samsung.`, ...) means the platform as shipped did
 * the I/O on our behalf, which the app cannot avoid. A stack with no app frame at all is the platform's too.
 */
fun blame(frames: List<StackTraceElement>, isAppClass: (String) -> Boolean): IoCulprit {
    for (frame in frames) {
        val name = frame.className
        if (isAppClass(name)) return IoCulprit.App
        if (!isAosp(name)) return IoCulprit.Platform
    }
    return IoCulprit.Platform
}

private val emulatorHardware = setOf("ranchu", "goldfish")

/**
 * Whether an app violation stops the process on a device of [hardware] (`Build.HARDWARE`): only on the emulator,
 * where the device tests, the fuzz and the monkey run. A maker patches classes inside `android.` too, so on a
 * person's phone [blame] can take the maker's I/O for the app's; there the debug build only logs.
 */
fun stopsOnAppIo(hardware: String): Boolean = hardware in emulatorHardware
