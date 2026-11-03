package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

/** The view model [create] makes, kept in this store as a screen keeps it, so [ViewModelStore.clear] is leaving the screen. */
internal inline fun <reified V : ViewModel> ViewModelStore.own(noinline create: () -> V): V =
    ViewModelProvider.create(this, viewModelFactory { initializer { create() } })[V::class]
