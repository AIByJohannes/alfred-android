package com.aibyjohannes.alfred.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object NotificationScheduler {
    const val ACTION_DAILY_REMINDER = "com.aibyjohannes.alfred.notifications.DAILY_REMINDER"
    const val ACTION_INACTIVITY_REMINDER = "com.aibyjohannes.alfred.notifications.INACTIVITY_REMINDER"
    const val ACTION_ONE_TIME_REMINDER = "com.aibyjohannes.alfred.notifications.ONE_TIME_REMINDER"
    const val EXTRA_REMINDER_MESSAGE = "reminder_message"
    const val EXTRA_REMINDER_NOTIFICATION_ID = "reminder_notification_id"

    fun scheduleOneTimeReminder(context: Context, message: String, triggerAtEpochMs: Long) {
        require(message.isNotBlank()) { "Reminder message must not be blank" }
        require(triggerAtEpochMs > System.currentTimeMillis()) { "Reminder time must be in the future" }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw IllegalStateException("Alarm manager is unavailable")
        val notificationId = ("$triggerAtEpochMs:${message.trim()}".hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        val intent = Intent(context, AlfredNotificationReceiver::class.java)
            .setAction(ACTION_ONE_TIME_REMINDER)
            .putExtra(EXTRA_REMINDER_MESSAGE, message.trim())
            .putExtra(EXTRA_REMINDER_NOTIFICATION_ID, notificationId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarm(alarmManager, triggerAtEpochMs, pendingIntent)
    }

    fun scheduleDailyReminder(context: Context) {
        scheduleDailyReminder(context, System.currentTimeMillis())
    }

    internal fun scheduleDailyReminder(context: Context, nowEpochMs: Long) {
        val preferences = NotificationPreferencesStore(context)
        if (!preferences.notificationsEnabled) {
            cancelDailyReminder(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = reminderPendingIntent(context, ACTION_DAILY_REMINDER, REQUEST_DAILY_REMINDER)

        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            set(Calendar.HOUR_OF_DAY, preferences.dailyReminderHour)
            set(Calendar.MINUTE, preferences.dailyReminderMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= nowEpochMs) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        setAlarm(alarmManager, calendar.timeInMillis, pendingIntent)
    }

    fun scheduleInactivityReminder(context: Context) {
        scheduleInactivityReminder(context, System.currentTimeMillis())
    }

    internal fun scheduleInactivityReminder(context: Context, nowEpochMs: Long) {
        val preferences = NotificationPreferencesStore(context)
        if (!preferences.notificationsEnabled) {
            cancelInactivityReminder(context)
            return
        }

        val lastActivity = preferences.lastChatActivityEpochMs
        if (lastActivity <= 0L || preferences.lastInactivityNotificationActivityEpochMs == lastActivity) {
            cancelInactivityReminder(context)
            return
        }

        val targetTime = (lastActivity + INACTIVITY_DELAY_MS).coerceAtLeast(
            preferences.lastNotificationEpochMs + NOTIFICATION_COOLDOWN_MS
        )
        if (targetTime <= nowEpochMs) {
            scheduleInactivityAt(context, nowEpochMs + MIN_RESCHEDULE_DELAY_MS)
        } else {
            scheduleInactivityAt(context, targetTime)
        }
    }

    fun rescheduleAll(context: Context) {
        rescheduleAll(context, System.currentTimeMillis())
    }

    internal fun rescheduleAll(context: Context, nowEpochMs: Long) {
        val preferences = NotificationPreferencesStore(context)
        if (preferences.notificationsEnabled) {
            scheduleDailyReminder(context, nowEpochMs)
            scheduleInactivityReminder(context, nowEpochMs)
        } else {
            cancelAll(context)
        }
    }

    fun cancelAll(context: Context) {
        cancelDailyReminder(context)
        cancelInactivityReminder(context)
    }

    fun recordChatActivity(context: Context) {
        recordChatActivity(context, System.currentTimeMillis())
    }

    internal fun recordChatActivity(context: Context, nowEpochMs: Long) {
        NotificationPreferencesStore(context).lastChatActivityEpochMs = nowEpochMs
        scheduleInactivityReminder(context, nowEpochMs)
    }

    private fun scheduleInactivityAt(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = reminderPendingIntent(context, ACTION_INACTIVITY_REMINDER, REQUEST_INACTIVITY_REMINDER)
        setAlarm(alarmManager, triggerAtMillis, pendingIntent)
    }

    private fun setAlarm(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
    }

    private fun cancelDailyReminder(context: Context) {
        cancel(context, ACTION_DAILY_REMINDER, REQUEST_DAILY_REMINDER)
    }

    private fun cancelInactivityReminder(context: Context) {
        cancel(context, ACTION_INACTIVITY_REMINDER, REQUEST_INACTIVITY_REMINDER)
    }

    private fun cancel(context: Context, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(reminderPendingIntent(context, action, requestCode))
    }

    private fun reminderPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlfredNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val INACTIVITY_DELAY_MS = 24 * 60 * 60 * 1000L
    const val NOTIFICATION_COOLDOWN_MS = 6 * 60 * 60 * 1000L
    private const val MIN_RESCHEDULE_DELAY_MS = 60 * 1000L
    private const val REQUEST_DAILY_REMINDER = 1001
    private const val REQUEST_INACTIVITY_REMINDER = 1002
}
