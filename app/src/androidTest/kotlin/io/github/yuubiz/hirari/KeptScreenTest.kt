package io.github.yuubiz.hirari

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.feature.library.StartPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class Probe : ViewModel()

/**
 * A screen the system kept, under a start that keeps or drops it (docs/spec.md section 4.3, 起動時の動作), on the
 * real Navigation Compose, which keeps a popped screen composed while it leaves.
 */
class KeptScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HirariApp
    private var loads = 0

    @After
    fun forgetTheStart() {
        app.start = null
    }

    /** Puts a kept screen on top of home and lets it show, with its start still waiting for [plan]. */
    private fun keptScreen(): Pair<AppStart, NavHostController> {
        val start = AppStart(restoring = true, CompletableDeferred<StartPlan>(), MainScope()) {}
        app.start = start
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> {}
                composable<LicensesRoute> { entry ->
                    Kept(app, entry) {
                        viewModel {
                            loads++
                            Probe()
                        }
                    }
                }
            }
        }
        compose.runOnIdle { nav.navigate(LicensesRoute) }
        compose.waitForIdle()
        assertEquals("a kept screen loads before the start decides", 0, loads)
        return start to nav
    }

    @Test
    fun aKeptScreenTheStartDropsNeverLoadsWhileItLeaves() {
        val (start, nav) = keptScreen()
        compose.runOnIdle { start.apply(nav, StartPlan.Open()) }
        compose.waitForIdle()
        assertTrue("the start went home", nav.currentDestination!!.hasRoute<HomeRoute>())
        assertEquals("the dropped screen loaded", 0, loads)
    }

    @Test
    fun aKeptScreenTheStartKeepsLoadsOnce() {
        val (start, nav) = keptScreen()
        compose.runOnIdle { start.apply(nav, StartPlan.Restore) }
        compose.waitForIdle()
        assertTrue("the kept screen stayed on top", nav.currentDestination!!.hasRoute<LicensesRoute>())
        assertEquals(1, loads)
    }
}
