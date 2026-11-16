package com.marcorx6.library.data

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Cross-process, per-download writer lease.
 *
 * WorkManager unique work is the first line of defence, but cancellation and
 * immediate resume can briefly overlap old and replacement workers. The OS file
 * lock is the final authority: only one worker may touch a download's destination
 * and checkpoints at a time, including across process recreation.
 */
internal object DownloadExecutionLock {
    internal class Lease(
        private val file: RandomAccessFile,
        private val lock: FileLock
    ) : Closeable {
        override fun close() {
            try {
                if (lock.isValid) lock.release()
            } finally {
                file.close()
            }
        }
    }

    fun acquire(context: Context, downloadId: Long): Lease? {
        require(downloadId > 0L)
        val directory = File(context.applicationContext.filesDir, "MARCO_RX6_downloads")
        if (!directory.exists() && !directory.mkdirs()) return null
        val lockFile = File(directory, "$downloadId.writer.lock")
        val handle = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull() ?: return null
        val lock = try {
            handle.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        }
        if (lock == null) {
            runCatching { handle.close() }
            return null
        }
        return Lease(handle, lock)
    }
}
