package com.aibyjohannes.alfred.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Error creating EncryptedSharedPreferences, falling back to regular SharedPreferences", e)
        // Fallback to regular SharedPreferences if EncryptedSharedPreferences fails
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun saveOpenRouterKey(key: String) {
        try {
            prefs.edit().putString(KEY_OPENROUTER_API_KEY, key.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving API key", e)
        }
    }

    fun loadOpenRouterKey(): String? {
        return try {
            val key = prefs.getString(KEY_OPENROUTER_API_KEY, null)
            if (key.isNullOrBlank() && com.aibyjohannes.alfred.BuildConfig.OPENROUTER_API_KEY.isNotBlank()) {
                return com.aibyjohannes.alfred.BuildConfig.OPENROUTER_API_KEY
            }
            key
        } catch (e: Exception) {
            Log.e(TAG, "Error loading API key", e)
            null
        }
    }

    fun hasApiKey(): Boolean {
        return !loadOpenRouterKey().isNullOrBlank()
    }

    fun clearApiKey() {
        try {
            prefs.edit().remove(KEY_OPENROUTER_API_KEY).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing API key", e)
        }
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val PREFS_FILE_NAME = "alfred_secret_prefs"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    }
}

