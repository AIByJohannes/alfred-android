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

class AlfredNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the notification channel for Android O+
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

        // Tap action opens MainActivity
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Select a fun engagement prompt
        val prompt = ENGAGEMENT_PROMPTS.random()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // Fallback standard app launcher icon
            .setContentTitle("Alfred")
            .setContentText(prompt)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Reschedule alarm for tomorrow to keep it reliable
        NotificationScheduler.scheduleDailyReminder(context)
    }

    companion object {
        const val CHANNEL_ID = "alfred_daily_engagement"
        const val NOTIFICATION_ID = 1001

        private val ENGAGEMENT_PROMPTS = listOf(
            "Good morning! Ask Alfred something today 👋",
            "Got any burning questions? I'm here to help 💡",
            "Let's tackle your goals today. What are we working on? 🚀",
            "Ready to learn something new? Ask me anything! 🧠",
            "Hey! Need to search the web or check your local notes? I've got you covered 🌐"
        )
    }
}
