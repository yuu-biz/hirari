package io.github.yuubiz.hirari

import org.junit.Assert.assertEquals
import org.junit.Test

class MainThreadIoTest {
    private fun stack(vararg classes: String) = classes.map { StackTraceElement(it, "m", null, 0) }

    private val appClasses = setOf("io.github.yuubiz.hirari.MainActivity", "androidx.datastore.core.SingleProcessDataStore")
    private val isApp = { name: String -> name in appClasses }

    @Test
    fun appCodeReachedThroughAospFramesIsTheAppsFault() {
        val frames = stack(
            "libcore.io.BlockGuardOs", "java.io.FileInputStream", "android.app.SharedPreferencesImpl",
            "io.github.yuubiz.hirari.MainActivity", "android.app.Activity", "android.app.ActivityThread",
        )
        assertEquals(IoCulprit.App, blame(frames, isApp))
    }

    @Test
    fun bundledLibraryCodeIsTheAppsFault() {
        val frames = stack("libcore.io.BlockGuardOs", "java.io.File", "androidx.datastore.core.SingleProcessDataStore")
        assertEquals(IoCulprit.App, blame(frames, isApp))
    }

    @Test
    fun deviceMakerFrameworkOnTheWayIsThePlatformsFault() {
        val frames = stack(
            "libcore.io.BlockGuardOs", "java.io.FileInputStream", "miui.util.FeatureParser", "android.app.Activity",
            "io.github.yuubiz.hirari.MainActivity",
        )
        assertEquals(IoCulprit.Platform, blame(frames, isApp))
    }

    @Test
    fun stackWithoutAppFramesIsThePlatformsFault() {
        val frames = stack("libcore.io.BlockGuardOs", "android.app.ActivityThread", "com.android.internal.os.ZygoteInit")
        assertEquals(IoCulprit.Platform, blame(frames, isApp))
    }

    @Test
    fun onlyTheEmulatorStopsTheProcess() {
        assertEquals(true, stopsOnAppIo("ranchu"))
        assertEquals(false, stopsOnAppIo("qcom"))
    }
}
