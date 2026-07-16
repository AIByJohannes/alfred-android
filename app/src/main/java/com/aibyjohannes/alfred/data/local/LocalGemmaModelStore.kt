package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalGemmaModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models")
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)

    fun installedModelPath(): String? = modelFile
        .takeIf { it.isFile && it.length() > 0L }
        ?.absolutePath

    fun installedModelSizeBytes(): Long = modelFile.takeIf(File::isFile)?.length() ?: 0L

    suspend fun importModel(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            modelDirectory.mkdirs()
            val temporaryFile = File(modelDirectory, "$MODEL_FILE_NAME.partial")
            temporaryFile.delete()
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: error("The selected model file could not be opened.")
                input.use { source ->
                    temporaryFile.outputStream().buffered().use { destination ->
                        source.copyTo(destination, bufferSize = COPY_BUFFER_SIZE)
                    }
                }
                require(temporaryFile.length() > 0L) { "The selected model file is empty." }
                if (modelFile.exists() && !modelFile.delete()) {
                    error("The previous local model could not be replaced.")
                }
                check(temporaryFile.renameTo(modelFile)) { "The imported model could not be installed." }
                modelFile
            } catch (error: Throwable) {
                temporaryFile.delete()
                throw error
            }
        }
    }

    companion object {
        const val LOCAL_MODEL_ID = "local/gemma-3n-e2b"
        private const val MODEL_FILE_NAME = "gemma-3n-e2b.litertlm"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
    }
}
