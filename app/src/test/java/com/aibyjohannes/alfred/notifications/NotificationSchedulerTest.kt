package com.aibyjohannes.alfred.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.IdentityHashMap

class NotificationSchedulerTest {

    private val context = mockk<Context>(relaxed = true)
    private val alarmManager = mockk<AlarmManager>(relaxed = true)
    private val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
    private val dailyPendingIntent = mockk<PendingIntent>(name = "daily")
    private val inactivityPendingIntent = mockk<PendingIntent>(name = "inactivity")
    private val intentActions = IdentityHashMap<Intent, String>()
    private val pendingIntentRequests = mutableListOf<PendingIntentRequest>()

    @Before
    fun setUp() {
        mockkConstructor(NotificationPreferencesStore::class)
        mockkConstructor(Intent::class)
        mockkStatic(PendingIntent::class)

        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { anyConstructed<Intent>().setAction(any()) } answers {
            val intent = invocation.self as Intent
            intentActions[intent] = firstArg()
            intent
        }
        every { anyConstructed<Intent>().action } answers {
            intentActions[invocation.self as Intent]
        }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } answers {
            val request = PendingIntentRequest(
                requestCode = secondArg(),
                action = thirdArg<Intent>().action,
                flags = arg(3)
            )
            pendingIntentRequests += request
            when (request.requestCode) {
                1001 -> dailyPendingIntent
                1002 -> inactivityPendingIntent
                else -> error("Unexpected reminder request code ${request.requestCode}")
            }
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `disabled notifications cancel the daily reminder identity`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns false

        NotificationScheduler.scheduleDailyReminder(context, NOW)

        verify(exactly = 1) { alarmManager.cancel(dailyPendingIntent) }
        assertPendingIntent(1001, NotificationScheduler.ACTION_DAILY_REMINDER)
    }

    @Test
    fun `daily reminder schedules the next configured local time`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderHour } returns 9
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderMinute } returns 30

        NotificationScheduler.scheduleDailyReminder(context, NOW)

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                expectedDailyTrigger(NOW, 9, 30),
                dailyPendingIntent
            )
        }
        assertPendingIntent(1001, NotificationScheduler.ACTION_DAILY_REMINDER)
    }

    @Test
    fun `disabled notifications cancel the inactivity reminder identity`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns false

        NotificationScheduler.scheduleInactivityReminder(context, NOW)

        verify(exactly = 1) { alarmManager.cancel(inactivityPendingIntent) }
        assertPendingIntent(1002, NotificationScheduler.ACTION_INACTIVITY_REMINDER)
    }

    @Test
    fun `inactivity reminder is cancelled when no chat activity exists`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns 0L

        NotificationScheduler.scheduleInactivityReminder(context, NOW)

        verify(exactly = 1) { alarmManager.cancel(inactivityPendingIntent) }
    }

    @Test
    fun `inactivity reminder is cancelled after that activity was already notified`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns 1_000L
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 1_000L

        NotificationScheduler.scheduleInactivityReminder(context, NOW)

        verify(exactly = 1) { alarmManager.cancel(inactivityPendingIntent) }
    }

    @Test
    fun `inactivity reminder honors both activity delay and notification cooldown`() {
        val lastActivity = NOW - 30L * 60L * 60L * 1000L
        val lastNotification = NOW - 1L * 60L * 60L * 1000L
        val expected = lastNotification + NotificationScheduler.NOTIFICATION_COOLDOWN_MS
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns lastActivity
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 0L
        every { anyConstructed<NotificationPreferencesStore>().lastNotificationEpochMs } returns lastNotification

        NotificationScheduler.scheduleInactivityReminder(context, NOW)

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expected, inactivityPendingIntent)
        }
    }

    @Test
    fun `overdue inactivity reminder is deferred by one minute`() {
        val lastActivity = NOW - 48L * 60L * 60L * 1000L
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns lastActivity
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 0L
        every { anyConstructed<NotificationPreferencesStore>().lastNotificationEpochMs } returns 0L

        NotificationScheduler.scheduleInactivityReminder(context, NOW)

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, NOW + 60_000L, inactivityPendingIntent)
        }
    }

    @Test
    fun `rescheduleAll creates both enabled reminder identities`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderHour } returns 9
        every { anyConstructed<NotificationPreferencesStore>().dailyReminderMinute } returns 0
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns NOW
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 0L
        every { anyConstructed<NotificationPreferencesStore>().lastNotificationEpochMs } returns 0L

        NotificationScheduler.rescheduleAll(context, NOW)

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), any(), dailyPendingIntent) }
        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), any(), inactivityPendingIntent) }
        assertTrue(pendingIntentRequests.any { it.requestCode == 1001 })
        assertTrue(pendingIntentRequests.any { it.requestCode == 1002 })
    }

    @Test
    fun `rescheduleAll cancels both disabled reminder identities`() {
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns false

        NotificationScheduler.rescheduleAll(context, NOW)

        verify(exactly = 1) { alarmManager.cancel(dailyPendingIntent) }
        verify(exactly = 1) { alarmManager.cancel(inactivityPendingIntent) }
    }

    @Test
    fun `recordChatActivity stores the supplied time and schedules from it`() {
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs = any() } returns Unit
        every { anyConstructed<NotificationPreferencesStore>().notificationsEnabled } returns true
        every { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs } returns NOW
        every { anyConstructed<NotificationPreferencesStore>().lastInactivityNotificationActivityEpochMs } returns 0L
        every { anyConstructed<NotificationPreferencesStore>().lastNotificationEpochMs } returns 0L

        NotificationScheduler.recordChatActivity(context, NOW)

        verify(exactly = 1) { anyConstructed<NotificationPreferencesStore>().lastChatActivityEpochMs = NOW }
        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                NOW + NotificationScheduler.INACTIVITY_DELAY_MS,
                inactivityPendingIntent
            )
        }
    }

    private fun assertPendingIntent(requestCode: Int, action: String) {
        val request = pendingIntentRequests.single { it.requestCode == requestCode }
        assertEquals(action, request.action)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, request.flags)
    }

    private fun expectedDailyTrigger(nowEpochMs: Long, hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneId.systemDefault())
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.toInstant().isAfter(now.toInstant())) {
            target = target.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }

    private data class PendingIntentRequest(
        val requestCode: Int,
        val action: String?,
        val flags: Int
    )

    companion object {
        private val NOW = Instant.parse("2026-06-20T10:00:00Z").toEpochMilli()
    }
}
