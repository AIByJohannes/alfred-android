package com.aibyjohannes.alfred.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aibyjohannes.alfred.core.audio.OpenRouterTtsClient

class ApiKeyStore internal constructor(
    private val prefs: SharedPreferences,
    private val fallbackApiKey: String
) {

    constructor(context: Context) : this(
        prefs = createPreferences(context),
        fallbackApiKey = com.aibyjohannes.alfred.BuildConfig.OPENROUTER_API_KEY
    )

    fun saveOpenRouterKey(key: String) {
        try {
            prefs.edit().putString(KEY_OPENROUTER_API_KEY, key.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving API key", e)
        }
    }

    fun loadOpenRouterKey(): String? {
        return try {
            prefs.getString(KEY_OPENROUTER_API_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackApiKey.trim().takeIf { it.isNotEmpty() }
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
            when (val model = prefs.getString(KEY_TTS_MODEL, null)?.trim()) {
                null, "" -> DEFAULT_TTS_MODEL_VAL
                in LEGACY_OPENAI_TTS_MODELS -> DEFAULT_TTS_MODEL_VAL
                else -> model
            }
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
            val voice = prefs.getString(KEY_TTS_VOICE, null)?.trim()
            when {
                voice.isNullOrBlank() -> DEFAULT_TTS_VOICE_VAL
                voice in LEGACY_OPENAI_TTS_VOICE_MIGRATIONS -> LEGACY_OPENAI_TTS_VOICE_MIGRATIONS.getValue(voice)
                else -> voice
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TTS voice", e)
            DEFAULT_TTS_VOICE_VAL
        }
    }

    fun saveTickTickClientId(clientId: String) {
        try {
            prefs.edit().putString(KEY_TICKTICK_CLIENT_ID, clientId.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TickTick client ID", e)
        }
    }

    fun loadTickTickClientId(): String? {
        return try {
            prefs.getString(KEY_TICKTICK_CLIENT_ID, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TickTick client ID", e)
            null
        }
    }

    fun saveTickTickClientSecret(clientSecret: String) {
        try {
            prefs.edit().putString(KEY_TICKTICK_CLIENT_SECRET, clientSecret.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TickTick client secret", e)
        }
    }

    fun loadTickTickClientSecret(): String? {
        return try {
            prefs.getString(KEY_TICKTICK_CLIENT_SECRET, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TickTick client secret", e)
            null
        }
    }

    fun saveTickTickAccessToken(token: String) {
        try {
            prefs.edit().putString(KEY_TICKTICK_ACCESS_TOKEN, token.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TickTick access token", e)
        }
    }

    fun loadTickTickAccessToken(): String? {
        return try {
            prefs.getString(KEY_TICKTICK_ACCESS_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TickTick access token", e)
            null
        }
    }

    fun saveTickTickRefreshToken(token: String) {
        try {
            prefs.edit().putString(KEY_TICKTICK_REFRESH_TOKEN, token.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving TickTick refresh token", e)
        }
    }

    fun loadTickTickRefreshToken(): String? {
        return try {
            prefs.getString(KEY_TICKTICK_REFRESH_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TickTick refresh token", e)
            null
        }
    }

    fun hasTickTickAuth(): Boolean {
        return !loadTickTickAccessToken().isNullOrBlank()
    }

    fun clearTickTickCredentials() {
        try {
            prefs.edit()
                .remove(KEY_TICKTICK_CLIENT_ID)
                .remove(KEY_TICKTICK_CLIENT_SECRET)
                .remove(KEY_TICKTICK_ACCESS_TOKEN)
                .remove(KEY_TICKTICK_REFRESH_TOKEN)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing TickTick credentials", e)
        }
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val PREFS_FILE_NAME = "alfred_secret_prefs"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_TICKTICK_CLIENT_ID = "ticktick_client_id"
        private const val KEY_TICKTICK_CLIENT_SECRET = "ticktick_client_secret"
        private const val KEY_TICKTICK_ACCESS_TOKEN = "ticktick_access_token"
        private const val KEY_TICKTICK_REFRESH_TOKEN = "ticktick_refresh_token"
        private const val KEY_MODEL = "selected_model"
        private const val DEFAULT_MODEL_VAL = "deepseek/deepseek-v4-flash"
        private const val KEY_STT_MODEL = "selected_stt_model"
        private const val DEFAULT_STT_MODEL_VAL = "openai/whisper-1"
        private const val KEY_TTS_MODEL = "selected_tts_model"
        private const val DEFAULT_TTS_MODEL_VAL = OpenRouterTtsClient.DEFAULT_MODEL
        private const val KEY_TTS_VOICE = "selected_tts_voice"
        private const val DEFAULT_TTS_VOICE_VAL = OpenRouterTtsClient.DEFAULT_VOICE
        private val LEGACY_OPENAI_TTS_MODELS = setOf(
            "openai/tts-1",
            "openai/tts-1-hd"
        )
        private val LEGACY_OPENAI_TTS_VOICE_MIGRATIONS = mapOf(
            "alloy" to "af_alloy",
            "echo" to "am_echo",
            "fable" to "bm_fable",
            "onyx" to "am_onyx",
            "nova" to "af_nova",
            "shimmer" to "af_sky"
        )

        private fun createPreferences(context: Context): SharedPreferences {
            return try {
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
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}

