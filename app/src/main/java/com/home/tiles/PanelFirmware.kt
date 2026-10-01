package com.home.tiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A value that lives in the firmware and is shown on a panel page. XGIMI's services are binder
 * calls that can block, and the panel is drawn over a playing video, so nothing is read or written
 * on the main thread:
 *  - [value] is null until the first read has come back, and stays null if the firmware has no such
 *    thing (not an XGIMI), so a page can simply leave the control out;
 *  - [change] shows the new value at once, then writes and reads back through [PanelIo], in order
 *    with the panel's other writes (the last of several quick changes wins).
 */
internal class FirmwareState<T : Any>(private val read: () -> T?, private val scope: CoroutineScope) {
    var value by mutableStateOf<T?>(null)
        private set

    /** True once the first read has come back, with a value or without one. */
    var loaded by mutableStateOf(false)
        private set

    private val key = "fw-${counter.incrementAndGet()}"
    private var recheck: Job? = null

    /** Bumped by every change, so a read that was already under way doesn't undo it. */
    @Volatile
    private var version = 0

    fun refresh() {
        val started = version
        scope.launch(Dispatchers.IO) {
            val fresh = read()
            if (version == started) value = fresh
            loaded = true
        }
    }

    /**
     * Shows [next] now and runs [write]. Then the firmware's own value is read back: at once, or
     * after [settleMs] when it applies the change asynchronously (sound output).
     */
    fun change(next: T, settleMs: Long = 0, write: () -> Unit) {
        val mine = ++version
        value = next
        PanelIo.submit(key) {
            write()
            // Only if nothing was changed since: a newer choice, already shown, isn't to jump back.
            if (settleMs == 0L) read()?.let { if (version == mine) value = it }
        }
        if (settleMs > 0) {
            recheck?.cancel()
            recheck = scope.launch {
                delay(settleMs)
                // Queued behind the write, which is why it goes through PanelIo too.
                PanelIo.submit("$key-read") { read()?.let { if (version == mine) value = it } }
            }
        }
    }

    private companion object {
        val counter = AtomicInteger()
    }
}

/** A [FirmwareState] for this composition, read when the page opens. */
@Composable
internal fun <T : Any> rememberFirmwareState(read: () -> T?): FirmwareState<T> {
    val scope = rememberCoroutineScope()
    val state = remember { FirmwareState(read, scope) }
    LaunchedEffect(state) { state.refresh() }
    return state
}
