package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRateTest {
    @Test
    fun rememberedRateShowsAtOnce() {
        assertEquals(100_000L, blendRate(100_000L, null, 0L))
        assertNull(blendRate(null, null, 0L))
    }

    @Test
    fun liveRateTakesOverGradually() {
        // Halfway through the 15 minutes: halfway between the two.
        assertEquals(150_000L, blendRate(100_000L, 200_000L, 7 * 60_000L + 30_000L))
        assertEquals(200_000L, blendRate(100_000L, 200_000L, 20 * 60_000L))
        assertEquals(200_000L, blendRate(null, 200_000L, 3 * 60_000L))
    }

    @Test
    fun learningMovesAThirdOfTheWay() {
        assertEquals(90_000L, learnRate(null, 90_000L))
        assertEquals(110_000L, learnRate(120_000L, 90_000L))
    }

    @Test
    fun mismeasuresAreKeptInRange() {
        assertEquals(240_000L, plausibleRate(372_000L))
        assertEquals(50_000L, plausibleRate(10_000L))
        assertEquals(112_000L, plausibleRate(112_000L))
    }
}
