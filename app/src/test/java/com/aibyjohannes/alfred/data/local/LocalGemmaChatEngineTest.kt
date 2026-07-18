package com.aibyjohannes.alfred.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalGemmaChatEngineTest {

    @Test
    fun `failed GPU initialization falls back to CPU without closing uninitialized engine`() {
        val gpuFailure = IllegalStateException("GPU initialization failed")
        val gpuEngine = FakeEngine(initializationFailure = gpuFailure)
        val cpuEngine = FakeEngine()

        val initialized = initializeWithCpuFallback(
            createGpuEngine = { gpuEngine },
            createCpuEngine = { cpuEngine }
        )

        assertSame(cpuEngine, initialized)
        assertTrue(gpuEngine.initializeCalled)
        assertFalse(gpuEngine.closeCalled)
        assertTrue(cpuEngine.initializeCalled)
    }

    @Test
    fun `backend failures preserve CPU error and suppress GPU error`() {
        val gpuFailure = IllegalStateException("GPU initialization failed")
        val cpuFailure = IllegalStateException("CPU initialization failed")
        val gpuEngine = FakeEngine(initializationFailure = gpuFailure)
        val cpuEngine = FakeEngine(initializationFailure = cpuFailure)

        val thrown = assertThrows(IllegalStateException::class.java) {
            initializeWithCpuFallback(
                createGpuEngine = { gpuEngine },
                createCpuEngine = { cpuEngine }
            )
        }

        assertSame(cpuFailure, thrown)
        assertTrue(thrown.suppressed.contains(gpuFailure))
        assertFalse(gpuEngine.closeCalled)
        assertFalse(cpuEngine.closeCalled)
    }

    private class FakeEngine(
        private val initializationFailure: Throwable? = null
    ) : LocalGemmaEngineHandle {
        var initializeCalled = false
        var closeCalled = false
        private var initialized = false

        override fun initialize() {
            initializeCalled = true
            initializationFailure?.let { throw it }
            initialized = true
        }

        override fun isInitialized(): Boolean = initialized

        override fun close() {
            closeCalled = true
            initialized = false
        }
    }
}
