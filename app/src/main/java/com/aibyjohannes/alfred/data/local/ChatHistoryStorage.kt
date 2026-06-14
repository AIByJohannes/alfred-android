package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

interface ChatHistoryStorage {
    fun ensureReady()
    fun ensureDirectory(path: List<String>)
    fun readText(path: List<String>): String?
    fun writeText(path: List<String>, text: String, mimeType: String = "text/plain")
    fun appendLine(path: List<String>, line: String, mimeType: String = "text/plain")
    fun delete(path: List<String>)
}

class FileChatHistoryStorage(
    private val rootDir: File
) : ChatHistoryStorage {
    override fun ensureReady() {
        rootDir.mkdirs()
    }

    override fun ensureDirectory(path: List<String>) {
        directoryAt(path).mkdirs()
    }

    override fun readText(path: List<String>): String? {
        val file = fileAt(path)
        return if (file.exists()) file.readText() else null
    }

    override fun writeText(path: List<String>, text: String, mimeType: String) {
        val file = fileAt(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun appendLine(path: List<String>, line: String, mimeType: String) {
        val file = fileAt(path)
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    override fun delete(path: List<String>) {
        val file = fileAt(path)
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    private fun fileAt(path: List<String>): File {
        require(path.isNotEmpty()) { "Path must not be empty" }
        return path.fold(rootDir) { current, segment -> File(current, segment) }
    }

    private fun directoryAt(path: List<String>): File {
        return path.fold(rootDir) { current, segment -> File(current, segment) }
    }
}

class DocumentChatHistoryStorage private constructor(
    private val context: Context,
    private val rootDirectory: DocumentFile
) : ChatHistoryStorage {
    override fun ensureReady() {
        require(rootDirectory.exists() && rootDirectory.isDirectory) {
            "Chat history folder is unavailable"
        }
    }

    override fun ensureDirectory(path: List<String>) {
        getOrCreateDirectory(path)
    }

    override fun readText(path: List<String>): String? {
        val file = findFile(path) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
    }

    override fun writeText(path: List<String>, text: String, mimeType: String) {
        val file = getOrCreateFile(path, mimeType)
        val stream = context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IllegalStateException("Could not open ${path.last()} for writing")
        stream.bufferedWriter().use { it.write(text) }
    }

    override fun appendLine(path: List<String>, line: String, mimeType: String) {
        val file = getOrCreateFile(path, mimeType)
        val stream = context.contentResolver.openOutputStream(file.uri, "wa")
            ?: throw IllegalStateException("Could not open ${path.last()} for appending")
        stream.bufferedWriter().use {
            it.write(line)
            it.newLine()
        }
    }

    override fun delete(path: List<String>) {
        findFile(path)?.delete()
    }

    private fun findFile(path: List<String>): DocumentFile? {
        require(path.isNotEmpty()) { "Path must not be empty" }
        var current = rootDirectory
        for (segment in path.dropLast(1)) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current.findFile(path.last())
    }

    private fun getOrCreateFile(path: List<String>, mimeType: String): DocumentFile {
        require(path.isNotEmpty()) { "Path must not be empty" }
        val current = getOrCreateDirectory(path.dropLast(1))
        return current.findFile(path.last())?.takeIf { it.isFile }
            ?: current.createFile(mimeType, path.last())
            ?: throw IllegalStateException("Could not create file ${path.last()}")
    }

    private fun getOrCreateDirectory(path: List<String>): DocumentFile {
        var current = rootDirectory
        for (segment in path) {
            current = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.createDirectory(segment)
                ?: throw IllegalStateException("Could not create folder $segment")
        }
        return current
    }

    companion object {
        const val APP_FOLDER_NAME = "Alfred"

        fun fromParentUri(context: Context, parentUri: Uri): DocumentChatHistoryStorage {
            val parent = DocumentFile.fromTreeUri(context, parentUri)
                ?: throw IllegalArgumentException("Invalid chat history folder")
            val root = parent.findFile(APP_FOLDER_NAME)?.takeIf { it.isDirectory }
                ?: parent.createDirectory(APP_FOLDER_NAME)
                ?: throw IllegalStateException("Could not create $APP_FOLDER_NAME folder")
            return DocumentChatHistoryStorage(context.applicationContext, root)
        }
    }
}
