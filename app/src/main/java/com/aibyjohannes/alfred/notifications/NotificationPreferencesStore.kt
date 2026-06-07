package com.aibyjohannes.alfred.notifications

import android.content.Context
import java.util.Locale

class NotificationPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var dailyReminderHour: Int
        get() = prefs.getInt(KEY_DAILY_HOUR, DEFAULT_DAILY_HOUR)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_HOUR, value.coerceIn(0, 23)).apply()
        }

    var dailyReminderMinute: Int
        get() = prefs.getInt(KEY_DAILY_MINUTE, DEFAULT_DAILY_MINUTE)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_MINUTE, value.coerceIn(0, 59)).apply()
        }

    var lastChatActivityEpochMs: Long
        get() = prefs.getLong(KEY_LAST_CHAT_ACTIVITY, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHAT_ACTIVITY, value).apply()
        }

    var lastNotificationEpochMs: Long
        get() = prefs.getLong(KEY_LAST_NOTIFICATION, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_NOTIFICATION, value).apply()
        }

    var lastInactivityNotificationActivityEpochMs: Long
        get() = prefs.getLong(KEY_LAST_INACTIVITY_ACTIVITY, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_INACTIVITY_ACTIVITY, value).apply()
        }

    fun reminderTimeLabel(): String {
        return String.format(Locale.getDefault(), "%02d:%02d", dailyReminderHour, dailyReminderMinute)
    }

    companion object {
        const val DEFAULT_DAILY_HOUR = 9
        const val DEFAULT_DAILY_MINUTE = 0
        private const val PREFS_NAME = "alfred_notification_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DAILY_HOUR = "daily_hour"
        private const val KEY_DAILY_MINUTE = "daily_minute"
        private const val KEY_LAST_CHAT_ACTIVITY = "last_chat_activity_epoch_ms"
        private const val KEY_LAST_NOTIFICATION = "last_notification_epoch_ms"
        private const val KEY_LAST_INACTIVITY_ACTIVITY = "last_inactivity_notification_activity_epoch_ms"
    }
}
