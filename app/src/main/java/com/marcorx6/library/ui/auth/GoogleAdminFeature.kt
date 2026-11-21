package com.marcorx6.library.ui

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn

/** Single feature boundary for Google account operations, verified admin session, and OAuth tokens. */
internal class GoogleAdminFeature(private val context: Context, translate: (String) -> String) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "marco_blogger_session",
        Context.MODE_PRIVATE
    )
    private val sessionState = AdminSessionState().apply {
        setBlogId(preferences.getString("blog_id", "").orEmpty())
    }

    val accountController = GoogleAccountController(context)
    val tokenProvider = GoogleTokenProvider(context, sessionState, translate)

    var blogId: String
        get() = sessionState.blogId()
        set(value) {
            sessionState.setBlogId(value)
            preferences.edit().putString("blog_id", value).apply()
        }

    var isAdminSession: Boolean
        get() = sessionState.isAuthorized(
            accountId = GoogleSignIn.getLastSignedInAccount(context)?.id
        )
        set(value) {
            if (value) {
                sessionState.grant(
                    accountId = GoogleSignIn.getLastSignedInAccount(context)?.id,
                    blogId = blogId
                )
            } else {
                sessionState.clear()
            }
        }

    fun driveToken(): String = tokenProvider.driveToken()
}
