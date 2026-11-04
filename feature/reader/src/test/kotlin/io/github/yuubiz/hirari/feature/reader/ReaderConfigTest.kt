package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReaderConfigTest {
    @Test
    fun theReaderStartsFromTheDefaultsOfTheGlobalSettings() {
        assertEquals(ReaderConfig.of(GlobalSettings()), ReaderConfig())
    }

    @Test
    fun tapsStepThroughStopsOnlyWhenTheSettingSaysSo() {
        assertFalse(ReaderConfig.of(GlobalSettings(tapAdvancesStops = false)).tapAdvancesStops)
        assertEquals(true, ReaderConfig.of(GlobalSettings(tapAdvancesStops = true)).tapAdvancesStops)
    }
}
