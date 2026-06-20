package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

interface ChatHistoryStorage {
    fun ensureReady()
    fun ensureDirectory(path: List<String>)
    fun readText(path: List<String>): StorageReadResult
    fun listChildren(path: List<String>): List<StorageEntry>
    fun createFileExclusive(path: List<String>, mimeType: String = "text/plain")
    fun writeText(path: List<String>, text: String, mimeType: String = "text/plain")
    fun appendLine(path: List<String>, line: String, mimeType: String = "text/plain")
    fun delete(path: List<String>)
}

sealed interface StorageReadResult {
    data class Found(val text: String) : StorageReadResult
    data object Missing : StorageReadResult
}

data class StorageEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

class FileChatHistoryStorage(
    private val rootDir: File
) : ChatHistoryStorage {
    override fun ensureReady() {
        check(rootDir.isDirectory || rootDir.mkdirs()) {
            "Chat history root is not a usable directory: ${rootDir.path}"
        }
    }

    override fun ensureDirectory(path: List<String>) {
        val directory = directoryAt(path)
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create directory: ${path.joinToString("/")}"
        }
    }

    override fun readText(path: List<String>): StorageReadResult {
        val file = fileAt(path)
        return if (file.exists()) {
            StorageReadResult.Found(file.readText())
        } else {
            StorageReadResult.Missing
        }
    }

    override fun listChildren(path: List<String>): List<StorageEntry> {
        val directory = if (path.isEmpty()) rootDir else directoryAt(path)
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "Not a directory: ${path.joinToString("/")}" }
        val children = directory.listFiles()
            ?: throw IllegalStateException("Could not list directory: ${path.joinToString("/")}")
        return children.map { child ->
            StorageEntry(child.name, child.isDirectory, if (child.isFile) child.length() else 0L)
        }
    }

    override fun createFileExclusive(path: List<String>, mimeType: String) {
        val file = fileAt(path)
        file.parentFile?.mkdirs()
        check(file.createNewFile()) { "File already exists: ${path.joinToString("/")}" }
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
        if (!file.exists()) return
        val deleted = if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
        check(deleted) { "Could not delete ${path.joinToString("/")}" }
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

    override fun readText(path: List<String>): StorageReadResult {
        val file = findFile(path) ?: return StorageReadResult.Missing
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Could not open ${path.last()} for reading")
        return StorageReadResult.Found(stream.bufferedReader().use { it.readText() })
    }

    override fun listChildren(path: List<String>): List<StorageEntry> {
        val directory = if (path.isEmpty()) rootDirectory else findFile(path)
            ?: return emptyList()
        check(directory.isDirectory) { "Not a directory: ${path.joinToString("/")}" }
        return queryChildren(context, directory)
    }

    override fun createFileExclusive(path: List<String>, mimeType: String) {
        require(path.isNotEmpty()) { "Path must not be empty" }
        val current = getOrCreateDirectory(path.dropLast(1))
        check(findChild(context, current, path.last()) == null) {
            "File already exists: ${path.joinToString("/")}"
        }
        current.createFile(mimeType, path.last())
            ?: throw IllegalStateException("Could not create file ${path.last()}")
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
        val file = findFile(path) ?: return
        check(file.delete()) { "Could not delete ${path.joinToString("/")}" }
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
            } catch (_: Exception) {
                // Fall back to a direct provider query, which either succeeds or throws.
            }

            val parentDocId = DocumentsContract.getDocumentId(parent.uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentDocId)
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            ) ?: throw IllegalStateException("Could not query children of ${parent.name}")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex)
                    if (displayName.equals(name, ignoreCase = true)) {
                        val docId = it.getString(idIndex)
                        val mimeType = it.getString(mimeIndex)
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
            return null
        }

        private fun queryChildren(context: Context, parent: DocumentFile): List<StorageEntry> {
            val parentDocId = DocumentsContract.getDocumentId(parent.uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentDocId)
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            ) ?: throw IllegalStateException("Could not query children of ${parent.name}")
            return cursor.use {
                val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                buildList {
                    while (it.moveToNext()) {
                        add(
                            StorageEntry(
                                name = it.getString(nameIndex),
                                isDirectory = it.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
                                size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L
                            )
                        )
                    }
                }
            }
        }
    }
}
