package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stands in for one of XGIMI's managers: a static getInstance() and a few differently typed methods. */
class FakeManager {
    var lastByte: Byte? = null
    var lastFlag: Boolean? = null

    fun setLevel(level: Byte) { lastByte = level }
    fun setFlag(on: Boolean) { lastFlag = on }
    fun value(): Int = 7
    fun scale(factor: Float): Float = factor * 2
    fun count(a: Int): String = "int:$a"
    fun count(a: String): String = "str:$a"
    fun nothing() {}
    fun boom() { throw IllegalStateException("boom") }

    companion object {
        private val shared = FakeManager()

        @JvmStatic
        fun getInstance(): FakeManager = shared
    }
}

class GmpfTest {
    private val fake = "com.home.tiles.FakeManager"

    @Test
    fun intArgumentIsConvertedToAByteParameter() {
        assertTrue(Gmpf.ok(fake, "setLevel", 5))
        assertEquals(5.toByte(), FakeManager.getInstance().lastByte)
    }

    @Test
    fun numberIsConvertedToABooleanParameter() {
        Gmpf.call(fake, "setFlag", 1)
        assertEquals(true, FakeManager.getInstance().lastFlag)
        Gmpf.call(fake, "setFlag", 0)
        assertEquals(false, FakeManager.getInstance().lastFlag)
        Gmpf.call(fake, "setFlag", true)
        assertEquals(true, FakeManager.getInstance().lastFlag)
    }

    @Test
    fun intArgumentIsConvertedToAFloatParameter() {
        assertEquals(6f, Gmpf.call(fake, "scale", 3).getOrNull() as Float, 0f)
    }

    @Test
    fun resultsAreReadAsIntOrBool() {
        assertEquals(7, Gmpf.int(fake, "value"))
        assertNull(Gmpf.bool(fake, "value"))
    }

    @Test
    fun overloadsAreChosenByArgumentType() {
        assertEquals("int:3", Gmpf.call(fake, "count", 3).getOrNull())
        assertEquals("str:x", Gmpf.call(fake, "count", "x").getOrNull())
    }

    @Test
    fun aVoidMethodIsASuccessHoldingNull() {
        val result = Gmpf.call(fake, "nothing")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun theRealCauseIsUnwrapped() {
        val failure = Gmpf.call(fake, "boom").exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("boom", failure?.message)
    }

    @Test
    fun unknownMethodAndWrongArgumentsFail() {
        assertTrue(Gmpf.call(fake, "missing").exceptionOrNull() is NoSuchMethodException)
        assertTrue(Gmpf.call(fake, "setFlag", "text").exceptionOrNull() is NoSuchMethodException)
        assertTrue(Gmpf.call(fake, "value", 1).exceptionOrNull() is NoSuchMethodException)
    }

    @Test
    fun missingClassFailsAndIsNotAvailable() {
        assertFalse(Gmpf.available("com.example.DoesNotExist"))
        assertTrue(Gmpf.call("com.example.DoesNotExist", "x").exceptionOrNull() is ClassNotFoundException)
        assertFalse(Gmpf.ok("com.example.DoesNotExist", "x"))
    }

    @Test
    fun hardwareHelpersReportFailureWhenThereIsNoXgimiFirmware() {
        assertFalse(Lumens.setLevel(5))
        assertNull(Lumens.level())
        assertFalse(PictureAdjust.set(9, 50)) // not a picture item at all
    }
}
