package com.aibyjohannes.alfred.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class NotificationSchedulerTest {

    private val context = mockk<Context>(relaxed = true)
    private val alarmManager = mockk<AlarmManager>(relaxed = true)
    private val pendingIntent = mockk<PendingIntent>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)

    @Before
    fun setUp() {
        mockkConstructor(NotificationPreferencesStore::class)
        mockkConstructor(Intent::class)
        mockkStatic(PendingIntent::class)

        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns pendingIntent
        every { anyConstructed<Intent>().setAction(any()) } answers { invocation.self as Intent }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun verifyAlarmSet() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            verify { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
        } else {
            verify { alarmManager.set(any(), any(), any()) }
        }
    }

    @Test
    fun `test scheduleDailyReminder when notifications disabled`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns false

        NotificationScheduler.scheduleDailyReminder(context)

        // Should cancel daily reminder
        verify { alarmManager.cancel(pendingIntent) }
    }

    @Test
    fun `test scheduleDailyReminder when notifications enabled`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderHour } returns 9
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderMinute } returns 30

        NotificationScheduler.scheduleDailyReminder(context)

        // Should set alarm
        verifyAlarmSet()
    }

    @Test
    fun `test scheduleInactivityReminder when notifications disabled`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns false

        NotificationScheduler.scheduleInactivityReminder(context)

        verify { alarmManager.cancel(pendingIntent) }
    }

    @Test
    fun `test scheduleInactivityReminder when no activity recorded`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns 0L

        NotificationScheduler.scheduleInactivityReminder(context)

        verify { alarmManager.cancel(pendingIntent) }
    }

    @Test
    fun `test scheduleInactivityReminder when already notified for last activity`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns 1000L
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 1000L

        NotificationScheduler.scheduleInactivityReminder(context)

        verify { alarmManager.cancel(pendingIntent) }
    }

    @Test
    fun `test scheduleInactivityReminder schedules correctly`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns System.currentTimeMillis()
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 0L
        every { anyConstructed<NotificationPreferencesStore>().lastNotificationEpochMs } returns 0L

        NotificationScheduler.scheduleInactivityReminder(context)

        verifyAlarmSet()
    }

    @Test
    fun `test rescheduleAll when enabled`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderHour } returns 9
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderMinute } returns 0
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns 0L

        NotificationScheduler.rescheduleAll(context)

        verifyAlarmSet()
    }

    @Test
    fun `test rescheduleAll when disabled`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns false

        NotificationScheduler.rescheduleAll(context)

        verify(exactly = 2) { alarmManager.cancel(pendingIntent) }
    }

    @Test
    fun `test recordChatActivity`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs = any() } just Runs
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns System.currentTimeMillis()
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 0L
        every { anyConstructed<NotificationPreferencesStore>().lastNotificationEpochMs } returns 0L

        NotificationScheduler.recordChatActivity(context)

        verify { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs = any() }
        verifyAlarmSet()
    }
}
