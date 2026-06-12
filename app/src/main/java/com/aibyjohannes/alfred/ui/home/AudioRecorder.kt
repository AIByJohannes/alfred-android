package com.aibyjohannes.alfred.ui.home

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null

    companion object {
        private const val TAG = "AudioRecorder"
    }

    fun startRecording(): File? {
        return try {
            val file = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
            currentFile = file

            // Since minSdk is 35, we can use MediaRecorder(context) directly
            val recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            currentFile?.delete()
            currentFile = null
            mediaRecorder = null
            null
        }
    }

    fun stopRecording(): File? {
        val recorder = mediaRecorder ?: return null
        val file = currentFile
        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording failed (could be too short)", e)
            file?.delete()
            mediaRecorder = null
            currentFile = null
            return null
        } finally {
            recorder.release()
        }
        mediaRecorder = null
        currentFile = null
        return file
    }

    fun cancelRecording() {
        val recorder = mediaRecorder
        if (recorder != null) {
            try {
                recorder.stop()
            } catch (e: Exception) {
                // Ignore
            } finally {
                recorder.release()
            }
        }
        mediaRecorder = null
        currentFile?.delete()
        currentFile = null
    }
}
