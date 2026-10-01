package com.home.tiles

import android.util.Log
import java.util.concurrent.Executors

/**
 * Hardware writes from the panel (XGIMI's services are binder calls that can block) run here, one
 * at a time and in order, off the main thread. A call for the same [submit] key as the one waiting
 * last replaces it, so dragging a slider sends only where it ended up.
 */
internal object PanelIo {
    private class Entry(val key: String, @Volatile var block: () -> Unit)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "panel-io").apply { isDaemon = true } }
    private val queue = ArrayDeque<Entry>()

    fun submit(key: String, block: () -> Unit) {
        val added = synchronized(queue) {
            val last = queue.lastOrNull()
            if (last != null && last.key == key) {
                last.block = block
                false
            } else {
                queue.addLast(Entry(key, block))
                true
            }
        }
        if (added) executor.execute {
            val entry = synchronized(queue) { queue.removeFirst() }
            runCatching(entry.block).onFailure { Log.w("PanelIo", "${entry.key} failed", it) }
        }
    }
}
