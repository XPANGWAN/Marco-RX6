package com.marcorx6.library.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.marcorx6.library.notification.BlogNotificationScheduler

/**
 * Keeps app-update and new-game push preferences independent.
 * Both preferences are device-local and synchronized with the FCM device record.
 */
internal class NotificationSettingsController(
    private val activity: Activity,
    private val scheduler: BlogNotificationScheduler,
    private val styleDialog: (AlertDialog) -> Unit,
    private val translate: (String) -> String,
    private val requestPermission: () -> Unit
) {
    fun show() {
        val selected = booleanArrayOf(
            scheduler.isAppUpdateEnabled(),
            scheduler.isEnabled()
        )
        val choices = arrayOf(
            translate("إشعارات تحديثات التطبيق"),
            translate("إشعارات الألعاب الجديدة")
        )
        val dialog = AlertDialog.Builder(activity)
            .setTitle(translate("إعدادات الإشعارات"))
            .setMessage(translate("اختر أنواع التنبيهات التي ترغب في تلقيها."))
            .setMultiChoiceItems(choices, selected) { _, which, checked ->
                selected[which] = checked
            }
            .setPositiveButton(translate("حفظ")) { _, _ ->
                scheduler.setAppUpdateEnabled(selected[0])
                scheduler.setEnabled(selected[1])
                if (selected.any { it } &&
                    !NotificationManagerCompat.from(activity).areNotificationsEnabled()
                ) {
                    if (Build.VERSION.SDK_INT >= 33) requestPermission()
                    else openSystemNotificationSettings()
                } else {
                    Toast.makeText(activity, translate("تم حفظ إعدادات الإشعارات"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(translate("إلغاء"), null)
            .show()
        styleDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            contentDescription = text
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            contentDescription = text
        }
    }

    private fun openSystemNotificationSettings() {
        val appSettings = Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
            putExtra("android.provider.extra.APP_PACKAGE", activity.packageName)
        }
        try {
            activity.startActivity(appSettings)
        } catch (_: Exception) {
            val details = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.packageName)
            )
            activity.startActivity(details)
        }
    }
}
