package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRowsTest {
    private fun item(key: String, aspect: Int?) = TvItem(key, "app", key, "", null, null, aspect)

    @Test
    fun rowTakesTheShapeMostCardsDeclare() {
        val row = TvChannel("app|a", "a", listOf(item("1", 5), item("2", 5), item("3", 0), item("4", null)))
        assertEquals(5, row.aspect)
        assertNull(TvChannel("app|b", "b", listOf(item("1", null))).aspect)
    }

    @Test
    fun postersStandTallerThanWideCards() {
        val wide = cardSize(null)
        val poster = cardSize(5)
        assertEquals(117f, wide.height.value, 0.01f)
        assertEquals(208f, wide.width.value, 0.01f)
        assertEquals(176f, poster.height.value, 0.01f)
        assertEquals(176f / 1.441f, poster.width.value, 0.01f)
    }

    @Test
    fun progressNeedsPositionAndDuration() {
        assertEquals(0.5f, progressOf(30_000, 60_000)!!, 0.001f)
        assertNull(progressOf(null, 60_000))
        assertNull(progressOf(30_000, 0))
        // Barely started: no bar.
        assertNull(progressOf(500, 60_000))
    }

    @Test
    fun switchingARowOnKeepsTheAutomaticOne() {
        val channels = listOf(
            TvChannel(WATCH_NEXT_KEY, "Продолжить", listOf(item("1", null))),
            TvChannel("x|Популярное", "Популярное", listOf(item("2", 5))),
        )
        assertEquals(listOf(WATCH_NEXT_KEY), shownChannelRows(SECOND_ROW_AUTO, channels))
        assertEquals("$WATCH_NEXT_KEY\nx|Популярное", toggleChannelRow(SECOND_ROW_AUTO, "x|Популярное", channels))
        // The automatic row switched off: nothing left.
        assertEquals(SECOND_ROW_OFF, toggleChannelRow(SECOND_ROW_AUTO, WATCH_NEXT_KEY, channels))
    }
}
