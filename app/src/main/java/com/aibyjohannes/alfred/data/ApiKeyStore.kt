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

    fun saveModel(model: String) {
        try {
            prefs.edit().putString(KEY_MODEL, model.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving model", e)
        }
    }

    fun loadModel(): String {
        return try {
            prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL_VAL
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            DEFAULT_MODEL_VAL
        }
    }

    fun saveSttModel(model: String) {
        try {
            prefs.edit().putString(KEY_STT_MODEL, model.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving STT model", e)
        }
    }

    fun loadSttModel(): String {
        return try {
            prefs.getString(KEY_STT_MODEL, null) ?: DEFAULT_STT_MODEL_VAL
        } catch (e: Exception) {
            Log.e(TAG, "Error loading STT model", e)
            DEFAULT_STT_MODEL_VAL
        }
    }

    fun saveTtsModel(model: String) {
        try {
            prefs.edit().putString(KEY_TTS_MODEL, model.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TTS model", e)
        }
    }

    fun loadTtsModel(): String {
        return try {
            prefs.getString(KEY_TTS_MODEL, null) ?: DEFAULT_TTS_MODEL_VAL
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TTS model", e)
            DEFAULT_TTS_MODEL_VAL
        }
    }

    fun saveTtsVoice(voice: String) {
        try {
            prefs.edit().putString(KEY_TTS_VOICE, voice.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TTS voice", e)
        }
    }

    fun loadTtsVoice(): String {
        return try {
            prefs.getString(KEY_TTS_VOICE, null) ?: DEFAULT_TTS_VOICE_VAL
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TTS voice", e)
            DEFAULT_TTS_VOICE_VAL
        }
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val PREFS_FILE_NAME = "alfred_secret_prefs"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_MODEL = "selected_model"
        private const val DEFAULT_MODEL_VAL = "google/gemini-3.5-flash"
        private const val KEY_STT_MODEL = "selected_stt_model"
        private const val DEFAULT_STT_MODEL_VAL = "openai/whisper-1"
        private const val KEY_TTS_MODEL = "selected_tts_model"
        private const val DEFAULT_TTS_MODEL_VAL = "openai/tts-1"
        private const val KEY_TTS_VOICE = "selected_tts_voice"
        private const val DEFAULT_TTS_VOICE_VAL = "alloy"
    }
}

