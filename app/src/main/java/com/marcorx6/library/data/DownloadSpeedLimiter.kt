package com.marcorx6.library.data

import java.util.concurrent.TimeUnit

/**
 * Aggregate byte-rate limiter shared by all connections of one download.
 * A zero value disables throttling. Reservations are made under a short lock;
 * waiting happens outside it so one slow segment cannot block every other worker.
 */
internal class DownloadSpeedLimiter(private val bytesPerSecond: Long) {
    private var nextAvailableNanos = 0L

    fun acquire(bytes: Int, shouldStop: () -> Boolean = { false }) {
        if (bytesPerSecond <= 0L || bytes <= 0) return

        val startAt = synchronized(this) {
            val now = System.nanoTime()
            val scheduled = maxOf(now, nextAvailableNanos)
            val duration = (bytes.toLong() * 1_000_000_000L / bytesPerSecond)
                .coerceAtLeast(1L)
            nextAvailableNanos = scheduled + duration
            scheduled
        }

        // Keep pause/cancel responsive even when the configured speed is very low.
        while (true) {
            if (shouldStop() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Download speed wait was interrupted")
            }
            val remaining = startAt - System.nanoTime()
            if (remaining <= 0L) return
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100L)))
        }
    }
}
