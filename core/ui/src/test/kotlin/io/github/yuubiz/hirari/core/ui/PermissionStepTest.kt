package io.github.yuubiz.hirari.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionStepTest {
    @Test
    fun aHeldPermissionTurnsTheFeatureOnWithoutAsking() {
        assertEquals(PermissionStep.Granted, permissionStep(granted = true, showRationale = false))
        assertEquals(PermissionStep.Granted, permissionStep(granted = true, showRationale = true))
    }

    @Test
    fun aRequestTurnedDownBeforeIsExplainedFirst() {
        assertEquals(PermissionStep.Explain, permissionStep(granted = false, showRationale = true))
    }

    @Test
    fun theFirstRequestGoesStraightToTheSystem() {
        assertEquals(PermissionStep.Ask, permissionStep(granted = false, showRationale = false))
    }
}
