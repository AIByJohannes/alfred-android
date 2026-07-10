package com.aibyjohannes.alfred.core.reminders

data class ReminderRequest(
    val message: String,
    val triggerAtEpochMs: Long
)

/** Platform boundary for scheduling a user-requested notification. */
interface ReminderClient {
    suspend fun scheduleReminder(request: ReminderRequest): Result<String>
}
