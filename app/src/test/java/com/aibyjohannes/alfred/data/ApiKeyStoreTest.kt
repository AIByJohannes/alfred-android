package com.aibyjohannes.alfred.data

import android.content.SharedPreferences
import android.util.Log
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiKeyStoreTest {

    private val sharedPrefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, String?>()
    private val booleanValues = mutableMapOf<String, Boolean>()

    private lateinit var apiKeyStore: ApiKeyStore

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        values.clear()
        booleanValues.clear()
        every { sharedPrefs.getString(any(), any()) } answers {
            values[firstArg()] ?: secondArg()
        }
        every { sharedPrefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg())
            editor
        }
        every { editor.apply() } just Runs
        every { sharedPrefs.getBoolean(any(), any()) } answers {
            booleanValues[firstArg()] ?: secondArg()
        }
        every { editor.putBoolean(any(), any()) } answers {
            booleanValues[firstArg()] = secondArg()
            editor
        }

        apiKeyStore = ApiKeyStore(sharedPrefs, fallbackApiKey = "")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `stored API key is trimmed and reported as configured`() {
        apiKeyStore.saveOpenRouterKey("  test-key  ")

        assertEquals("test-key", apiKeyStore.loadOpenRouterKey())
        assertTrue(apiKeyStore.hasApiKey())
    }

    @Test
    fun `blank stored API key is absent and configured fallback is used`() {
        val storeWithFallback = ApiKeyStore(sharedPrefs, fallbackApiKey = "  fallback-key  ")

        storeWithFallback.saveOpenRouterKey("   ")
        assertEquals("fallback-key", storeWithFallback.loadOpenRouterKey())

        storeWithFallback.saveOpenRouterKey("saved-key")
        storeWithFallback.clearApiKey()
        assertEquals("fallback-key", storeWithFallback.loadOpenRouterKey())
    }

    @Test
    fun `chat model defaults and custom selection round trip`() {
        assertEquals("openai/gpt-5.6-luna", apiKeyStore.loadModel())

        apiKeyStore.saveModel("  custom-model  ")

        assertEquals("custom-model", apiKeyStore.loadModel())
    }

    @Test
    fun `efficiency and privacy modes default off and round trip`() {
        assertFalse(apiKeyStore.isEfficiencyModeEnabled())
        assertFalse(apiKeyStore.isPrivacyModeEnabled())
        assertFalse(apiKeyStore.isCostConfirmationEnabled())
        assertTrue(apiKeyStore.areToolsEnabled())

        apiKeyStore.saveEfficiencyMode(true)
        apiKeyStore.savePrivacyMode(true)
        apiKeyStore.saveCostConfirmation(true)
        apiKeyStore.saveToolsEnabled(false)

        assertTrue(apiKeyStore.isEfficiencyModeEnabled())
        assertTrue(apiKeyStore.isPrivacyModeEnabled())
        assertTrue(apiKeyStore.isCostConfirmationEnabled())
        assertFalse(apiKeyStore.areToolsEnabled())
    }

    @Test
    fun `legacy free chat model selections migrate to paid equivalents`() {
        mapOf(
            "google/gemma-4-31b-it:free" to "google/gemma-4-31b-it",
            "google/gemma-4-26b-a4b-it:free" to "google/gemma-4-26b-a4b-it",
            "qwen/qwen3-next-80b-a3b-instruct:free" to "qwen/qwen3-next-80b-a3b-instruct",
            "openrouter/free" to "google/gemma-4-26b-a4b-it"
        ).forEach { (legacy, current) ->
            apiKeyStore.saveModel(legacy)

            assertEquals(current, apiKeyStore.loadModel())
        }
    }

    @Test
    fun `speech models default and legacy TTS models migrate`() {
        assertEquals("openai/whisper-1", apiKeyStore.loadSttModel())
        assertEquals("hexgrad/kokoro-82m", apiKeyStore.loadTtsModel())

        apiKeyStore.saveSttModel("  custom-stt  ")
        apiKeyStore.saveTtsModel("custom-tts")
        assertEquals("custom-stt", apiKeyStore.loadSttModel())
        assertEquals("custom-tts", apiKeyStore.loadTtsModel())

        apiKeyStore.saveTtsModel("openai/tts-1-hd")
        assertEquals("hexgrad/kokoro-82m", apiKeyStore.loadTtsModel())
        apiKeyStore.saveTtsModel("   ")
        assertEquals("hexgrad/kokoro-82m", apiKeyStore.loadTtsModel())
    }

    @Test
    fun `TTS voice defaults and legacy voices migrate`() {
        assertEquals("af_alloy", apiKeyStore.loadTtsVoice())

        apiKeyStore.saveTtsVoice(" custom-voice ")
        assertEquals("custom-voice", apiKeyStore.loadTtsVoice())

        mapOf("alloy" to "af_alloy", "echo" to "am_echo", "shimmer" to "af_sky").forEach { (legacy, current) ->
            apiKeyStore.saveTtsVoice(legacy)
            assertEquals(current, apiKeyStore.loadTtsVoice())
        }
    }

    @Test
    fun `TickTick credentials round trip and clear as one unit`() {
        assertFalse(apiKeyStore.hasTickTickAuth())

        apiKeyStore.saveTickTickClientId(" client-id ")
        apiKeyStore.saveTickTickClientSecret(" client-secret ")
        apiKeyStore.saveTickTickAccessToken(" access-token ")
        apiKeyStore.saveTickTickRefreshToken(" refresh-token ")

        assertEquals("client-id", apiKeyStore.loadTickTickClientId())
        assertEquals("client-secret", apiKeyStore.loadTickTickClientSecret())
        assertEquals("access-token", apiKeyStore.loadTickTickAccessToken())
        assertEquals("refresh-token", apiKeyStore.loadTickTickRefreshToken())
        assertTrue(apiKeyStore.hasTickTickAuth())

        apiKeyStore.clearTickTickCredentials()

        assertNull(apiKeyStore.loadTickTickClientId())
        assertNull(apiKeyStore.loadTickTickClientSecret())
        assertNull(apiKeyStore.loadTickTickAccessToken())
        assertNull(apiKeyStore.loadTickTickRefreshToken())
        assertFalse(apiKeyStore.hasTickTickAuth())
    }

    @Test
    fun `GitHub token round trips and clears`() {
        assertFalse(apiKeyStore.hasGitHubAuth())
        apiKeyStore.saveGitHubAccessToken("  github-token  ")
        assertEquals("github-token", apiKeyStore.loadGitHubAccessToken())
        assertTrue(apiKeyStore.hasGitHubAuth())
        apiKeyStore.clearGitHubCredentials()
        assertNull(apiKeyStore.loadGitHubAccessToken())
        assertFalse(apiKeyStore.hasGitHubAuth())
    }

    @Test
    fun `Together key round trips and clears`() {
        assertFalse(apiKeyStore.hasTogetherApiKey())
        apiKeyStore.saveTogetherApiKey("  together-key  ")
        assertEquals("together-key", apiKeyStore.loadTogetherApiKey())
        assertTrue(apiKeyStore.hasTogetherApiKey())
        apiKeyStore.clearTogetherApiKey()
        assertNull(apiKeyStore.loadTogetherApiKey())
        assertFalse(apiKeyStore.hasTogetherApiKey())
    }

    @Test
    fun `preference read failures return safe absent values and defaults`() {
        val throwingPrefs = mockk<SharedPreferences>()
        every { throwingPrefs.getString(any(), any()) } throws IllegalStateException("read failed")
        val store = ApiKeyStore(throwingPrefs, fallbackApiKey = "fallback-key")

        assertNull(store.loadOpenRouterKey())
        assertEquals("openai/gpt-5.6-luna", store.loadModel())
        assertEquals("openai/whisper-1", store.loadSttModel())
        assertEquals("hexgrad/kokoro-82m", store.loadTtsModel())
        assertEquals("af_alloy", store.loadTtsVoice())
        assertNull(store.loadTickTickAccessToken())
        verify(atLeast = 6) { Log.e("ApiKeyStore", any(), any()) }
    }

    @Test
    fun `preference write failures are contained and logged`() {
        val throwingPrefs = mockk<SharedPreferences>()
        val throwingEditor = mockk<SharedPreferences.Editor>()
        every { throwingPrefs.edit() } returns throwingEditor
        every { throwingEditor.putString(any(), any()) } throws IllegalStateException("write failed")
        every { throwingEditor.remove(any()) } throws IllegalStateException("remove failed")
        val store = ApiKeyStore(throwingPrefs, fallbackApiKey = "")

        store.saveOpenRouterKey("key")
        store.saveModel("model")
        store.saveTickTickAccessToken("token")
        store.clearApiKey()
        store.clearTickTickCredentials()

        verify(atLeast = 5) { Log.e("ApiKeyStore", any(), any()) }
    }
}
