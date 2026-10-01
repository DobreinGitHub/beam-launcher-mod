package com.home.tiles

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelIoTest {
    @Test
    fun runsInOrderAndKeepsOnlyTheNewestOfAnAdjacentSameKey() {
        val ran = CopyOnWriteArrayList<Int>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Holds the queue's single thread, so what follows is queued up behind it.
        PanelIo.submit("gate") {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        PanelIo.submit("x") { ran += 1 }
        PanelIo.submit("x") { ran += 2 } // replaces the first: same key, still waiting, last in line
        PanelIo.submit("y") { ran += 3 }
        PanelIo.submit("x") { ran += 4 } // not adjacent to the earlier x: a separate entry
        val done = CountDownLatch(1)
        PanelIo.submit("done") { done.countDown() }

        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(2, 3, 4), ran.toList())
    }

    @Test
    fun aFailingWriteDoesNotStopTheQueue() {
        val ran = CountDownLatch(1)
        PanelIo.submit("bad") { error("refused") }
        PanelIo.submit("good") { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }
}
