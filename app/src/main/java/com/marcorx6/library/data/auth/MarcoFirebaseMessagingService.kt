package com.marcorx6.library.data.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        FirebasePushTokenRepository(this).saveToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val notificationKey = data["notificationId"]?.takeIf { it.isNotBlank() }
            ?: message.messageId?.takeIf { it.isNotBlank() }
            ?: "${data["type"].orEmpty()}:${data["title"].orEmpty()}:${data["body"].orEmpty()}"
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
        val postUrl = data["postUrl"].orEmpty()
        val openApp = if (data["type"] == "blog_game" && postUrl.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(postUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data.forEach { (key, value) -> putExtra("push_$key", value) }
            }
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

        // Show the game cover in the expanded notification when a safe HTTPS image is available.
        // If image loading fails, keep the normal readable text notification.
        val imageUrl = data["imageUrl"].orEmpty()
        val cover = downloadNotificationImage(imageUrl)
        if (cover != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(cover)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(localizedBody)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(localizedBody))
        }
        val notification = builder.build()
        // Android 13+ can revoke notification permission at runtime.
        // Do not attempt to post when permission is unavailable.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        notifyOnce(notificationKey, notification)
    }

    /**
     * FCM may retry a message when delivery acknowledgement or the server status
     * update is delayed. A stable ID updates the same Android notification, while
     * this bounded local ledger prevents a dismissed notification from reappearing.
     */
    private fun notifyOnce(key: String, notification: android.app.Notification) {
        synchronized(notificationLock) {
            val preferences = getSharedPreferences("marco_push_dedupe", Context.MODE_PRIVATE)
            val delivered = preferences.getStringSet("delivered_ids", emptySet()).orEmpty()
            if (key in delivered) {
                android.util.Log.i("MarcoFCM", "Duplicate push ignored")
                return
            }
            try {
                val stableId = (key.hashCode() and Int.MAX_VALUE).let { if (it == 0) 1 else it }
                NotificationManagerCompat.from(this).notify(stableId, notification)
                val updated = LinkedHashSet(delivered)
                updated.add(key)
                while (updated.size > 300) updated.remove(updated.first())
                preferences.edit().putStringSet("delivered_ids", updated).apply()
            } catch (_: SecurityException) {
                // Permission may be revoked between the check and notify call.
            }
        }
    }

    private val notificationLock = Any()

    private fun downloadNotificationImage(imageUrl: String): Bitmap? {
        if (!imageUrl.startsWith("https://", ignoreCase = true)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "image/*")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val length = connection.contentLength
            if (length > 4 * 1024 * 1024) return null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 4 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
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
