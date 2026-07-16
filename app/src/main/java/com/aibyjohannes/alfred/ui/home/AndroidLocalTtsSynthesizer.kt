package com.aibyjohannes.alfred.ui.home

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class AndroidLocalTtsSynthesizer(private val context: Context) {
    suspend fun synthesize(text: String, outputFile: File): Result<File> = suspendCancellableCoroutine { continuation ->
        if (text.isBlank()) {
            continuation.resume(Result.failure(IllegalArgumentException("Cannot synthesize empty text.")))
            return@suspendCancellableCoroutine
        }
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                continuation.resume(Result.failure(IllegalStateException("Android offline text-to-speech is unavailable.")))
                return@TextToSpeech
            }
            val locale = Locale.getDefault()
            tts.language = locale
            tts.voices
                ?.filter { !it.isNetworkConnectionRequired }
                ?.sortedByDescending { it.locale.language == locale.language }
                ?.firstOrNull()
                ?.let { tts.voice = it }
            if (tts.voice?.isNetworkConnectionRequired != false) {
                tts.shutdown()
                continuation.resume(Result.failure(IllegalStateException("No offline voice is installed for ${locale.displayLanguage}.")))
                return@TextToSpeech
            }
            outputFile.parentFile?.mkdirs()
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    tts.shutdown()
                    if (continuation.isActive) continuation.resume(Result.success(outputFile))
                }
                @Deprecated("Deprecated by Android")
                override fun onError(id: String?) {
                    tts.shutdown()
                    if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Offline speech synthesis failed.")))
                }
            })
            val result = tts.synthesizeToFile(text, Bundle(), outputFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                tts.shutdown()
                if (continuation.isActive) continuation.resume(Result.failure(IllegalStateException("Offline speech synthesis could not start.")))
            }
        }
        continuation.invokeOnCancellation { engine?.shutdown() }
    }
}
