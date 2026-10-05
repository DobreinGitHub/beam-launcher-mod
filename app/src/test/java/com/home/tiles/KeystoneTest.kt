package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoneTest {
    private val fullFrame = listOf(0, 0, 1919, 0, 0, 1079, 1919, 1079) // TL, TR, BL, BR

    @Test
    fun fullFrameIsValid() {
        assertTrue(Keystone.isValid(fullFrame))
    }

    @Test
    fun aTrapezoidIsValid() {
        assertTrue(Keystone.isValid(listOf(100, 50, 1800, 20, 0, 1079, 1919, 1000)))
    }

    @Test
    fun crossedSidesAreInvalid() {
        // BL and BR swapped: the quadrilateral crosses itself.
        assertFalse(Keystone.isValid(listOf(0, 0, 1919, 0, 1919, 1079, 0, 1079)))
    }

    @Test
    fun collapsedPictureIsInvalid() {
        assertFalse(Keystone.isValid(listOf(500, 500, 500, 500, 500, 500, 500, 500)))
        // Four corners on one line.
        assertFalse(Keystone.isValid(listOf(0, 0, 100, 100, 200, 200, 300, 300)))
    }

    @Test
    fun wrongNumberOfValuesIsInvalid() {
        assertFalse(Keystone.isValid(fullFrame.dropLast(1)))
        assertFalse(Keystone.isValid(emptyList()))
    }

    @Test
    fun clampKeepsCoordinatesOnTheChip() {
        val clamped = Keystone.clamp(listOf(-5, -5, 3000, 2000, 10, 20, 1919, 1079))
        assertEquals(listOf(0, 0, 1919, 1079, 10, 20, 1919, 1079), clamped)
    }

    @Test
    fun sizeShrinksToHalfAtTheLastStep() {
        assertEquals(100, Keystone.sizePercent(0))
        assertEquals(75, Keystone.sizePercent(Keystone.MAX_ZOOM / 2))
        assertEquals(Keystone.MIN_SIZE_PERCENT, Keystone.sizePercent(Keystone.MAX_ZOOM))
        // Out-of-range steps are kept inside the range.
        assertEquals(100, Keystone.sizePercent(-3))
        assertEquals(Keystone.MIN_SIZE_PERCENT, Keystone.sizePercent(Keystone.MAX_ZOOM + 40))
    }
}
