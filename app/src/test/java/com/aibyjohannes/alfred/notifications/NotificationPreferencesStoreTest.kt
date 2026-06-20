package com.aibyjohannes.alfred.notifications

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationPreferencesStoreTest {

    private val context = mockk<Context>()
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var store: NotificationPreferencesStore
    private val prefsMap = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        prefsMap.clear()
        
        every { context.getSharedPreferences("alfred_notification_prefs", Context.MODE_PRIVATE) } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        
        // Mock get methods
        every { sharedPrefs.getBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Boolean>()
            (prefsMap[key] as? Boolean) ?: default
        }
        every { sharedPrefs.getInt(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Int>()
            (prefsMap[key] as? Int) ?: default
        }
        every { sharedPrefs.getLong(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Long>()
            (prefsMap[key] as? Long) ?: default
        }

        // Mock put methods
        every { editor.putBoolean(any(), any()) } answers {
            prefsMap[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            prefsMap[firstArg<String>()] = secondArg<Int>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            prefsMap[firstArg<String>()] = secondArg<Long>()
            editor
        }
        every { editor.apply() } just Runs

        store = NotificationPreferencesStore(context)
    }

    @Test
    fun `test default preferences`() {
        assertFalse(store.notificationsEnabled)
        assertEquals(NotificationPreferencesStore.DEFAULT_DAILY_HOUR, store.dailyReminderHour)
        assertEquals(NotificationPreferencesStore.DEFAULT_DAILY_MINUTE, store.dailyReminderMinute)
        assertEquals(0L, store.lastChatActivityEpochMs)
        assertEquals(0L, store.lastNotificationEpochMs)
        assertEquals(0L, store.lastInactivityNotificationActivityEpochMs)
    }

    @Test
    fun `test set and get enabled status`() {
        store.notificationsEnabled = true
        assertTrue(store.notificationsEnabled)
        store.notificationsEnabled = false
        assertFalse(store.notificationsEnabled)
    }

    @Test
    fun `test set and get hour with coercion`() {
        store.dailyReminderHour = 15
        assertEquals(15, store.dailyReminderHour)

        // Lower bound coercion
        store.dailyReminderHour = -5
        assertEquals(0, store.dailyReminderHour)

        // Upper bound coercion
        store.dailyReminderHour = 25
        assertEquals(23, store.dailyReminderHour)
    }

    @Test
    fun `test set and get minute with coercion`() {
        store.dailyReminderMinute = 30
        assertEquals(30, store.dailyReminderMinute)

        // Lower bound coercion
        store.dailyReminderMinute = -1
        assertEquals(0, store.dailyReminderMinute)

        // Upper bound coercion
        store.dailyReminderMinute = 60
        assertEquals(59, store.dailyReminderMinute)
    }

    @Test
    fun `test epoch ms fields`() {
        val now = System.currentTimeMillis()
        store.lastChatActivityEpochMs = now
        store.lastNotificationEpochMs = now + 1000
        store.lastInactivityNotificationActivityEpochMs = now + 2000

        assertEquals(now, store.lastChatActivityEpochMs)
        assertEquals(now + 1000, store.lastNotificationEpochMs)
        assertEquals(now + 2000, store.lastInactivityNotificationActivityEpochMs)
    }

    @Test
    fun `test reminderTimeLabel formatting`() {
        store.dailyReminderHour = 9
        store.dailyReminderMinute = 5
        assertEquals("09:05", store.reminderTimeLabel())

        store.dailyReminderHour = 22
        store.dailyReminderMinute = 15
        assertEquals("22:15", store.reminderTimeLabel())
    }
}
