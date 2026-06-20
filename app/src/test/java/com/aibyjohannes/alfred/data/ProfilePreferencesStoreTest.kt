package com.aibyjohannes.alfred.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfilePreferencesStoreTest {

    private val context = mockk<Context>()
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var store: ProfilePreferencesStore
    private val prefsMap = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        prefsMap.clear()

        every { context.getSharedPreferences("alfred_profile_prefs", Context.MODE_PRIVATE) } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor

        every { sharedPrefs.getString(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<String>()
            prefsMap[key] ?: default
        }

        every { editor.putString(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            if (value != null) {
                prefsMap[key] = value
            } else {
                prefsMap.remove(key)
            }
            editor
        }
        every { editor.apply() } just Runs

        store = ProfilePreferencesStore(context)
    }

    @Test
    fun `test default profile settings`() {
        assertEquals(ProfilePreferencesStore.DEFAULT_DISPLAY_NAME, store.displayName)
        assertEquals(ProfilePreferencesStore.DEFAULT_STATUS_LABEL, store.statusLabel)
    }

    @Test
    fun `test set and get displayName trims input`() {
        store.displayName = "   Alice Bob   "
        assertEquals("Alice Bob", store.displayName)

        store.displayName = "Charlie"
        assertEquals("Charlie", store.displayName)
    }

    @Test
    fun `test set and get statusLabel trims input`() {
        store.statusLabel = "   Active Status   "
        assertEquals("Active Status", store.statusLabel)

        store.statusLabel = "Away"
        assertEquals("Away", store.statusLabel)
    }
}
