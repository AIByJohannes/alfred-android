package com.aibyjohannes.alfred.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveOpenRouterKey(key: String) {
        prefs.edit().putString(KEY_OPENROUTER_API_KEY, key.trim()).apply()
    }

    fun loadOpenRouterKey(): String? {
        return prefs.getString(KEY_OPENROUTER_API_KEY, null)
    }

    fun hasApiKey(): Boolean {
        return !loadOpenRouterKey().isNullOrBlank()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_OPENROUTER_API_KEY).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "alfred_secret_prefs"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    }
}

