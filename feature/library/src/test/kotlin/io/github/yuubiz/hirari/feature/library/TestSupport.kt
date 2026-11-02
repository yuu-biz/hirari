package io.github.yuubiz.hirari.feature.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Keeps [flow] subscribed for the test, as a screen does, starting at once. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.watch(flow: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}
