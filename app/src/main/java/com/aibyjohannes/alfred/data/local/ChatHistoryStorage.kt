package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
            current = findChild(context, current, segment)?.takeIf { it.isDirectory } ?: return null
        }
        return findChild(context, current, path.last())
    }

    private fun getOrCreateFile(path: List<String>, mimeType: String): DocumentFile {
        require(path.isNotEmpty()) { "Path must not be empty" }
        val current = getOrCreateDirectory(path.dropLast(1))
        return findChild(context, current, path.last())?.takeIf { it.isFile }
            ?: current.createFile(mimeType, path.last())
            ?: throw IllegalStateException("Could not create file ${path.last()}")
    }

    private fun getOrCreateDirectory(path: List<String>): DocumentFile {
        var current = rootDirectory
        for (segment in path) {
            current = findChild(context, current, segment)?.takeIf { it.isDirectory }
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
            
            val isAlreadyRoot = parent.name?.equals(APP_FOLDER_NAME, ignoreCase = true) == true ||
                    hasMetadataFile(context, parent)

            val root = if (isAlreadyRoot) {
                parent
            } else {
                val existing = findChild(context, parent, APP_FOLDER_NAME)
                existing?.takeIf { it.isDirectory }
                    ?: parent.createDirectory(APP_FOLDER_NAME)
                    ?: throw IllegalStateException("Could not create $APP_FOLDER_NAME folder")
            }
            return DocumentChatHistoryStorage(context.applicationContext, root)
        }

        private fun hasMetadataFile(context: Context, parent: DocumentFile): Boolean {
            return findChild(context, parent, "metadata.json") != null
        }

        private fun findChild(context: Context, parent: DocumentFile, displayName: String): DocumentFile? {
            try {
                val file = parent.findFile(displayName)
                if (file != null) return file
            } catch (e: Exception) {
                // Ignore and fall back to direct content resolver query
            }

            val resolver = context.contentResolver
            try {
                val parentDocId = DocumentsContract.getDocumentId(parent.uri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    parent.uri,
                    parentDocId
                )
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        if (displayName.equals(name, ignoreCase = true)) {
                            val docId = cursor.getString(idIndex)
                            val mimeType = cursor.getString(mimeIndex)
                            val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, docId)
                            return if (isDir) {
                                DocumentFile.fromTreeUri(context, fileUri)
                            } else {
                                DocumentFile.fromSingleUri(context, fileUri)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return null
        }
    }
}
