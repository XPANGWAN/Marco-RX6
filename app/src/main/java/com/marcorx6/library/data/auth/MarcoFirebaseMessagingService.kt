package com.marcorx6.library.data.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.marcorx6.library.ui.LanguageTranslator
import com.marcorx6.library.ui.activity.MainActivity
import com.marcorx6.library.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives FCM data messages and displays them as Android notifications. */
class MarcoFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebasePushTokenRepository().saveToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = data["title"]?.take(80)
            ?: message.notification?.title?.take(80)
            ?: "MARCO"
        val body = data["body"]?.take(180)
            ?: message.notification?.body?.take(180)
            ?: return
        val language = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("language", "ar") ?: "ar"
        val localizedTitle = LanguageTranslator.translate(title, language)
        val localizedBody = LanguageTranslator.translate(body, language)

        val channelId = "marco_social_updates"
        ensureChannel(channelId)
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data.forEach { (key, value) -> putExtra("push_$key", value) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_marco)
            .setContentTitle(localizedTitle)
            .setContentText(localizedBody)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Keep notifications compact: text and the app small icon only.
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(localizedBody))
        val notification = builder.build()
        // Android 13+ can revoke notification permission at runtime.
        // Do not attempt to post when permission is unavailable.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            NotificationManagerCompat.from(this).notify(
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                notification
            )
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and notify call.
        }
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                LanguageTranslator.translate(
                    "إشعارات MARCO",
                    getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("language", "ar") ?: "ar"
                ),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = LanguageTranslator.translate(
                    "رسائل ومتابعون وتحديثات الألعاب",
                    getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("language", "ar") ?: "ar"
                )
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
