package com.marcorx6.library.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import com.marcorx6.library.data.auth.FirebasePushTokenRepository

/**
 * Stores the opt-in preference and syncs it to the Cloudflare push service.
 * Runtime permission prompts and settings UI remain in MainActivity.
 */
internal class BlogNotificationScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(appContext)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        FirebasePushTokenRepository(appContext).syncCurrentToken()
    }

    fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BlogNotificationWorker.CHANNEL_ID,
                "New MARCO posts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when a new post is published on the MARCO blog"
            }
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // Cloudflare polls Blogger and delivers FCM; cancel the legacy on-device poller.
    fun schedule() {
        workManager.cancelUniqueWork(BlogNotificationWorker.WORK_NAME)
        FirebasePushTokenRepository(appContext).syncCurrentToken()
    }

    fun cancel() {
        workManager.cancelUniqueWork(BlogNotificationWorker.WORK_NAME)
        FirebasePushTokenRepository(appContext).syncCurrentToken()
    }

    private companion object {
        const val PREFERENCES_NAME = "notification_settings"
        const val KEY_ENABLED = "enabled"
    }
}
