package com.aibyjohannes.alfred.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotificationScheduler.rescheduleAll(context)
        }
    }
}
