package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

interface ChatHistoryStorage {
    val supportsAtomicReplace: Boolean
    fun ensureReady()
    fun ensureDirectory(path: List<String>)
    fun readText(path: List<String>): StorageReadResult
    fun listChildren(path: List<String>): List<StorageEntry>
    fun createFileExclusive(path: List<String>, mimeType: String = "text/plain")
    fun writeText(path: List<String>, text: String, mimeType: String = "text/plain")
    fun appendLine(path: List<String>, line: String, mimeType: String = "text/plain")
    fun replaceTextVerified(path: List<String>, text: String, mimeType: String = "text/plain")
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
    override val supportsAtomicReplace: Boolean = true
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
        FileOutputStream(file, false).use { stream ->
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            writer.write(text)
            writer.flush()
            stream.fd.sync()
        }
    }

    override fun appendLine(path: List<String>, line: String, mimeType: String) {
        val file = fileAt(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { stream ->
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            writer.write(line)
            writer.write("\n")
            writer.flush()
            stream.fd.sync()
        }
        check(file.readText().endsWith("$line\n")) { "Append verification failed: ${path.joinToString("/")}" }
    }

    override fun replaceTextVerified(path: List<String>, text: String, mimeType: String) {
        val target = fileAt(path)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary, false).use { stream ->
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            writer.write(text)
            writer.flush()
            stream.fd.sync()
        }
        check(temporary.readText() == text) { "Temporary write verification failed: ${path.joinToString("/")}" }
        runCatching {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(target.readText() == text) { "Replacement verification failed: ${path.joinToString("/")}" }
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
    override val supportsAtomicReplace: Boolean = false
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
        writeAndSync(file, text, "wt")
        check((readText(path) as? StorageReadResult.Found)?.text == text) {
            "Write verification failed: ${path.joinToString("/")}"
        }
    }

    override fun appendLine(path: List<String>, line: String, mimeType: String) {
        val file = getOrCreateFile(path, mimeType)
        writeAndSync(file, "$line\n", "wa")
        val written = (readText(path) as? StorageReadResult.Found)?.text
        check(written?.endsWith("$line\n") == true) {
            "Append verification failed: ${path.joinToString("/")}"
        }
    }

    override fun replaceTextVerified(path: List<String>, text: String, mimeType: String) {
        require(path.isNotEmpty()) { "Path must not be empty" }
        val directory = getOrCreateDirectory(path.dropLast(1))
        val targetName = path.last()
        val target = findChild(context, directory, targetName)
        val temporaryName = ".$targetName.${UUID.randomUUID()}.tmp"
        val temporary = directory.createFile(mimeType, temporaryName)
            ?: throw IllegalStateException("Could not create migration file $temporaryName")
        writeAndSync(temporary, text, "wt")
        check(readDocumentText(temporary) == text) { "Temporary write verification failed: $targetName" }

        if (target == null) {
            renameDocument(temporary, targetName)
            val replaced = findChild(context, directory, targetName)
                ?: throw IllegalStateException("Replacement disappeared: $targetName")
            check(readDocumentText(replaced) == text) { "Replacement verification failed: $targetName" }
        } else {
            val backupName = ".$targetName.${UUID.randomUUID()}.legacy"
            val backup = renameDocument(target, backupName)
            try {
                renameDocument(temporary, targetName)
                val replaced = findChild(context, directory, targetName)
                    ?: throw IllegalStateException("Replacement disappeared: $targetName")
                check(readDocumentText(replaced) == text) { "Replacement verification failed: $targetName" }
                check(backup.delete()) { "Could not remove migration backup $backupName" }
            } catch (error: Exception) {
                runCatching { findChild(context, directory, targetName)?.delete() }
                runCatching { renameDocument(backup, targetName) }
                throw error
            }
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

    private fun writeAndSync(file: DocumentFile, text: String, mode: String) {
        val descriptor = context.contentResolver.openFileDescriptor(file.uri, mode)
            ?: throw IllegalStateException("Could not open ${file.name} for writing")
        descriptor.use { parcel ->
            FileOutputStream(parcel.fileDescriptor).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                runCatching { stream.fd.sync() }
            }
        }
    }

    private fun readDocumentText(file: DocumentFile): String {
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Could not open ${file.name} for reading")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun renameDocument(file: DocumentFile, newName: String): DocumentFile {
        val renamedUri = DocumentsContract.renameDocument(context.contentResolver, file.uri, newName)
            ?: throw IllegalStateException("Could not rename ${file.name} to $newName")
        return DocumentFile.fromSingleUri(context, renamedUri)
            ?: throw IllegalStateException("Could not resolve renamed document $newName")
    }

    companion object {
        const val APP_FOLDER_NAME = "Alfred"

        fun fromParentUri(context: Context, parentUri: Uri): DocumentChatHistoryStorage {
            val parent = DocumentFile.fromTreeUri(context, parentUri)
                ?: throw IllegalArgumentException("Invalid chat history folder")
            
            val isAlreadyRoot = parent.name?.equals(APP_FOLDER_NAME, ignoreCase = true) == true ||
                    hasMetadataFile(context, parent) || hasEventStorage(context, parent)

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

        private fun hasEventStorage(context: Context, parent: DocumentFile): Boolean = runCatching {
            queryChildren(context, parent).any { 
                (it.isDirectory && it.name == "workspaces") || (it.isDirectory && it.name.startsWith("workspace-"))
            }
        }.getOrDefault(false)

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
