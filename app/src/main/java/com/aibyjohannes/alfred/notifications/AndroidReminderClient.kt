package com.aibyjohannes.alfred.notifications

import android.content.Context
import com.aibyjohannes.alfred.core.reminders.ReminderClient
import com.aibyjohannes.alfred.core.reminders.ReminderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class AndroidReminderClient(context: Context) : ReminderClient {
    private val appContext = context.applicationContext

    override suspend fun scheduleReminder(request: ReminderRequest): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            NotificationScheduler.scheduleOneTimeReminder(appContext, request.message, request.triggerAtEpochMs)
            "Reminder scheduled for ${Instant.ofEpochMilli(request.triggerAtEpochMs)}."
        }
    }
}
