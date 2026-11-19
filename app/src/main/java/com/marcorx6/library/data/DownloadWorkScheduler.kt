package com.marcorx6.library.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Starts only as many transfers as the user allows. Slot reservation is performed
 * in SQLite so parallel enqueue calls cannot overbook the queue.
 */
internal object DownloadWorkScheduler {
    private val lock = Any()

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            scheduleLocked(appContext, ExistingWorkPolicy.KEEP)
        }
    }

    /**
     * Repairs queue rows left in STATUS_SCHEDULED if Android stopped the process
     * after SQLite reserved a slot but before WorkManager began the worker.
     * Re-enqueueing with KEEP preserves any still-running unique work; if the old
     * work already finished or disappeared, a fresh worker is created.
     */
    fun recoverAndSchedule(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            DownloadDatabase.get(appContext).releaseScheduled()
            scheduleLocked(appContext, ExistingWorkPolicy.KEEP)
        }
    }

    /**
     * Rebuilds queued work after the user's network preference changes.
     * WorkManager has no Wi-Fi-only transport constraint, so workers use CONNECTED
     * and verify the active transport before opening or writing the destination.
     */
    fun rescheduleForNetworkChange(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val database = DownloadDatabase.get(appContext)
            val manager = WorkManager.getInstance(appContext)
            database.releaseScheduled().forEach { id ->
                manager.cancelUniqueWork(workName(id))
            }
            scheduleLocked(appContext, ExistingWorkPolicy.REPLACE)
        }
    }

    private fun scheduleLocked(context: Context, policy: ExistingWorkPolicy) {
        val database = DownloadDatabase.get(context)
        val limit = DownloadConcurrencyPreferences(context).maxConcurrent()
        val ids = database.claimPending(limit)
        if (ids.isEmpty()) return
        val manager = WorkManager.getInstance(context)
        ids.forEach { id ->
            val request = OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
                .setInputData(workDataOf(ResumableDownloadWorker.KEY_ID to id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            try {
                manager.enqueueUniqueWork(workName(id), policy, request)
            } catch (error: Exception) {
                database.status(
                    id,
                    android.app.DownloadManager.STATUS_PENDING,
                    error.message ?: "Could not schedule download"
                )
            }
        }
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
    }

    fun workName(id: Long): String = "marco_rx6_download_$id"
}
