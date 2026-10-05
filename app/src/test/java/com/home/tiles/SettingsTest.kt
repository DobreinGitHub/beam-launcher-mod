package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun screensaverDelaysAscendAndNeverIsLast() {
        val delays = ScreensaverTimeout.options.map { it.first }
        assertEquals(delays.sorted(), delays)
        assertEquals(Int.MAX_VALUE, delays.last())
        assertTrue(delays.size > 1)
    }
}
