package com.aibyjohannes.alfred

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented tests for the Alfred app.
 * These tests run on an Android device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class AlfredInstrumentedTest {

    @Test
    fun appPackage_isCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.aibyjohannes.alfred", appContext.packageName)
    }

    @Test
    fun appContext_isNotNull() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)
    }

    @Test
    fun appName_matchesExpected() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = appContext.getString(appContext.applicationInfo.labelRes)
        // The app name should be A.L.F.R.E.D. or Alfred
        assertTrue(
            "App name should be related to Alfred",
            appName.contains("Alfred", ignoreCase = true) || 
            appName.contains("A.L.F.R.E.D", ignoreCase = true) ||
            appName.contains("ALFRED", ignoreCase = true)
        )
    }
}