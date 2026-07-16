package com.aibyjohannes.alfred.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aibyjohannes.alfred.core.audio.OpenRouterTtsClient
import com.aibyjohannes.alfred.core.notion.NotionCredentials
import com.aibyjohannes.alfred.core.notion.NotionOAuthPending

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
            normalizeChatModel(prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL_VAL)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            DEFAULT_MODEL_VAL
        }
    }

    fun saveTogetherApiKey(key: String) {
        prefs.edit().putString(KEY_TOGETHER_API_KEY, key.trim()).apply()
    }

    fun loadTogetherApiKey(): String? = prefs.getString(KEY_TOGETHER_API_KEY, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun hasTogetherApiKey(): Boolean = loadTogetherApiKey() != null

    fun clearTogetherApiKey() {
        prefs.edit().remove(KEY_TOGETHER_API_KEY).apply()
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

    fun saveNotionPendingAuthorization(pending: NotionOAuthPending) {
        prefs.edit()
            .putString(KEY_NOTION_CLIENT_ID, pending.clientId)
            .putString(KEY_NOTION_CLIENT_SECRET, pending.clientSecret)
            .putString(KEY_NOTION_TOKEN_ENDPOINT, pending.tokenEndpoint)
            .putString(KEY_NOTION_REDIRECT_URI, pending.redirectUri)
            .putString(KEY_NOTION_CODE_VERIFIER, pending.codeVerifier)
            .putString(KEY_NOTION_OAUTH_STATE, pending.state)
            .apply()
    }

    fun loadNotionPendingAuthorization(): NotionOAuthPending? {
        val clientId = prefs.getString(KEY_NOTION_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val tokenEndpoint = prefs.getString(KEY_NOTION_TOKEN_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: return null
        val redirectUri = prefs.getString(KEY_NOTION_REDIRECT_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val verifier = prefs.getString(KEY_NOTION_CODE_VERIFIER, null)?.takeIf { it.isNotBlank() } ?: return null
        val state = prefs.getString(KEY_NOTION_OAUTH_STATE, null)?.takeIf { it.isNotBlank() } ?: return null
        return NotionOAuthPending(
            clientId = clientId,
            clientSecret = prefs.getString(KEY_NOTION_CLIENT_SECRET, null)?.takeIf { it.isNotBlank() },
            tokenEndpoint = tokenEndpoint,
            redirectUri = redirectUri,
            codeVerifier = verifier,
            state = state,
            authorizationUrl = ""
        )
    }

    fun saveNotionCredentials(credentials: NotionCredentials) {
        prefs.edit()
            .putString(KEY_NOTION_CLIENT_ID, credentials.clientId)
            .putString(KEY_NOTION_CLIENT_SECRET, credentials.clientSecret)
            .putString(KEY_NOTION_ACCESS_TOKEN, credentials.accessToken)
            .putString(KEY_NOTION_REFRESH_TOKEN, credentials.refreshToken)
            .putString(KEY_NOTION_TOKEN_ENDPOINT, credentials.tokenEndpoint)
            .putLong(KEY_NOTION_EXPIRES_AT, credentials.expiresAtEpochSeconds ?: 0L)
            .putString(KEY_NOTION_WORKSPACE_ID, credentials.workspaceId)
            .remove(KEY_NOTION_REDIRECT_URI)
            .remove(KEY_NOTION_CODE_VERIFIER)
            .remove(KEY_NOTION_OAUTH_STATE)
            .apply()
    }

    fun loadNotionCredentials(): NotionCredentials? {
        val clientId = prefs.getString(KEY_NOTION_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = prefs.getString(KEY_NOTION_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val tokenEndpoint = prefs.getString(KEY_NOTION_TOKEN_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: return null
        return NotionCredentials(
            clientId = clientId,
            clientSecret = prefs.getString(KEY_NOTION_CLIENT_SECRET, null)?.takeIf { it.isNotBlank() },
            accessToken = accessToken,
            refreshToken = prefs.getString(KEY_NOTION_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() },
            tokenEndpoint = tokenEndpoint,
            expiresAtEpochSeconds = prefs.getLong(KEY_NOTION_EXPIRES_AT, 0L).takeIf { it > 0L },
            workspaceId = prefs.getString(KEY_NOTION_WORKSPACE_ID, null)?.takeIf { it.isNotBlank() }
        )
    }

    fun hasNotionAuth(): Boolean = loadNotionCredentials() != null

    fun clearNotionCredentials() {
        prefs.edit()
            .remove(KEY_NOTION_CLIENT_ID)
            .remove(KEY_NOTION_CLIENT_SECRET)
            .remove(KEY_NOTION_ACCESS_TOKEN)
            .remove(KEY_NOTION_REFRESH_TOKEN)
            .remove(KEY_NOTION_TOKEN_ENDPOINT)
            .remove(KEY_NOTION_EXPIRES_AT)
            .remove(KEY_NOTION_WORKSPACE_ID)
            .remove(KEY_NOTION_REDIRECT_URI)
            .remove(KEY_NOTION_CODE_VERIFIER)
            .remove(KEY_NOTION_OAUTH_STATE)
            .apply()
    }

    fun saveGitHubAccessToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_ACCESS_TOKEN, token.trim()).apply()
    }

    fun loadGitHubAccessToken(): String? = prefs.getString(KEY_GITHUB_ACCESS_TOKEN, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun hasGitHubAuth(): Boolean = loadGitHubAccessToken() != null

    fun clearGitHubCredentials() {
        prefs.edit().remove(KEY_GITHUB_ACCESS_TOKEN).apply()
    }

    fun saveSearchTool(searchTool: String) {
        try {
            prefs.edit().putString(KEY_SEARCH_TOOL, searchTool.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving search tool", e)
        }
    }

    fun loadSearchTool(): String {
        return try {
            prefs.getString(KEY_SEARCH_TOOL, null) ?: DEFAULT_SEARCH_TOOL_VAL
        } catch (e: Exception) {
            Log.e(TAG, "Error loading search tool", e)
            DEFAULT_SEARCH_TOOL_VAL
        }
    }

    fun saveEfficiencyMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EFFICIENCY_MODE, enabled).apply()
    }

    fun isEfficiencyModeEnabled(): Boolean = prefs.getBoolean(KEY_EFFICIENCY_MODE, false)

    fun savePrivacyMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY_MODE, enabled).apply()
    }

    fun isPrivacyModeEnabled(): Boolean = prefs.getBoolean(KEY_PRIVACY_MODE, false)

    fun saveCostConfirmation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COST_CONFIRMATION, enabled).apply()
    }

    fun isCostConfirmationEnabled(): Boolean = prefs.getBoolean(KEY_COST_CONFIRMATION, false)

    fun saveToolsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOOLS_ENABLED, enabled).apply()
    }

    fun areToolsEnabled(): Boolean = prefs.getBoolean(KEY_TOOLS_ENABLED, true)

    fun savePreferLocalVoice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_LOCAL_VOICE, enabled).apply()
    }

    fun isPreferLocalVoiceEnabled(): Boolean = prefs.getBoolean(KEY_PREFER_LOCAL_VOICE, false)

    fun saveLocalVoiceFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_VOICE_FALLBACK, enabled).apply()
    }

    fun isLocalVoiceFallbackEnabled(): Boolean = prefs.getBoolean(KEY_LOCAL_VOICE_FALLBACK, true)

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val PREFS_FILE_NAME = "alfred_secret_prefs"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_TOGETHER_API_KEY = "together_api_key"
        private const val KEY_TICKTICK_CLIENT_ID = "ticktick_client_id"
        private const val KEY_TICKTICK_CLIENT_SECRET = "ticktick_client_secret"
        private const val KEY_TICKTICK_ACCESS_TOKEN = "ticktick_access_token"
        private const val KEY_TICKTICK_REFRESH_TOKEN = "ticktick_refresh_token"
        private const val KEY_NOTION_CLIENT_ID = "notion_client_id"
        private const val KEY_NOTION_CLIENT_SECRET = "notion_client_secret"
        private const val KEY_NOTION_ACCESS_TOKEN = "notion_access_token"
        private const val KEY_NOTION_REFRESH_TOKEN = "notion_refresh_token"
        private const val KEY_NOTION_TOKEN_ENDPOINT = "notion_token_endpoint"
        private const val KEY_NOTION_EXPIRES_AT = "notion_expires_at"
        private const val KEY_NOTION_WORKSPACE_ID = "notion_workspace_id"
        private const val KEY_NOTION_REDIRECT_URI = "notion_redirect_uri"
        private const val KEY_NOTION_CODE_VERIFIER = "notion_code_verifier"
        private const val KEY_NOTION_OAUTH_STATE = "notion_oauth_state"
        private const val KEY_GITHUB_ACCESS_TOKEN = "github_access_token"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_SEARCH_TOOL = "selected_search_tool"
        private const val KEY_EFFICIENCY_MODE = "efficiency_mode"
        private const val KEY_PRIVACY_MODE = "privacy_mode"
        private const val KEY_COST_CONFIRMATION = "cost_confirmation"
        private const val KEY_TOOLS_ENABLED = "tools_enabled"
        private const val KEY_PREFER_LOCAL_VOICE = "prefer_local_voice"
        private const val KEY_LOCAL_VOICE_FALLBACK = "local_voice_fallback"
        private const val DEFAULT_MODEL_VAL = "openai/gpt-5.6-luna"
        private const val DEFAULT_SEARCH_TOOL_VAL = "perplexity"
        private val LEGACY_FREE_CHAT_MODEL_MIGRATIONS = mapOf(
            "google/gemma-4-31b-it:free" to "google/gemma-4-31b-it",
            "google/gemma-4-26b-a4b-it:free" to "google/gemma-4-26b-a4b-it",
            "qwen/qwen3-next-80b-a3b-instruct:free" to "qwen/qwen3-next-80b-a3b-instruct",
            "openrouter/free" to "google/gemma-4-26b-a4b-it"
        )
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

        private fun normalizeChatModel(model: String): String {
            val trimmed = model.trim()
            return LEGACY_FREE_CHAT_MODEL_MIGRATIONS[trimmed] ?: trimmed
        }

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

