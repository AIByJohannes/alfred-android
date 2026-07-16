package com.aibyjohannes.alfred.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AndroidLocalSpeechRecognizer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    val isAvailable: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!isAvailable) {
            onError("No on-device speech recognition model is installed.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    isListening = false
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    if (text.isBlank()) onError("No speech detected.") else onResult(text)
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (error != SpeechRecognizer.ERROR_CLIENT) onError(errorMessage(error))
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(recognitionIntent())
        }
        isListening = true
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        isListening = false
        recognizer?.cancel()
    }

    fun requestModelDownload() {
        if (!isAvailable) {
            SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                triggerModelDownload(recognitionIntent())
                destroy()
            }
        } else {
            recognizer?.triggerModelDownload(recognitionIntent())
                ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).apply {
                    triggerModelDownload(recognitionIntent())
                    destroy()
                }
        }
    }

    override fun close() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "On-device speech audio error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The installed recognizer could not run offline."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "On-device speech recognition is busy."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "On-device speech service disconnected."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The current language is not supported on device."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download the on-device model for the current language."
        else -> "On-device speech recognition failed (error $error)."
    }
}
