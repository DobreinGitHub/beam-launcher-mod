package com.home.tiles

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun valueIsNullUntilTheFirstReadComesBack() {
        val hardware = AtomicInteger(3)
        val state = FirmwareState({ hardware.get() }, scope)
        assertNull(state.value)
        assertFalse(state.loaded)
        state.refresh()
        waitFor("the first read") { state.loaded }
        assertEquals(3, state.value)
    }

    @Test
    fun aValueTheFirmwareDoesNotHaveStaysNullButIsLoaded() {
        val state = FirmwareState<Int>({ null }, scope)
        state.refresh()
        waitFor("the first read") { state.loaded }
        assertNull(state.value)
    }

    @Test
    fun aChangeShowsAtOnceThenWritesAndReadsBack() {
        val hardware = AtomicInteger(1)
        val written = CountDownLatch(1)
        val mayWrite = CountDownLatch(1)
        val state = FirmwareState({ hardware.get() }, scope)
        state.change(5) {
            mayWrite.await(5, TimeUnit.SECONDS) // held back until the check below is done
            hardware.set(4) // the firmware ends up with something else than asked for
            written.countDown()
        }
        assertEquals(5, state.value) // before the write has even run
        mayWrite.countDown()
        assertTrue(written.await(5, TimeUnit.SECONDS))
        waitFor("the read back") { state.value == 4 }
    }

    @Test
    fun anOlderReadBackDoesNotUndoANewerChange() {
        val hardware = AtomicInteger(0)
        val firstWriting = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)
        val state = FirmwareState({ hardware.get() }, scope)
        state.change(1) {
            firstWriting.countDown()
            letFirstFinish.await(5, TimeUnit.SECONDS)
            hardware.set(1)
        }
        assertTrue(firstWriting.await(5, TimeUnit.SECONDS))
        state.change(2) { hardware.set(2) } // while the first write is still going
        letFirstFinish.countDown()
        waitFor("the last write") { hardware.get() == 2 }
        waitFor("the last read back") { state.value == 2 }
    }
}
