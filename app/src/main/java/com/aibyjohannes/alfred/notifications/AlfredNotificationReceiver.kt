package com.aibyjohannes.alfred.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aibyjohannes.alfred.MainActivity
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.local.ChatHistoryLocationStore
import com.aibyjohannes.alfred.data.local.FileConversationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlfredNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleNotification(context.applicationContext, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleNotification(context: Context, intent: Intent) {
        val preferences = NotificationPreferencesStore(context)
        if (!preferences.notificationsEnabled) {
            NotificationScheduler.cancelAll(context)
            return
        }

        val isOneTimeReminder = intent.action == NotificationScheduler.ACTION_ONE_TIME_REMINDER
        val kind = when (intent.action) {
            NotificationScheduler.ACTION_INACTIVITY_REMINDER -> NotificationKind.INACTIVITY
            else -> NotificationKind.DAILY
        }

        val now = System.currentTimeMillis()
        if (!isOneTimeReminder && now - preferences.lastNotificationEpochMs < NotificationScheduler.NOTIFICATION_COOLDOWN_MS) {
            NotificationScheduler.rescheduleAll(context)
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Engagement Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily friendly prompts to interact with Alfred"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val storage = runCatching {
            ChatHistoryLocationStore(context).createStorage()
        }.getOrNull()
        val prompt = intent.getStringExtra(NotificationScheduler.EXTRA_REMINDER_MESSAGE)
            ?.takeIf { isOneTimeReminder && it.isNotBlank() }
            ?: if (storage != null) {
            NotificationPersonalizer(FileConversationStore(storage, context)).buildPrompt(kind)
        } else {
            NotificationPersonalizer.genericPrompt(kind)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Alfred")
            .setContentText(prompt)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        val notificationId = intent.getIntExtra(
            NotificationScheduler.EXTRA_REMINDER_NOTIFICATION_ID,
            NOTIFICATION_ID
        )
        notificationManager.notify(notificationId, notification)
        preferences.lastNotificationEpochMs = now
        if (kind == NotificationKind.INACTIVITY) {
            preferences.lastInactivityNotificationActivityEpochMs = preferences.lastChatActivityEpochMs
        }

        NotificationScheduler.rescheduleAll(context)
    }

    companion object {
        const val CHANNEL_ID = "alfred_daily_engagement"
        const val NOTIFICATION_ID = 1001
    }
}
