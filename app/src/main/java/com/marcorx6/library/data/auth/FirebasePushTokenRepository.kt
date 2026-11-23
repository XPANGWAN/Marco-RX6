package com.marcorx6.library.data.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest

/** Stores each registered user's FCM token in a private, per-account device document. */
internal class FirebasePushTokenRepository(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val messaging: FirebaseMessaging = FirebaseMessaging.getInstance()
) {
    fun syncCurrentToken() {
        val user = auth.currentUser
        if (user == null) {
            android.util.Log.i("MarcoFCM", "Token sync deferred: Firebase user is not ready")
            return
        }
        // Anonymous Firebase sessions are valid device owners for push registration.
        // Visitors can receive app updates without creating a MARCO account.
        messaging.token
            .addOnSuccessListener { token -> saveTokenForUser(token, user.uid) }
            .addOnFailureListener { error ->
                android.util.Log.w("MarcoFCM", "Unable to retrieve FCM token", error)
            }
    }

    fun saveToken(token: String) {
        if (token.isBlank()) return
        val user = auth.currentUser ?: return
        saveTokenForUser(token, user.uid)
    }

    private fun saveTokenForUser(token: String, uid: String) {
        if (token.isBlank() || uid.isBlank()) return
        // A token request may finish after an account switch; keep it bound to the
        // account that initiated the request.
        val documentId = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        firestore.collection("users").document(uid)
            .collection("devices").document(documentId)
            .set(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "blogNotificationsEnabled" to context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE).getBoolean("enabled", false),
                    "appUpdateNotificationsEnabled" to context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE).getBoolean("app_updates_enabled", true),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener {
                val preferences = context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
                val blogEnabled = preferences.getBoolean("enabled", false)
                val appUpdatesEnabled = preferences.getBoolean("app_updates_enabled", true)
                android.util.Log.i("MarcoFCM", "FCM device registration saved; blogNotificationsEnabled=$blogEnabled appUpdateNotificationsEnabled=$appUpdatesEnabled")
            }
            .addOnFailureListener { error ->
                android.util.Log.w("MarcoFCM", "Unable to save FCM device token", error)
            }
    }
}
