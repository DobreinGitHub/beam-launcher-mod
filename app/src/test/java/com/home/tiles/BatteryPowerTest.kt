package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryPowerTest {
    @Test
    fun firmwareTypeTwoIsExternalPowerNotStandEvenWithCachedStandLevel() {
        assertEquals(StandState.NONE, standState(2))
        assertEquals(StandState.POWERING, standState(1))
        assertEquals(StandState.CHARGING, standState(4))
        assertEquals(StandState.NONE, standState(0))
        assertEquals(StandState.NONE, standState(3))
        assertEquals(PowerSource.PLUGGED_CHECKING, powerSource(true, standState(2), false, 58, 0))
        assertEquals(PowerSource.BATTERY, powerSource(false, standState(2), false, 58, 0))
    }

    @Test
    fun pdChargeIsConfirmedByNextIncreaseAndReversedByNewDecrease() {
        val trend = PowerTrend()
        val stand = standState(2)
        assertEquals(PowerSource.PLUGGED_CHECKING, powerSource(true, stand, false, 58, trend.update(true, stand, 2, 58)))
        assertEquals(PowerSource.CHARGING, powerSource(true, stand, false, 59, trend.update(true, stand, 2, 59)))
        assertEquals(PowerSource.CHARGING, powerSource(true, stand, false, 59, trend.update(true, stand, 2, 59)))
        assertEquals(PowerSource.PLUGGED_DRAINING, powerSource(true, stand, false, 58, trend.update(true, stand, 2, 58)))
        assertEquals(PowerSource.FULL, powerSource(true, stand, true, 100, trend.update(true, stand, 2, 100)))
    }

    @Test
    fun sourceChangeClearsOldChargingEvidenceAndStandBehavesAsBefore() {
        val trend = PowerTrend()
        trend.update(true, StandState.NONE, 2, 58)
        trend.update(true, StandState.NONE, 2, 59)
        assertEquals(0, trend.update(false, StandState.NONE, 3, 59))
        assertEquals(PowerSource.BATTERY, powerSource(false, StandState.NONE, false, 59, trend.direction))
        assertEquals(0, trend.update(true, StandState.NONE, 2, 59))
        assertEquals(PowerSource.PLUGGED_CHECKING, powerSource(true, StandState.NONE, false, 59, trend.direction))
        assertEquals(PowerSource.STAND, powerSource(true, StandState.POWERING, false, 59, trend.update(true, StandState.POWERING, 1, 59)))
        assertEquals(PowerSource.FULL, powerSource(true, StandState.CHARGING, true, 100, trend.update(true, StandState.CHARGING, 4, 100)))
    }

    @Test
    fun switchingFromStandardAdapterToPdResetsOldChargingEvidence() {
        val trend = PowerTrend()
        trend.update(true, StandState.NONE, 0, 58)
        assertEquals(1, trend.update(true, StandState.NONE, 0, 59))
        assertEquals(0, trend.update(true, StandState.NONE, 2, 59))
        assertEquals(PowerSource.PLUGGED_CHECKING, powerSource(true, StandState.NONE, false, 59, trend.direction))
        assertEquals(1, trend.update(true, StandState.NONE, 2, 60))
        assertEquals(PowerSource.CHARGING, powerSource(true, StandState.NONE, false, 60, trend.direction))
    }
}
