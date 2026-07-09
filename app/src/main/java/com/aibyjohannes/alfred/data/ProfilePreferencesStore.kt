package com.aibyjohannes.alfred.data

import android.content.Context

class ProfilePreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
        set(value) {
            prefs.edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()
        }

    val statusLabel: String
        get() = DEFAULT_STATUS_LABEL

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()
        }

    companion object {
        private const val PREFS_FILE_NAME = "alfred_profile_prefs"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_STATUS_LABEL = "status_label"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val DEFAULT_DISPLAY_NAME = "Alfred User"
        const val DEFAULT_STATUS_LABEL = "Local profile"
    }
}
