package com.manuel.ours.data.sync

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic logical clock. Thread-safe because SMS parsing (background), the UI, and
 * the sync worker all mint events concurrently.
 */
class LamportClock(initial: Long = 0L) {

    private val counter = AtomicLong(initial)

    val current: Long get() = counter.get()

    /** Call when creating a local event. */
    fun tick(): Long = counter.incrementAndGet()

    /** Call on receiving remote events, before minting any local ones. */
    fun observe(remote: Long): Long {
        while (true) {
            val local = counter.get()
            if (remote <= local) return local
            if (counter.compareAndSet(local, remote)) return remote
        }
    }

    fun observeAll(events: Iterable<SyncEvent>) {
        val max = events.maxOfOrNull { it.lamport } ?: return
        observe(max)
    }
}
