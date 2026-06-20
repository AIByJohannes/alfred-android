package com.aibyjohannes.alfred.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiKeyStoreTest {

    private val context = mockk<Context>()
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var apiKeyStore: ApiKeyStore
    private val store = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.d(any(), any()) } returns 0

        store.clear()
        
        // Mock getSharedPreferences to return our mocked sharedPrefs
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        
        // Mock SharedPreferences behavior
        every { sharedPrefs.edit() } returns editor
        every { sharedPrefs.getString(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<String?>()
            (store[key] as? String) ?: default
        }
        
        // Mock Editor behavior
        every { editor.putString(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String?>()
            store[key] = value
            editor
        }
        every { editor.remove(any()) } answers {
            val key = firstArg<String>()
            store.remove(key)
            editor
        }
        every { editor.apply() } just Runs
        
        apiKeyStore = ApiKeyStore(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test save and load OpenRouter key`() {
        apiKeyStore.saveOpenRouterKey("  test-key  ")
        assertEquals("test-key", apiKeyStore.loadOpenRouterKey())
        assertTrue(apiKeyStore.hasApiKey())
    }

    @Test
    fun `test clear API key`() {
        apiKeyStore.saveOpenRouterKey("test-key")
        apiKeyStore.clearApiKey()
        val expected = if (com.aibyjohannes.alfred.BuildConfig.OPENROUTER_API_KEY.isNotBlank()) {
            com.aibyjohannes.alfred.BuildConfig.OPENROUTER_API_KEY
        } else {
            null
        }
        assertEquals(expected, apiKeyStore.loadOpenRouterKey())
        assertEquals(expected != null, apiKeyStore.hasApiKey())
    }

    @Test
    fun `test save and load model`() {
        // Default value
        assertEquals("google/gemini-3.5-flash", apiKeyStore.loadModel())
        
        // Custom value
        apiKeyStore.saveModel("custom-model")
        assertEquals("custom-model", apiKeyStore.loadModel())
    }

    @Test
    fun `test save and load STT model`() {
        // Default value
        assertEquals("openai/whisper-1", apiKeyStore.loadSttModel())
        
        // Custom value
        apiKeyStore.saveSttModel("custom-stt")
        assertEquals("custom-stt", apiKeyStore.loadSttModel())
    }

    @Test
    fun `test save and load TTS model`() {
        // Default value
        assertEquals("hexgrad/kokoro-82m", apiKeyStore.loadTtsModel())
        
        // Custom value
        apiKeyStore.saveTtsModel("custom-tts")
        assertEquals("custom-tts", apiKeyStore.loadTtsModel())
        
        // Legacy model migrations
        apiKeyStore.saveTtsModel("openai/tts-1")
        assertEquals("hexgrad/kokoro-82m", apiKeyStore.loadTtsModel())
        
        apiKeyStore.saveTtsModel("openai/tts-1-hd")
        assertEquals("hexgrad/kokoro-82m", apiKeyStore.loadTtsModel())
    }

    @Test
    fun `test save and load TTS voice`() {
        // Default value
        assertEquals("af_alloy", apiKeyStore.loadTtsVoice())
        
        // Custom value
        apiKeyStore.saveTtsVoice("custom-voice")
        assertEquals("custom-voice", apiKeyStore.loadTtsVoice())
        
        // Legacy voice migration
        apiKeyStore.saveTtsVoice("alloy")
        assertEquals("af_alloy", apiKeyStore.loadTtsVoice())
        
        apiKeyStore.saveTtsVoice("echo")
        assertEquals("am_echo", apiKeyStore.loadTtsVoice())
        
        apiKeyStore.saveTtsVoice("shimmer")
        assertEquals("af_sky", apiKeyStore.loadTtsVoice())
    }

    @Test
    fun `test save and load TickTick credentials`() {
        assertNull(apiKeyStore.loadTickTickClientId())
        assertNull(apiKeyStore.loadTickTickClientSecret())
        assertNull(apiKeyStore.loadTickTickAccessToken())
        assertNull(apiKeyStore.loadTickTickRefreshToken())
        assertFalse(apiKeyStore.hasTickTickAuth())
        
        apiKeyStore.saveTickTickClientId("client-id")
        apiKeyStore.saveTickTickClientSecret("client-secret")
        apiKeyStore.saveTickTickAccessToken("access-token")
        apiKeyStore.saveTickTickRefreshToken("refresh-token")
        
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
    fun `test all methods exception paths`() {
        val throwingPrefs = mockk<SharedPreferences>()
        val throwingEditor = mockk<SharedPreferences.Editor>()
        every { throwingPrefs.edit() } returns throwingEditor
        
        every { throwingEditor.putString(any(), any()) } throws RuntimeException("write error")
        every { throwingEditor.remove(any()) } throws RuntimeException("remove error")
        every { throwingPrefs.getString(any(), any()) } throws RuntimeException("read error")
        
        every { context.getSharedPreferences(any(), any()) } returns throwingPrefs
        val throwingStore = ApiKeyStore(context)
        
        throwingStore.saveOpenRouterKey("key")
        assertNull(throwingStore.loadOpenRouterKey())
        throwingStore.clearApiKey()
        
        throwingStore.saveModel("model")
        assertEquals("google/gemini-3.5-flash", throwingStore.loadModel())
        
        throwingStore.saveSttModel("stt")
        assertEquals("openai/whisper-1", throwingStore.loadSttModel())
        
        throwingStore.saveTtsModel("tts")
        assertEquals("hexgrad/kokoro-82m", throwingStore.loadTtsModel())
        
        throwingStore.saveTtsVoice("voice")
        assertEquals("af_alloy", throwingStore.loadTtsVoice())
        
        throwingStore.saveTickTickClientId("id")
        throwingStore.saveTickTickClientSecret("secret")
        throwingStore.saveTickTickAccessToken("token")
        throwingStore.saveTickTickRefreshToken("refresh")
        
        assertNull(throwingStore.loadTickTickClientId())
        assertNull(throwingStore.loadTickTickClientSecret())
        assertNull(throwingStore.loadTickTickAccessToken())
        assertNull(throwingStore.loadTickTickRefreshToken())
        
        throwingStore.clearTickTickCredentials()
        
        verify(atLeast = 1) { Log.e("ApiKeyStore", any(), any()) }
    }
}
